package radisson.actors.completion

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.backend.LlamaBackendSupervisor
import radisson.actors.http.api.models.{
  ChatCompletionRequest,
  ChatCompletionResponse,
  ErrorDetail,
  ErrorResponse
}
import radisson.config.AppConfig
import radisson.util.Logging

object CompletionRequestDispatcher extends Logging {

  private val MaxBackendStartRetries = 20
  private val BackendStartRetryDelay = 3.seconds

  private object QueueAdapter {
    def behavior(
        queue: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[
          StreamingCompletionRequestActor.ChunkMessage
        ]
    ): Behavior[StreamingCompletionRequestActor.ChunkMessage] =
      Behaviors.receive { (context, message) =>
        queue.offer(message)
        message match {
          case StreamingCompletionRequestActor.ChunkMessage.Completed |
              StreamingCompletionRequestActor.ChunkMessage.Failed(_, _) =>
            queue.complete()
            Behaviors.stopped
          case _ =>
            Behaviors.same
        }
      }
  }

  enum Command {
    case Initialize(
        config: AppConfig,
        backendSupervisor: ActorRef[LlamaBackendSupervisor.Command]
    )

    case HandleCompletion(
        request: ChatCompletionRequest,
        replyTo: ActorRef[CompletionResponse]
    )

    case HandleStreamingCompletion(
        request: ChatCompletionRequest,
        queue: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[
          StreamingCompletionRequestActor.ChunkMessage
        ]
    )

    case BackendResolved(
        backendId: String,
        backendResponse: LlamaBackendSupervisor.BackendResponse,
        request: ChatCompletionRequest,
        replyTo: ActorRef[CompletionResponse],
        retryCount: Int = 0
    )

    case StreamingBackendResolved(
        backendId: String,
        backendResponse: LlamaBackendSupervisor.BackendResponse,
        request: ChatCompletionRequest,
        queue: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[
          StreamingCompletionRequestActor.ChunkMessage
        ],
        retryCount: Int = 0
    )

    case RequestCompleted(
        requestId: String,
        actor: ActorRef[?]
    )
  }

  enum CompletionResponse {
    case Success(response: ChatCompletionResponse)
    case Error(error: ErrorResponse, statusCode: Int)
  }

  case class DispatcherState(
      config: AppConfig,
      backendSupervisor: ActorRef[LlamaBackendSupervisor.Command],
      activeRequests: Map[String, ActorRef[?]]
  )

  def behavior: Behavior[Command] = Behaviors.setup { context =>

    def uninitialized(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.Initialize(config, backendSupervisor) =>
        log.info("CompletionRequestDispatcher initialized")
        active(
          DispatcherState(
            config = config,
            backendSupervisor = backendSupervisor,
            activeRequests = Map.empty
          )
        )

      case _ =>
        log.warn("Received message before initialization")
        Behaviors.same
    }

    def active(state: DispatcherState): Behavior[Command] =
      Behaviors.receiveMessage {
        case Command.HandleCompletion(request, replyTo) =>
          BackendResolver.resolveBackend(request.model, state.config) match {
            case Left(error) =>
              replyTo ! CompletionResponse.Error(error, 400)
              Behaviors.same

            case Right(backendConfig) =>
              val responseAdapter =
                context.messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                  response =>
                    Command.BackendResolved(
                      backendConfig.id,
                      response,
                      request,
                      replyTo
                    )
                }

              state.backendSupervisor ! LlamaBackendSupervisor.Command
                .RequestBackend(
                  backendConfig.id,
                  responseAdapter
                )
              Behaviors.same
          }

        case Command.HandleStreamingCompletion(request, queue) =>
          BackendResolver.resolveBackend(request.model, state.config) match {
            case Left(error) =>
              queue.offer(
                StreamingCompletionRequestActor.ChunkMessage.Failed(
                  error.error.message,
                  Some(400)
                )
              )
              queue.complete()
              Behaviors.same

            case Right(backendConfig) =>
              val responseAdapter =
                context.messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                  response =>
                    Command.StreamingBackendResolved(
                      backendConfig.id,
                      response,
                      request,
                      queue
                    )
                }

              state.backendSupervisor ! LlamaBackendSupervisor.Command
                .RequestBackend(
                  backendConfig.id,
                  responseAdapter
                )
              Behaviors.same
          }

