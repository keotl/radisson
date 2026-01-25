package radisson.actors.completion

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.http.api.models.{
  ChatCompletionRequest,
  ChatCompletionResponse,
  ErrorResponse,
  ErrorDetail
}
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.util.Logging
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import io.circe.syntax._
import io.circe.parser

object CompletionRequestActor extends Logging {

  enum Command {
    case Execute

    case HttpResponseReceived(result: Try[ChatCompletionResponse])
    case HttpRequestFailed(error: Throwable)
  }

  def behavior(
      requestId: String,
      request: ChatCompletionRequest,
      endpointInfo: EndpointInfo,
      replyTo: ActorRef[CompletionRequestDispatcher.CompletionResponse],
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  ): Behavior[Command] = Behaviors.setup { context =>
    given ec: scala.concurrent.ExecutionContext = context.executionContext
    given sttpBackend: Backend[Future] = HttpClientFutureBackend()

    Behaviors.receiveMessage {
      case Command.Execute =>
        log.info("Executing completion request {}", requestId)

        val httpRequest = RequestBuilder.buildRequest(
          request,
          endpointInfo
        )

        val requestWithBody = httpRequest.body(request.asJson.noSpaces)

        val responseFuture =
          requestWithBody.send(sttpBackend).flatMap { response =>
            val bodyString = response.body match {
              case Right(body) => body
              case Left(body)  => body
            }

            if response.code.isSuccess then {
              parser.decode[ChatCompletionResponse](bodyString) match {
                case Right(completionResponse) =>
                  Future.successful(completionResponse)
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

        executing(requestId, replyTo, dispatcher)
      }
  }

  def executing(
      requestId: String,
      replyTo: ActorRef[CompletionRequestDispatcher.CompletionResponse],
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case Command.HttpResponseReceived(Success(response)) =>
        replyTo ! CompletionRequestDispatcher.CompletionResponse.Success(
          response
        )
        dispatcher ! CompletionRequestDispatcher.Command.RequestCompleted(
          requestId,
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

        replyTo ! CompletionRequestDispatcher.CompletionResponse.Error(
          errorResponse,
          statusCode
        )
        dispatcher ! CompletionRequestDispatcher.Command.RequestCompleted(
          requestId,
          context.self
        )
        Behaviors.stopped

      case _ =>
        log.warn("Unexpected message in executing state")
        Behaviors.same
    }
  }
}
