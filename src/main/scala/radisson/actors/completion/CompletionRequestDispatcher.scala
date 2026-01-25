package radisson.actors.completion

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.backend.LlamaBackendSupervisor
import radisson.actors.http.api.models.{ChatCompletionRequest, ChatCompletionResponse, ErrorResponse, ErrorDetail}
import radisson.config.AppConfig
import radisson.util.Logging

object CompletionRequestDispatcher extends Logging {

  enum Command {
    case Initialize(
      config: AppConfig,
      backendSupervisor: ActorRef[LlamaBackendSupervisor.Command]
    )

    case HandleCompletion(
      request: ChatCompletionRequest,
      replyTo: ActorRef[CompletionResponse]
    )

    case BackendResolved(
      backendId: String,
      backendResponse: LlamaBackendSupervisor.BackendResponse,
      request: ChatCompletionRequest,
      replyTo: ActorRef[CompletionResponse]
    )

    case RequestCompleted(
      requestId: String,
      actor: ActorRef[CompletionRequestActor.Command]
    )
  }

  enum CompletionResponse {
    case Success(response: ChatCompletionResponse)
    case Error(error: ErrorResponse, statusCode: Int)
  }

  case class DispatcherState(
    config: AppConfig,
    backendSupervisor: ActorRef[LlamaBackendSupervisor.Command],
    activeRequests: Map[String, ActorRef[CompletionRequestActor.Command]]
  )

  def behavior: Behavior[Command] = Behaviors.setup { context =>

    def uninitialized(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.Initialize(config, backendSupervisor) =>
        log.info("CompletionRequestDispatcher initialized")
        active(DispatcherState(
          config = config,
          backendSupervisor = backendSupervisor,
          activeRequests = Map.empty
        ))

      case _ =>
        log.warn("Received message before initialization")
        Behaviors.same
    }

    def active(state: DispatcherState): Behavior[Command] = Behaviors.receiveMessage {
      case Command.HandleCompletion(request, replyTo) =>
        BackendResolver.resolveBackend(request.model, state.config) match {
          case Left(error) =>
            replyTo ! CompletionResponse.Error(error, 400)
            Behaviors.same

          case Right(backendConfig) =>
            val responseAdapter = context.messageAdapter[LlamaBackendSupervisor.BackendResponse] { response =>
              Command.BackendResolved(backendConfig.id, response, request, replyTo)
            }

            state.backendSupervisor ! LlamaBackendSupervisor.Command.RequestBackend(
              backendConfig.id,
              responseAdapter
            )
            Behaviors.same
        }

      case Command.BackendResolved(backendId, backendResponse, request, replyTo) =>
        backendResponse match {
          case LlamaBackendSupervisor.BackendResponse.Starting =>
            replyTo ! CompletionResponse.Error(
              ErrorResponse(ErrorDetail(
                s"Backend '$backendId' is starting. Please retry in a few seconds.",
                "service_unavailable"
              )),
              503
            )
            Behaviors.same

          case LlamaBackendSupervisor.BackendResponse.Available(endpoint, port) =>
            val requestId = java.util.UUID.randomUUID().toString
            val backendConfig = state.config.backends.find(_.id == backendId).get

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

            active(state.copy(
              activeRequests = state.activeRequests + (requestId -> requestActor)
            ))

          case LlamaBackendSupervisor.BackendResponse.Failed(reason) =>
            replyTo ! CompletionResponse.Error(
              ErrorResponse(ErrorDetail(
                s"Backend failed: $reason",
                "service_error"
              )),
              500
            )
            Behaviors.same
        }

      case Command.RequestCompleted(requestId, actor) =>
        log.debug("Request {} completed, cleaning up actor", requestId)
        context.stop(actor)
        active(state.copy(
          activeRequests = state.activeRequests - requestId
        ))
    }

    uninitialized()
  }
}