        case Command.BackendResolved(
              backendId,
              backendResponse,
              request,
              replyTo,
              retryCount
            ) =>
          backendResponse match {
            case LlamaBackendSupervisor.BackendResponse.Starting =>
              if retryCount >= MaxBackendStartRetries then
                log.warn(
                  "Backend '{}' did not start after {} attempts",
                  backendId,
                  MaxBackendStartRetries
                )
                replyTo ! CompletionResponse.Error(
                  ErrorResponse(
                    ErrorDetail(
                      s"Backend '$backendId' failed to start in time. Please try again later.",
                      "service_unavailable"
                    )
                  ),
                  503
                )
                Behaviors.same
              else
                log.debug(
                  "Backend '{}' is starting, retrying in {} (attempt {}/{})",
                  backendId,
                  BackendStartRetryDelay,
                  retryCount + 1,
                  MaxBackendStartRetries
                )

                val responseAdapter =
                  context
                    .messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                      response =>
                        Command.BackendResolved(
                          backendId,
                          response,
                          request,
                          replyTo,
                          retryCount + 1
                        )
                    }

                context.scheduleOnce(
                  BackendStartRetryDelay,
                  state.backendSupervisor,
                  LlamaBackendSupervisor.Command.RequestBackend(
                    backendId,
                    responseAdapter
                  )
                )
                Behaviors.same

            case LlamaBackendSupervisor.BackendResponse
                  .Available(endpoint, port) =>
              val requestId = java.util.UUID.randomUUID().toString
              val backendConfig =
                state.config.backends.find(_.id == backendId).get

              val endpointInfo = RequestBuilder.buildEndpointInfo(
                backendConfig,
                endpoint,
                port,
                state.config.server.request_timeout
              )

              val requestActor = context.spawn(
                CompletionRequestActor.behavior(
                  requestId = requestId,
                  request = request,
                  endpointInfo = endpointInfo,
                  replyTo = replyTo,
                  dispatcher = context.self
                ),
                s"completion-request-$requestId"
              )

              requestActor ! CompletionRequestActor.Command.Execute

              active(
                state.copy(
                  activeRequests =
                    state.activeRequests + (requestId -> requestActor)
                )
              )

            case LlamaBackendSupervisor.BackendResponse.Failed(reason) =>
              replyTo ! CompletionResponse.Error(
                ErrorResponse(
                  ErrorDetail(
                    s"Backend failed: $reason",
                    "service_error"
                  )
                ),
                500
              )
              Behaviors.same
          }

        case Command.StreamingBackendResolved(
              backendId,
              backendResponse,
              request,
              queue,
              retryCount
            ) =>
          backendResponse match {
            case LlamaBackendSupervisor.BackendResponse.Starting =>
              if retryCount >= MaxBackendStartRetries then
                log.warn(
                  "Backend '{}' did not start after {} attempts (streaming)",
                  backendId,
                  MaxBackendStartRetries
                )
                queue.offer(
                  StreamingCompletionRequestActor.ChunkMessage.Failed(
                    s"Backend '$backendId' failed to start in time. Please try again later.",
                    Some(503)
                  )
                )
                queue.complete()
                Behaviors.same
              else
                log.debug(
                  "Backend '{}' is starting, retrying in {} (streaming attempt {}/{})",
                  backendId,
                  BackendStartRetryDelay,
                  retryCount + 1,
                  MaxBackendStartRetries
                )

                val responseAdapter =
                  context
                    .messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                      response =>
                        Command.StreamingBackendResolved(
                          backendId,
                          response,
                          request,
                          queue,
                          retryCount + 1
                        )
                    }

                context.scheduleOnce(
                  BackendStartRetryDelay,
                  state.backendSupervisor,
                  LlamaBackendSupervisor.Command.RequestBackend(
                    backendId,
                    responseAdapter
                  )
                )
                Behaviors.same

            case LlamaBackendSupervisor.BackendResponse
                  .Available(endpoint, port) =>
              val requestId = java.util.UUID.randomUUID().toString
              val backendConfig =
                state.config.backends.find(_.id == backendId).get

              val endpointInfo = RequestBuilder.buildEndpointInfo(
                backendConfig,
                endpoint,
                port,
                state.config.server.request_timeout
              )

              val chunkListener = context.spawn(
                QueueAdapter.behavior(queue),
                s"queue-adapter-$requestId"
              )

              val streamingActor = context.spawn(
                StreamingCompletionRequestActor.behavior(
                  chunkListener,
                  context.self,
                  requestId
                ),
                s"streaming-completion-request-$requestId"
              )

              streamingActor ! StreamingCompletionRequestActor.Command.Execute(
                request,
                endpointInfo
              )

              active(
                state.copy(
                  activeRequests =
                    state.activeRequests + (requestId -> streamingActor)
                )
              )

            case LlamaBackendSupervisor.BackendResponse.Failed(reason) =>
              queue.offer(
                StreamingCompletionRequestActor.ChunkMessage.Failed(
                  s"Backend failed: $reason",
                  Some(500)
                )
              )
              queue.complete()
              Behaviors.same
          }

        case Command.RequestCompleted(requestId, actor) =>
          log.debug("Request {} completed, cleaning up actor", requestId)
          context.stop(actor)
          active(
            state.copy(
              activeRequests = state.activeRequests - requestId
            )
          )
      }

    uninitialized()
  }
}
