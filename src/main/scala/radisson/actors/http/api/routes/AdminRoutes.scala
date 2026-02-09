package radisson.actors.http.api.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import radisson.actors.backend.LlamaBackendSupervisor
import radisson.actors.http.api.models._
import radisson.util.JsonSupport.given
import radisson.util.Logging

object AdminRoutes extends Logging {

  private val DefaultHoldTtlSeconds = 300
  private val MaxHoldTtlSeconds = 3600

  def routes(
      backendSupervisor: ActorRef[LlamaBackendSupervisor.Command]
  )(using system: org.apache.pekko.actor.typed.ActorSystem[?]): Route = {
    given timeout: Timeout = Timeout(30.seconds)

    pathPrefix("admin" / "backend") {
      path(Segment / "acquire") { backendId =>
        post {
          entity(as[AcquireHoldRequest]) { request =>
            val ttl = request.ttl_seconds.getOrElse(DefaultHoldTtlSeconds)

            if (ttl > MaxHoldTtlSeconds) {
              complete(
                StatusCodes.BadRequest,
                ErrorResponse(
                  ErrorDetail(
                    s"ttl_seconds cannot exceed $MaxHoldTtlSeconds",
                    "invalid_request"
                  )
                )
              )
            } else {
              val responseFuture = backendSupervisor.ask(replyTo =>
                LlamaBackendSupervisor.Command.AcquireHold(
                  backendId,
                  ttl,
                  replyTo
                )
              )

              onSuccess(responseFuture) {
                case LlamaBackendSupervisor.HoldResponse.Acquired(
                      id,
                      bType,
                      status,
                      port,
                      endpoint,
                      acquiredAt,
                      expiresAt
                    ) =>
                  complete(
                    StatusCodes.OK,
                    AcquireHoldResponse(
                      backend_id = id,
                      backend_type = bType,
                      status = status,
                      port = port,
                      endpoint = endpoint,
                      hold_acquired_at = acquiredAt,
                      hold_expires_at = expiresAt
                    )
                  )

                case LlamaBackendSupervisor.HoldResponse.NotFound(_) =>
                  complete(
                    StatusCodes.NotFound,
                    ErrorResponse(
                      ErrorDetail(
                        s"Backend '$backendId' not found in configuration",
                        "not_found"
                      )
                    )
                  )

                case LlamaBackendSupervisor.HoldResponse.InsufficientMemory(
                      _,
                      required
                    ) =>
                  complete(
                    StatusCodes.ServiceUnavailable,
                    ErrorResponse(
                      ErrorDetail(
                        s"Insufficient memory to start backend (requires ${required} bytes)",
                        "resource_exhausted"
                      )
                    )
                  )

                case LlamaBackendSupervisor.HoldResponse.Failed(_, reason) =>
                  complete(
                    StatusCodes.InternalServerError,
                    ErrorResponse(ErrorDetail(reason, "internal_error"))
                  )

                case _ =>
                  complete(
                    StatusCodes.InternalServerError,
                    ErrorResponse(
                      ErrorDetail(
                        "Unexpected response from backend supervisor",
                        "internal_error"
                      )
                    )
                  )
              }
            }
          }
        }
      } ~
      path(Segment / "release") { backendId =>
        post {
          val responseFuture = backendSupervisor.ask(replyTo =>
            LlamaBackendSupervisor.Command.ReleaseHold(backendId, replyTo)
          )

          onSuccess(responseFuture) {
            case LlamaBackendSupervisor.HoldResponse.Released(id, timestamp) =>
              complete(
                StatusCodes.OK,
                ReleaseHoldResponse(
                  backend_id = id,
                  status = "released",
                  released_at = timestamp
                )
              )

            case LlamaBackendSupervisor.HoldResponse.NotFound(_) =>
              complete(
                StatusCodes.OK,
                ReleaseHoldResponse(
                  backend_id = backendId,
                  status = "released",
                  released_at = System.currentTimeMillis() / 1000
                )
              )

            case _ =>
              complete(
                StatusCodes.InternalServerError,
                ErrorResponse(
                  ErrorDetail(
                    "Unexpected response from backend supervisor",
                    "internal_error"
                  )
                )
              )
          }
        }
      }
    }
  }
}
