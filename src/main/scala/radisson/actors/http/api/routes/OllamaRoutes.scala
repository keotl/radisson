package radisson.actors.http.api.routes

import scala.concurrent.duration._

import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  MediaTypes,
  StatusCode,
  StatusCodes
}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import radisson.actors.completion.{
  CompletionRequestDispatcher,
  StreamingCompletionRequestActor
}
import radisson.actors.http.api.models._
import radisson.config.AppConfig
import radisson.util.JsonSupport.given

object OllamaRoutes {
  def routes(
      config: AppConfig,
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  )(using system: ActorSystem[?]): Route = {

    given timeout: Timeout = Timeout(config.server.request_timeout.seconds)

    pathPrefix("api") {
      path("tags") {
        get {
          val response =
            OllamaModelAssembler.buildOllamaResponse(config.backends)
          complete(response)
        }
      } ~
        path("chat") {
          post {
            entity(as[OllamaChatRequest]) { request =>
              validateOllamaChatRequest(request) match {
                case Left(error) =>
                  complete(StatusCodes.BadRequest, error)
                case Right(_) =>
                  val openAIRequest = OllamaAdapter.toOpenAIChatRequest(request)

                  if request.stream.getOrElse(true) then {
                    val (queueRef, source) = Source
                      .queue[StreamingCompletionRequestActor.ChunkMessage](
                        bufferSize = 100,
                        OverflowStrategy.backpressure
                      )
                      .preMaterialize()

                    dispatcher ! CompletionRequestDispatcher.Command
                      .HandleStreamingCompletion(
                        openAIRequest,
                        queueRef
                      )

                    val ndjsonSource = source.map {
                      case StreamingCompletionRequestActor.ChunkMessage.Chunk(
                            data
                          ) =>
                        val chunk = OllamaAdapter.toOllamaChatChunk(
                          data,
                          request.model,
                          isDone = false
                        )
                        ByteString(chunk.asJson.noSpaces + "\n")

                      case StreamingCompletionRequestActor.ChunkMessage
                            .Completed =>
                        val finalChunk = OllamaChatChunk(
                          model = request.model,
                          created_at = java.time.Instant.now().toString,
                          message = OllamaMessageDelta(),
                          done = true,
                          done_reason = Some("stop")
                        )
                        ByteString(finalChunk.asJson.noSpaces + "\n")

                      case StreamingCompletionRequestActor.ChunkMessage
                            .Failed(error, _) =>
                        val errorChunk = OllamaChatChunk(
                          model = request.model,
                          created_at = java.time.Instant.now().toString,
                          message = OllamaMessageDelta(
                            content = Some(s"Error: $error")
                          ),
                          done = true,
                          done_reason = Some("error")
                        )
                        ByteString(errorChunk.asJson.noSpaces + "\n")
                    }

                    complete(
                      HttpEntity(
                        ContentType(MediaTypes.`application/json`),
                        ndjsonSource
                      )
                    )
                  } else {
                    val responseFuture = dispatcher
                      .ask[CompletionRequestDispatcher.CompletionResponse](
                        replyTo =>
                          CompletionRequestDispatcher.Command
                            .HandleCompletion(openAIRequest, replyTo)
                      )

                    onSuccess(responseFuture) {
                      case CompletionRequestDispatcher.CompletionResponse
                            .Success(response) =>
                        val ollamaResponse =
                          OllamaAdapter.toOllamaChatResponse(
                            response,
                            request.model
                          )
                        complete(ollamaResponse)

                      case CompletionRequestDispatcher.CompletionResponse
                            .Error(error, statusCode) =>
                        complete(StatusCode.int2StatusCode(statusCode), error)
                    }
                  }
              }
            }
          }
        } ~
        path("generate") {
          post {
            entity(as[OllamaGenerateRequest]) { request =>
              validateOllamaGenerateRequest(request) match {
                case Left(error) =>
                  complete(StatusCodes.BadRequest, error)
                case Right(_) =>
                  val openAIRequest =
                    OllamaAdapter.toOpenAIChatRequestFromGenerate(request)

                  if request.stream.getOrElse(true) then {
                    val (queueRef, source) = Source
                      .queue[StreamingCompletionRequestActor.ChunkMessage](
                        bufferSize = 100,
                        OverflowStrategy.backpressure
                      )
                      .preMaterialize()

                    dispatcher ! CompletionRequestDispatcher.Command
                      .HandleStreamingCompletion(
                        openAIRequest,
                        queueRef
                      )

                    val ndjsonSource = source.map {
                      case StreamingCompletionRequestActor.ChunkMessage.Chunk(
                            data
                          ) =>
                        val chunk = OllamaAdapter.toOllamaGenerateChunk(
                          data,
                          request.model,
                          isDone = false
                        )
                        ByteString(chunk.asJson.noSpaces + "\n")

                      case StreamingCompletionRequestActor.ChunkMessage
                            .Completed =>
                        val finalChunk = OllamaGenerateChunk(
                          model = request.model,
                          created_at = java.time.Instant.now().toString,
                          response = "",
                          done = true,
                          done_reason = Some("stop")
                        )
                        ByteString(finalChunk.asJson.noSpaces + "\n")

                      case StreamingCompletionRequestActor.ChunkMessage
                            .Failed(error, _) =>
                        val errorChunk = OllamaGenerateChunk(
                          model = request.model,
                          created_at = java.time.Instant.now().toString,
                          response = s"Error: $error",
                          done = true,
                          done_reason = Some("error")
                        )
                        ByteString(errorChunk.asJson.noSpaces + "\n")
                    }

                    complete(
                      HttpEntity(
                        ContentType(MediaTypes.`application/json`),
                        ndjsonSource
                      )
                    )
                  } else {
                    val responseFuture = dispatcher
                      .ask[CompletionRequestDispatcher.CompletionResponse](
                        replyTo =>
                          CompletionRequestDispatcher.Command
                            .HandleCompletion(openAIRequest, replyTo)
                      )

                    onSuccess(responseFuture) {
                      case CompletionRequestDispatcher.CompletionResponse
                            .Success(response) =>
                        val ollamaResponse =
                          OllamaAdapter.toOllamaGenerateResponse(
                            response,
                            request.model
                          )
                        complete(ollamaResponse)

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
  }

  private def validateOllamaChatRequest(
      req: OllamaChatRequest
  ): Either[ErrorResponse, Unit] = {
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

  private def validateOllamaGenerateRequest(
      req: OllamaGenerateRequest
  ): Either[ErrorResponse, Unit] = {
    if req.prompt.isEmpty then
      Left(
        ErrorResponse(
          ErrorDetail("prompt cannot be empty", "invalid_request")
        )
      )
    else if req.model.isEmpty then
      Left(
        ErrorResponse(ErrorDetail("model cannot be empty", "invalid_request"))
      )
    else Right(())
  }
}
