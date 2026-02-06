package radisson.actors.completion

import scala.concurrent.Future
import scala.util.{Failure, Success}

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.actors.http.api.models.{
  ChatCompletionChunk,
  ChatCompletionRequest
}
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
      backendId: String
  ): Behavior[Command] =
    Behaviors.setup { context =>
      given system: org.apache.pekko.actor.typed.ActorSystem[?] =
        context.system
      given sttpBackend: WebSocketStreamBackend[
        Future,
        sttp.capabilities.pekko.PekkoStreams
      ] = PekkoHttpBackend.usingActorSystem(context.system.classicSystem)

      Behaviors.receiveMessage {
        case Command.Execute(request, endpointInfo) =>
          context.log.info(s"Starting streaming request $requestId")

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
          chunkListener ! ChunkMessage.Completed
          dispatcherRef ! CompletionRequestDispatcher.Command.RequestCompleted(
            requestId,
            backendId,
            context.self
          )
          Behaviors.stopped

        case Command.StreamFailed(error) =>
          context.log.error(s"Stream failed for request $requestId", error)
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
