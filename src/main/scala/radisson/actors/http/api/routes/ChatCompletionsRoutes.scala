package radisson.actors.http.api.routes

import scala.concurrent.duration._

import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import radisson.actors.completion.{
  CompletionRequestDispatcher,
  StreamingCompletionRequestActor
}
import radisson.actors.http.api.models._
import radisson.config.AppConfig
import radisson.util.JsonSupport.given

object ChatCompletionsRoutes {
  def routes(
      config: AppConfig,
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  )(using system: ActorSystem[?]): Route = {

    given timeout: Timeout = Timeout(config.server.request_timeout.seconds)

    pathPrefix("v1") {
      path("models") {
        get {
          val response =
            OpenAIModelAssembler.buildModelsResponse(config.backends)
          complete(response)
        }
      } ~
        path("chat" / "completions") {
          post {
            entity(as[ChatCompletionRequest]) { request =>
              validateRequest(request) match
                case Left(error) =>
                  complete(StatusCodes.BadRequest, error)
                case Right(_) =>
                  if request.stream.contains(true) then {
                    val (queueRef, source) = Source
                      .queue[StreamingCompletionRequestActor.ChunkMessage](
                        bufferSize = 100,
                        OverflowStrategy.backpressure
                      )
                      .preMaterialize()

                    dispatcher ! CompletionRequestDispatcher.Command
                      .HandleStreamingCompletion(
                        request,
                        queueRef
                      )

                    val sseSource = source.map {
                      case StreamingCompletionRequestActor.ChunkMessage.Chunk(
                            data
                          ) =>
                        ServerSentEvent(data.asJson.noSpaces)
                      case StreamingCompletionRequestActor.ChunkMessage.Completed =>
                        ServerSentEvent("[DONE]")
                      case StreamingCompletionRequestActor.ChunkMessage
                            .Failed(error, _) =>
                        ServerSentEvent(
                          ErrorResponse(
                            ErrorDetail(error, "stream_error")
                          ).asJson.noSpaces,
                          eventType = Some("error")
                        )
                    }

                    complete(sseSource)
                  } else {
                    val responseFuture = dispatcher
                      .ask[CompletionRequestDispatcher.CompletionResponse](
                        replyTo =>
                          CompletionRequestDispatcher.Command
                            .HandleCompletion(request, replyTo)
                      )

                    onSuccess(responseFuture) {
                      case CompletionRequestDispatcher.CompletionResponse
                            .Success(
                              response
                            ) =>
                        complete(response)

                      case CompletionRequestDispatcher.CompletionResponse
                            .Error(error, statusCode) =>
                        complete(StatusCode.int2StatusCode(statusCode), error)
                    }
                  }
            }
          }
        }
    }
  }

  private def validateRequest(
      req: ChatCompletionRequest
  ): Either[ErrorResponse, Unit] =
    if req.messages.isEmpty then
      Left(
        ErrorResponse(
          ErrorDetail("messages cannot be empty", "invalid_request")
        )
      )
    else if req.model.isEmpty then
      Left(
        ErrorResponse(ErrorDetail("model cannot be empty", "invalid_request"))
      )
    else Right(())

}
