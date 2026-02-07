package radisson.actors.embedding

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import io.circe.parser
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.completion.RequestBuilder
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.actors.http.api.models.{
  EmbeddingRequest,
  EmbeddingResponse,
  ErrorDetail,
  ErrorResponse
}
import radisson.util.Logging
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend

object EmbeddingRequestActor extends Logging {

  enum Command {
    case Execute

    case HttpResponseReceived(result: Try[EmbeddingResponse])
    case HttpRequestFailed(error: Throwable)
  }

  def behavior(
      requestId: String,
      backendId: String,
      request: EmbeddingRequest,
      endpointInfo: EndpointInfo,
      replyTo: ActorRef[EmbeddingRequestDispatcher.EmbeddingCompletionResponse],
      dispatcher: ActorRef[EmbeddingRequestDispatcher.Command]
  ): Behavior[Command] = Behaviors.setup { context =>
    given ec: scala.concurrent.ExecutionContext = context.executionContext
    given sttpBackend: Backend[Future] = HttpClientFutureBackend()

    Behaviors.receiveMessage { case Command.Execute =>
      log.info("Executing embedding request {}", requestId)

      val httpRequest = RequestBuilder.buildEmbeddingRequest(
        request,
        endpointInfo
      )

      val responseFuture =
        httpRequest.send(sttpBackend).flatMap { response =>
          val bodyString = response.body match {
            case Right(body) => body
            case Left(body)  => body
          }

          if response.code.isSuccess then {
            parser.decode[EmbeddingResponse](bodyString) match {
              case Right(embeddingResponse) =>
                Future.successful(embeddingResponse)
              case Left(error) => Future.failed(error)
            }
          } else {
            Future.failed(
              new RuntimeException(
                s"Backend returned error: ${response.code} - $bodyString"
              )
            )
          }
        }

      context.pipeToSelf(responseFuture) {
        case Success(response) =>
          Command.HttpResponseReceived(Success(response))
        case Failure(error) => Command.HttpRequestFailed(error)
      }

      executing(requestId, backendId, replyTo, dispatcher)
    }
  }

  def executing(
      requestId: String,
      backendId: String,
      replyTo: ActorRef[EmbeddingRequestDispatcher.EmbeddingCompletionResponse],
      dispatcher: ActorRef[EmbeddingRequestDispatcher.Command]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case Command.HttpResponseReceived(Success(response)) =>
        replyTo ! EmbeddingRequestDispatcher.EmbeddingCompletionResponse
          .Success(
            response
          )
        dispatcher ! EmbeddingRequestDispatcher.Command.RequestCompleted(
          requestId,
          backendId,
          context.self
        )
        Behaviors.stopped

      case Command.HttpRequestFailed(error) =>
        val (errorResponse, statusCode) = error match {
          case _: java.util.concurrent.TimeoutException =>
            (
              ErrorResponse(
                ErrorDetail("Request to backend timed out", "timeout_error")
              ),
              504
            )

          case _: io.circe.DecodingFailure =>
            (
              ErrorResponse(
                ErrorDetail(
                  "Backend returned invalid response format",
                  "invalid_response_error"
                )
              ),
              502
            )

          case other =>
            log.error("HTTP request failed", other)
            (
              ErrorResponse(
                ErrorDetail(
                  s"Failed to communicate with backend: ${other.getMessage}",
                  "service_error"
                )
              ),
              502
            )
        }

        replyTo ! EmbeddingRequestDispatcher.EmbeddingCompletionResponse.Error(
          errorResponse,
          statusCode
        )
        dispatcher ! EmbeddingRequestDispatcher.Command.RequestCompleted(
          requestId,
          backendId,
          context.self
        )
        Behaviors.stopped

      case _ =>
        log.warn("Unexpected message in executing state")
        Behaviors.same
    }
  }
}
