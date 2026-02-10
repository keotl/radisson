package radisson.actors.completion

import scala.collection.immutable
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
import radisson.actors.tracing.RequestTracer
import radisson.config.AppConfig
import radisson.util.Logging

object CompletionRequestDispatcher extends Logging {

  private val MaxBackendStartRetries = 20
  private val BackendStartRetryDelay = 3.seconds

  enum RequestPriority(val value: Int) {
    case RunningBackend extends RequestPriority(1)
    case StartingBackend extends RequestPriority(2)
    case NewBackend extends RequestPriority(3)
  }

  case class QueuedRequest(
      request: ChatCompletionRequest,
      backendId: String,
      requestId: String,
      queuedAt: Long,
      replyTo: Either[
        ActorRef[CompletionResponse],
        org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[
          StreamingCompletionRequestActor.ChunkMessage
        ]
      ],
      isStreaming: Boolean,
      priority: RequestPriority
  )

  given Ordering[QueuedRequest] =
    Ordering.by(req => (req.priority.value, req.queuedAt))

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
        backendSupervisor: ActorRef[LlamaBackendSupervisor.Command],
        requestTracer: Option[ActorRef[RequestTracer.Command]] = None
    )

    case HandleCompletion(
        request: ChatCompletionRequest,
        replyTo: ActorRef[CompletionResponse]
    )

    case HandleStreamingCompletion(
        requestId: String,
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
        requestId: String,
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
        backendId: String,
        actor: ActorRef[?]
    )

    case ProcessQueue

    case BeginDraining(backendId: String)
    case CheckDrainComplete(backendId: String)
  }

  enum CompletionResponse {
    case Success(response: ChatCompletionResponse)
    case Error(error: ErrorResponse, statusCode: Int)
  }

  case class RequestInfo(
      actor: ActorRef[?],
      backendId: String
  )

  case class DispatcherState(
      config: AppConfig,
      backendSupervisor: ActorRef[LlamaBackendSupervisor.Command],
      activeRequests: Map[String, RequestInfo],
      backendInFlightRequests: Map[String, Set[String]],
      pendingQueue: immutable.Queue[QueuedRequest],
      drainingBackends: Set[String],
      requestTracer: Option[ActorRef[RequestTracer.Command]] = None
  )

  def behavior: Behavior[Command] = Behaviors.setup { context =>

    def uninitialized(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.Initialize(config, backendSupervisor, requestTracer) =>
        log.info("CompletionRequestDispatcher initialized")
        active(
          DispatcherState(
            config = config,
            backendSupervisor = backendSupervisor,
            activeRequests = Map.empty,
            backendInFlightRequests = Map.empty,
            pendingQueue = immutable.Queue.empty,
            drainingBackends = Set.empty,
            requestTracer = requestTracer
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
              val requestId = java.util.UUID.randomUUID().toString
              val queuedRequest = QueuedRequest(
                request = request,
                backendId = backendConfig.id,
                requestId = requestId,
                queuedAt = System.currentTimeMillis(),
                replyTo = Left(replyTo),
                isStreaming = false,
                priority = RequestPriority.NewBackend
              )

              log.debug(
                "Enqueueing request {} for backend {}",
                requestId,
                backendConfig.id
              )
              val updatedState = state.copy(pendingQueue =
                state.pendingQueue.enqueue(queuedRequest)
              )
              context.self ! Command.ProcessQueue
              active(updatedState)
          }

        case Command.HandleStreamingCompletion(requestId, request, queue) =>
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
              val queuedRequest = QueuedRequest(
                request = request,
                backendId = backendConfig.id,
                requestId = requestId,
                queuedAt = System.currentTimeMillis(),
                replyTo = Right(queue),
                isStreaming = true,
                priority = RequestPriority.NewBackend
              )

              log.debug(
                "Enqueueing streaming request {} for backend {}",
                requestId,
                backendConfig.id
              )
              val updatedState = state.copy(pendingQueue =
                state.pendingQueue.enqueue(queuedRequest)
              )
              context.self ! Command.ProcessQueue
              active(updatedState)
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
                  backendId = backendId,
                  request = request,
                  endpointInfo = endpointInfo,
                  replyTo = replyTo,
                  dispatcher = context.self,
                  requestTracer = state.requestTracer
                ),
                s"completion-request-$requestId"
              )

              requestActor ! CompletionRequestActor.Command.Execute

              val updatedInFlight =
                state.backendInFlightRequests.get(backendId) match {
                  case Some(requests) => requests + requestId
                  case None           => Set(requestId)
                }

              active(
                state.copy(
                  activeRequests =
                    state.activeRequests + (requestId -> RequestInfo(
                      requestActor,
                      backendId
                    )),
                  backendInFlightRequests =
                    state.backendInFlightRequests + (backendId -> updatedInFlight)
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
              requestId,
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
                          requestId,
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
              if state.activeRequests.contains(requestId) then
                log.debug(
                  "Request {} already has an active streaming actor, ignoring duplicate backend resolution",
                  requestId
                )
                Behaviors.same
              else
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
                    requestId,
                    backendId,
                    state.requestTracer
                  ),
                  s"streaming-completion-request-$requestId"
                )

                streamingActor ! StreamingCompletionRequestActor.Command
                  .Execute(
                    request,
                    endpointInfo
                  )

                val updatedInFlight =
                  state.backendInFlightRequests.get(backendId) match {
                    case Some(requests) => requests + requestId
                    case None           => Set(requestId)
                  }

                active(
                  state.copy(
                    activeRequests =
                      state.activeRequests + (requestId -> RequestInfo(
                        streamingActor,
                        backendId
                      )),
                    backendInFlightRequests =
                      state.backendInFlightRequests + (backendId -> updatedInFlight)
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

        case Command.RequestCompleted(requestId, backendId, actor) =>
          log.debug(
            "Request {} completed for backend {}, cleaning up actor",
            requestId,
            backendId
          )
          context.stop(actor)

          val updatedInFlight =
            state.backendInFlightRequests.get(backendId) match {
              case Some(requests) =>
                val remaining = requests - requestId
                if remaining.isEmpty then None else Some(remaining)
              case None => None
            }

          val newState = state.copy(
            activeRequests = state.activeRequests - requestId,
            backendInFlightRequests = updatedInFlight match {
              case Some(requests) =>
                state.backendInFlightRequests + (backendId -> requests)
              case None => state.backendInFlightRequests - backendId
            }
          )

          if state.drainingBackends.contains(
              backendId
            ) && updatedInFlight.isEmpty
          then
            log.info(
              "Backend {} fully drained, notifying supervisor",
              backendId
            )
            state.backendSupervisor ! LlamaBackendSupervisor.Command
              .BackendDrained(backendId)
            val finalState = newState.copy(drainingBackends =
              newState.drainingBackends - backendId
            )
            context.self ! Command.ProcessQueue
            active(finalState)
          else
            context.self ! Command.ProcessQueue
            active(newState)

        case Command.ProcessQueue =>
          if state.pendingQueue.isEmpty then Behaviors.same
          else
            val (queuedRequest, remainingQueue) = state.pendingQueue.dequeue
            log.debug(
              "Processing queued request {} for backend {} (queued at {}, priority {})",
              queuedRequest.requestId,
              queuedRequest.backendId,
              queuedRequest.queuedAt,
              queuedRequest.priority
            )

            if queuedRequest.isStreaming then
              val responseAdapter =
                context.messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                  response =>
                    Command.StreamingBackendResolved(
                      queuedRequest.requestId,
                      queuedRequest.backendId,
                      response,
                      queuedRequest.request,
                      queuedRequest.replyTo.toOption.get
                    )
                }

              state.backendSupervisor ! LlamaBackendSupervisor.Command
                .RequestBackend(
                  queuedRequest.backendId,
                  responseAdapter
                )
            else
              val responseAdapter =
                context.messageAdapter[LlamaBackendSupervisor.BackendResponse] {
                  response =>
                    Command.BackendResolved(
                      queuedRequest.backendId,
                      response,
                      queuedRequest.request,
                      queuedRequest.replyTo.left.get
                    )
                }

              state.backendSupervisor ! LlamaBackendSupervisor.Command
                .RequestBackend(
                  queuedRequest.backendId,
                  responseAdapter
                )

            active(state.copy(pendingQueue = remainingQueue))

        case Command.BeginDraining(backendId) =>
          log.info("Backend {} entering draining state", backendId)
          val updatedState =
            state.copy(drainingBackends = state.drainingBackends + backendId)
          context.self ! Command.CheckDrainComplete(backendId)
          active(updatedState)

        case Command.CheckDrainComplete(backendId) =>
          if state.drainingBackends.contains(backendId) then
            state.backendInFlightRequests.get(backendId) match {
              case Some(requests) if requests.nonEmpty =>
                log.debug(
                  "Backend {} still has {} in-flight requests",
                  backendId,
                  requests.size
                )
                Behaviors.same
              case _ =>
                log.info(
                  "Backend {} has no in-flight requests, notifying supervisor",
                  backendId
                )
                state.backendSupervisor ! LlamaBackendSupervisor.Command
                  .BackendDrained(backendId)
                active(
                  state.copy(drainingBackends =
                    state.drainingBackends - backendId
                  )
                )
            }
          else Behaviors.same
      }

    uninitialized()
  }
}
