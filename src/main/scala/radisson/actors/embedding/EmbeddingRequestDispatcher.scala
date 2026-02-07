package radisson.actors.embedding

import scala.collection.immutable
import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.backend.LlamaBackendSupervisor
import radisson.actors.completion.{BackendResolver, RequestBuilder}
import radisson.actors.http.api.models.{
  EmbeddingRequest,
  EmbeddingResponse,
  ErrorDetail,
  ErrorResponse
}
import radisson.config.AppConfig
import radisson.util.Logging

object EmbeddingRequestDispatcher extends Logging {

  private val MaxBackendStartRetries = 20
  private val BackendStartRetryDelay = 3.seconds

  enum RequestPriority(val value: Int) {
    case RunningBackend extends RequestPriority(1)
    case StartingBackend extends RequestPriority(2)
    case NewBackend extends RequestPriority(3)
  }

  case class QueuedRequest(
      request: EmbeddingRequest,
      backendId: String,
      requestId: String,
      queuedAt: Long,
      replyTo: ActorRef[EmbeddingCompletionResponse],
      priority: RequestPriority
  )

  given Ordering[QueuedRequest] =
    Ordering.by(req => (req.priority.value, req.queuedAt))

  enum Command {
    case Initialize(
        config: AppConfig,
        backendSupervisor: ActorRef[LlamaBackendSupervisor.Command]
    )

    case HandleEmbedding(
        request: EmbeddingRequest,
        replyTo: ActorRef[EmbeddingCompletionResponse]
    )

    case BackendResolved(
        backendId: String,
        backendResponse: LlamaBackendSupervisor.BackendResponse,
        request: EmbeddingRequest,
        replyTo: ActorRef[EmbeddingCompletionResponse],
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

  enum EmbeddingCompletionResponse {
    case Success(response: EmbeddingResponse)
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
      drainingBackends: Set[String]
  )

  def behavior: Behavior[Command] = Behaviors.setup { context =>

    def uninitialized(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.Initialize(config, backendSupervisor) =>
        log.info("EmbeddingRequestDispatcher initialized")
        active(
          DispatcherState(
            config = config,
            backendSupervisor = backendSupervisor,
            activeRequests = Map.empty,
            backendInFlightRequests = Map.empty,
            pendingQueue = immutable.Queue.empty,
            drainingBackends = Set.empty
          )
        )

      case other =>
        log.warn("Received message before initialization: {}", other)
        Behaviors.same
    }

    def active(state: DispatcherState): Behavior[Command] =
      Behaviors.receiveMessage {
        case Command.HandleEmbedding(request, replyTo) =>
          val requestId = java.util.UUID.randomUUID().toString

          BackendResolver.resolveBackend(
            request.model,
            state.config,
            Set("local-embeddings")
          ) match {
            case Left(errorResponse) =>
              replyTo ! EmbeddingCompletionResponse.Error(errorResponse, 400)
              Behaviors.same

            case Right(backend) =>
              state.backendSupervisor ! LlamaBackendSupervisor.Command
                .RequestBackend(
                  backend.id,
                  context.messageAdapter(response =>
                    Command.BackendResolved(
                      backend.id,
                      response,
                      request,
                      replyTo
                    )
                  )
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
            case LlamaBackendSupervisor.BackendResponse.Available(
                  endpoint,
                  port
                ) =>
              val requestId = java.util.UUID.randomUUID().toString
              val backendConfig =
                state.config.backends.find(_.id == backendId).get

              val endpointInfo = RequestBuilder.buildEmbeddingEndpointInfo(
                backendConfig,
                endpoint,
                port,
                state.config.server.request_timeout
              )

              val requestActor =
                context.spawn(
                  EmbeddingRequestActor.behavior(
                    requestId,
                    backendId,
                    request,
                    endpointInfo,
                    replyTo,
                    context.self
                  ),
                  s"embedding-request-$requestId"
                )

              requestActor ! EmbeddingRequestActor.Command.Execute

              val newActiveRequests =
                state.activeRequests + (requestId -> RequestInfo(
                  requestActor,
                  backendId
                ))
              val newInFlight = state.backendInFlightRequests
                .getOrElse(backendId, Set.empty) + requestId

              active(
                state.copy(
                  activeRequests = newActiveRequests,
                  backendInFlightRequests =
                    state.backendInFlightRequests + (backendId -> newInFlight)
                )
              )

            case LlamaBackendSupervisor.BackendResponse.Starting
                if retryCount < MaxBackendStartRetries =>
              context.scheduleOnce(
                BackendStartRetryDelay,
                context.self,
                Command.BackendResolved(
                  backendId,
                  LlamaBackendSupervisor.BackendResponse.Starting,
                  request,
                  replyTo,
                  retryCount + 1
                )
              )
              Behaviors.same

            case LlamaBackendSupervisor.BackendResponse.Starting =>
              replyTo ! EmbeddingCompletionResponse.Error(
                ErrorResponse(
                  ErrorDetail(
                    "Backend is starting but took too long",
                    "service_unavailable"
                  )
                ),
                503
              )
              Behaviors.same

            case LlamaBackendSupervisor.BackendResponse.Failed(reason) =>
              replyTo ! EmbeddingCompletionResponse.Error(
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

        case Command.RequestCompleted(requestId, backendId, actor) =>
          val newActiveRequests = state.activeRequests - requestId
          val newInFlight = state.backendInFlightRequests
            .getOrElse(backendId, Set.empty) - requestId

          val newBackendInFlightRequests =
            if newInFlight.isEmpty then
              state.backendInFlightRequests - backendId
            else state.backendInFlightRequests + (backendId -> newInFlight)

          val newState = state.copy(
            activeRequests = newActiveRequests,
            backendInFlightRequests = newBackendInFlightRequests
          )

          if state.drainingBackends.contains(backendId) then {
            context.self ! Command.CheckDrainComplete(backendId)
          }

          context.self ! Command.ProcessQueue

          active(newState)

        case Command.ProcessQueue =>
          Behaviors.same

        case Command.BeginDraining(backendId) =>
          log.info("Beginning drain for backend: {}", backendId)
          active(
            state.copy(drainingBackends = state.drainingBackends + backendId)
          )

        case Command.CheckDrainComplete(backendId) =>
          val inFlightCount =
            state.backendInFlightRequests.getOrElse(backendId, Set.empty).size

          if inFlightCount == 0 then {
            log.info("Drain complete for backend: {}", backendId)
            state.backendSupervisor ! LlamaBackendSupervisor.Command
              .BackendDrained(backendId)
            active(
              state.copy(drainingBackends = state.drainingBackends - backendId)
            )
          } else {
            log.debug(
              "Drain in progress for backend: {} ({} requests remaining)",
              backendId,
              inFlightCount
            )
            Behaviors.same
          }

        case Command.Initialize(_, _) =>
          log.warn("Already initialized, ignoring")
          Behaviors.same
      }

    uninitialized()
  }
}
