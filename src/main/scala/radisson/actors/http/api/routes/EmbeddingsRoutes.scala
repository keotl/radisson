package radisson.actors.http.api.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import radisson.actors.embedding.EmbeddingRequestDispatcher
import radisson.actors.http.api.models._
import radisson.config.AppConfig
import radisson.util.JsonSupport.given

object EmbeddingsRoutes {
  def routes(
      config: AppConfig,
      dispatcher: ActorRef[EmbeddingRequestDispatcher.Command]
  )(using system: ActorSystem[?]): Route = {

    given timeout: Timeout = Timeout(config.server.request_timeout.seconds)

    pathPrefix("v1") {
      path("embeddings") {
        post {
          entity(as[EmbeddingRequest]) { request =>
            validateRequest(request) match
              case Left(error) =>
                complete(StatusCodes.BadRequest, error)
              case Right(_) =>
                val responseFuture = dispatcher
                  .ask[EmbeddingRequestDispatcher.EmbeddingCompletionResponse](
                    replyTo =>
                      EmbeddingRequestDispatcher.Command
                        .HandleEmbedding(request, replyTo)
                  )

                onSuccess(responseFuture) {
                  case EmbeddingRequestDispatcher.EmbeddingCompletionResponse
                        .Success(
                          response
                        ) =>
                    complete(response)

                  case EmbeddingRequestDispatcher.EmbeddingCompletionResponse
                        .Error(error, statusCode) =>
                    complete(StatusCode.int2StatusCode(statusCode), error)
                }
          }
        }
      }
    }
  }

  private def validateRequest(
      req: EmbeddingRequest
  ): Either[ErrorResponse, Unit] = {
    val inputs = req.input.toList
    if inputs.isEmpty then
      Left(
        ErrorResponse(
          ErrorDetail("input cannot be empty", "invalid_request")
        )
      )
    else if inputs.exists(_.isEmpty) then
      Left(
        ErrorResponse(
          ErrorDetail("input cannot contain empty strings", "invalid_request")
        )
      )
    else if req.model.isEmpty then
      Left(
        ErrorResponse(ErrorDetail("model cannot be empty", "invalid_request"))
      )
    else Right(())
  }
}
