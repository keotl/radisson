package radisson.actors.completion

import scala.concurrent.Future
import scala.util.{Failure, Success}

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.actors.http.api.models.{
  ChatCompletionChunk,
  ChatCompletionRequest
}
import radisson.actors.tracing.RequestTracer
import sttp.client4.WebSocketStreamBackend
import sttp.client4.pekkohttp.PekkoHttpBackend

object StreamingCompletionRequestActor {

  enum ChunkMessage {
    case Chunk(data: ChatCompletionChunk)
    case Completed
    case Failed(error: String, statusCode: Option[Int] = None)
  }

  enum Command {
    case Execute(
        request: ChatCompletionRequest,
        endpointInfo: EndpointInfo
    )
    case BackendStreamReady(source: Source[ByteString, ?])
    case ChunkReceived(chunk: ChatCompletionChunk)
    case StreamCompleted()
    case StreamFailed(error: Throwable)
  }

  def behavior(
      chunkListener: ActorRef[ChunkMessage],
      dispatcherRef: ActorRef[CompletionRequestDispatcher.Command],
      requestId: String,
      backendId: String,
      requestTracer: Option[ActorRef[RequestTracer.Command]] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      given system: org.apache.pekko.actor.typed.ActorSystem[?] =
        context.system
      given sttpBackend: WebSocketStreamBackend[
        Future,
        sttp.capabilities.pekko.PekkoStreams
      ] = PekkoHttpBackend.usingActorSystem(context.system.classicSystem)

      var model: String = ""
      var startedAt: Long = 0L
      var requestBody: Option[Json] = None

      Behaviors.receiveMessage {
        case Command.Execute(request, endpointInfo) =>
          context.log.info(s"Starting streaming request $requestId")
          model = request.model
          startedAt = System.currentTimeMillis()
          requestBody = requestTracer.map(_ => request.asJson)

          val streamingRequest =
            RequestBuilder.buildStreamingRequest(request, endpointInfo)(using
              context.system
            )

          context.pipeToSelf(sttpBackend.send(streamingRequest)) {
            case Success(response) if response.code.isSuccess =>
              response.body match {
                case Right(stream) => Command.BackendStreamReady(stream)
                case Left(error) =>
                  Command.StreamFailed(
                    new RuntimeException(s"Failed to get stream: $error")
                  )
              }
            case Success(response) =>
              val errorMsg = response.body match {
                case Left(msg) => msg
                case Right(_)  => "Unknown error"
              }
              Command.StreamFailed(
                new RuntimeException(
                  s"Backend returned error: ${response.code} - $errorMsg"
                )
              )
            case Failure(exception) =>
              Command.StreamFailed(exception)
          }

          Behaviors.same

        case Command.BackendStreamReady(source) =>
          context.log.debug(s"Backend stream ready for request $requestId")

          val chunkStream = source
            .via(SSEParser.flow)
            .via(ChunkParser.flow)

          context.pipeToSelf(
            chunkStream
              .runForeach { chunk =>
                context.self ! Command.ChunkReceived(chunk)
              }
          ) {
            case Success(_)  => Command.StreamCompleted()
            case Failure(ex) => Command.StreamFailed(ex)
          }

          Behaviors.same

        case Command.ChunkReceived(chunk) =>
          chunkListener ! ChunkMessage.Chunk(chunk)
          Behaviors.same

        case Command.StreamCompleted() =>
          context.log.info(s"Stream completed for request $requestId")

          requestTracer.foreach { tracer =>
            val completedAt = System.currentTimeMillis()
            tracer ! RequestTracer.Command.RecordTrace(
              RequestTracer.RequestTrace(
                request_id = requestId,
                backend_id = backendId,
                model = model,
                request_type = "streaming",
                status = "success",
                started_at = startedAt,
                completed_at = completedAt,
                duration_ms = completedAt - startedAt,
                http_status = Some(200),
                request_body = requestBody
              )
            )
          }

          chunkListener ! ChunkMessage.Completed
          dispatcherRef ! CompletionRequestDispatcher.Command.RequestCompleted(
            requestId,
            backendId,
            context.self
          )
          Behaviors.stopped

        case Command.StreamFailed(error) =>
          context.log.error(s"Stream failed for request $requestId", error)

          requestTracer.foreach { tracer =>
            val completedAt = System.currentTimeMillis()
            tracer ! RequestTracer.Command.RecordTrace(
              RequestTracer.RequestTrace(
                request_id = requestId,
                backend_id = backendId,
                model = model,
                request_type = "streaming",
                status = "error",
                error_type = Some("stream_error"),
                started_at = startedAt,
                completed_at = completedAt,
                duration_ms = completedAt - startedAt,
                request_body = requestBody
              )
            )
          }

          chunkListener ! ChunkMessage.Failed(error.getMessage)
          dispatcherRef ! CompletionRequestDispatcher.Command.RequestCompleted(
            requestId,
            backendId,
            context.self
          )
          Behaviors.stopped
      }
    }
}
