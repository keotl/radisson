package radisson.actors.completion

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import io.circe.syntax._
import io.circe.{Json, parser}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.actors.http.api.models.{
  ChatCompletionRequest,
  ChatCompletionResponse,
  ErrorDetail,
  ErrorResponse
}
import radisson.actors.tracing.RequestTracer
import radisson.util.{FieldDropDetector, Logging}
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend

object CompletionRequestActor extends Logging {

  enum Command {
    case Execute

    case HttpResponseReceived(
        result: Try[ChatCompletionResponse],
        rawResponse: Option[String] = None
    )
    case HttpRequestFailed(error: Throwable, rawResponse: Option[String] = None)
  }

  def behavior(
      requestId: String,
      backendId: String,
      request: ChatCompletionRequest,
      endpointInfo: EndpointInfo,
      replyTo: ActorRef[CompletionRequestDispatcher.CompletionResponse],
      dispatcher: ActorRef[CompletionRequestDispatcher.Command],
      requestTracer: Option[ActorRef[RequestTracer.Command]] = None
  ): Behavior[Command] = Behaviors.setup { context =>
    given ec: scala.concurrent.ExecutionContext = context.executionContext
    given sttpBackend: Backend[Future] = HttpClientFutureBackend()

    Behaviors.receiveMessage { case Command.Execute =>
      log.info("Executing completion request {}", requestId)

      val startedAt = System.currentTimeMillis()

      val httpRequest = RequestBuilder.buildRequest(
        request,
        endpointInfo
      )

      val rawRequestBody = requestTracer.map(_ => request.asJson.noSpaces)

      val responseFuture =
        httpRequest.send(sttpBackend).map { response =>
          val bodyString = response.body match {
            case Right(body) => body
            case Left(body)  => body
          }

          val parsed = if response.code.isSuccess then {
            val result = parser.decode[ChatCompletionResponse](bodyString).toTry
            result.foreach { decoded =>
              parser.parse(bodyString).foreach { originalJson =>
                FieldDropDetector.warnOnDroppedFields(
                  "ChatCompletionResponse",
                  originalJson,
                  decoded
                )
              }
            }
            result
          } else {
            Failure(
              new RuntimeException(
                s"Backend returned error: ${response.code} - $bodyString"
              )
            )
          }

          (parsed, bodyString)
        }

      context.pipeToSelf(responseFuture) {
        case Success((tryResponse, rawResponse)) =>
          tryResponse match {
            case Success(response) =>
              Command.HttpResponseReceived(Success(response), Some(rawResponse))
            case Failure(error) =>
              Command.HttpRequestFailed(error, Some(rawResponse))
          }
        case Failure(error) =>
          Command.HttpRequestFailed(error, None)
      }

      val requestBody = requestTracer.map(_ => request.asJson)
      executing(
        requestId,
        backendId,
        request.model,
        startedAt,
        requestBody,
        rawRequestBody,
        replyTo,
        dispatcher,
        requestTracer
      )
    }
  }

  def executing(
      requestId: String,
      backendId: String,
      model: String,
      startedAt: Long,
      requestBody: Option[Json],
      rawRequestBody: Option[String],
      replyTo: ActorRef[CompletionRequestDispatcher.CompletionResponse],
      dispatcher: ActorRef[CompletionRequestDispatcher.Command],
      requestTracer: Option[ActorRef[RequestTracer.Command]]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case Command.HttpResponseReceived(Success(response), rawResponse) =>
        requestTracer.foreach { tracer =>
          val completedAt = System.currentTimeMillis()
          tracer ! RequestTracer.Command.RecordTrace(
            RequestTracer.RequestTrace(
              request_id = requestId,
              backend_id = backendId,
              model = model,
              request_type = "completion",
              status = "success",
              started_at = startedAt,
              completed_at = completedAt,
              duration_ms = completedAt - startedAt,
              prompt_tokens = Some(response.usage.prompt_tokens),
              completion_tokens = Some(response.usage.completion_tokens),
              total_tokens = Some(response.usage.total_tokens),
              http_status = Some(200),
              request_body = requestBody,
              response_body = Some(response.asJson),
              raw_request_body = rawRequestBody,
              raw_response_body = rawResponse
            )
          )
        }

        replyTo ! CompletionRequestDispatcher.CompletionResponse.Success(
          response
        )
        dispatcher ! CompletionRequestDispatcher.Command.RequestCompleted(
          requestId,
          backendId,
          context.self
        )
        Behaviors.stopped

      case Command.HttpRequestFailed(error, rawResponse) =>
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

        val errorType = error match {
          case _: java.util.concurrent.TimeoutException => "timeout_error"
          case _: io.circe.DecodingFailure => "invalid_response_error"
          case _                           => "service_error"
        }

        requestTracer.foreach { tracer =>
          val completedAt = System.currentTimeMillis()
          tracer ! RequestTracer.Command.RecordTrace(
            RequestTracer.RequestTrace(
              request_id = requestId,
              backend_id = backendId,
              model = model,
              request_type = "completion",
              status = "error",
              error_type = Some(errorType),
              started_at = startedAt,
              completed_at = completedAt,
              duration_ms = completedAt - startedAt,
              http_status = Some(statusCode),
              request_body = requestBody,
              raw_request_body = rawRequestBody,
              raw_response_body = rawResponse
            )
          )
        }

        replyTo ! CompletionRequestDispatcher.CompletionResponse.Error(
          errorResponse,
          statusCode
        )
        dispatcher ! CompletionRequestDispatcher.Command.RequestCompleted(
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
