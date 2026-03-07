package radisson.actors.http.api.routes

import scala.concurrent.duration._

import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.model.MediaType
import org.apache.pekko.http.scaladsl.model.ContentType
import org.apache.pekko.http.scaladsl.model.HttpCharsets
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
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
import radisson.util.JsonSupport
import radisson.util.JsonSupport.given

object ChatCompletionsRoutes {
  def routes(
      config: AppConfig,
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  )(using system: ActorSystem[?]): Route = {

    given timeout: Timeout = Timeout(config.server.request_timeout.seconds)
    given requestUnmarshaller
        : org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller[
          ChatCompletionRequest
        ] =
      JsonSupport.checkingUnmarshaller[ChatCompletionRequest](
        "ChatCompletionRequest"
      )

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
                    val requestId = java.util.UUID.randomUUID().toString
                    val (queueRef, source) = Source
                      .queue[StreamingCompletionRequestActor.ChunkMessage](
                        bufferSize = 100,
                        OverflowStrategy.backpressure
                      )
                      .preMaterialize()

                    dispatcher ! CompletionRequestDispatcher.Command
                      .HandleStreamingCompletion(
                        requestId,
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

                    val sseContentType = MediaType.customWithFixedCharset(
                      "text",
                      "event-stream",
                      HttpCharsets.`UTF-8`
                    )
                    val byteSource = sseSource.map { event =>
                      val sb = new StringBuilder
                      event.eventType.foreach(t => sb.append(s"event:$t\n"))
                      event.data.split("\n").foreach(line => sb.append(s"data:$line\n"))
                      sb.append("\n")
                      org.apache.pekko.util.ByteString(sb.toString)
                    }
                    complete(HttpResponse(
                      entity = HttpEntity.Chunked.fromData(
                        ContentType(sseContentType),
                        byteSource
                      )
                    ))
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
