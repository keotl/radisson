package radisson.actors.completion

import scala.concurrent.Future
import scala.util.{Failure, Success}

import io.circe.syntax._
import io.circe.{Json, parser}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.{ContentType, ContentTypes}
import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Keep,
  Sink,
  Source
}
import org.apache.pekko.util.ByteString
import radisson.actors.completion.RequestBuilder.EndpointInfo
import radisson.actors.http.api.models.{
  ChatCompletionRequest,
  ChatCompletionResponse,
  ErrorDetail,
  ErrorResponse
}
import radisson.actors.tracing.RequestTracer
import radisson.util.{FieldDropDetector, Logging}
import sttp.capabilities.pekko.PekkoStreams
import sttp.client4.WebSocketStreamBackend
import sttp.client4.pekkohttp.PekkoHttpBackend

object CompletionRequestActor extends Logging {

  enum Command {
    case Execute

    // Upstream response headers received; the body is still a lazy, unconsumed
    // stream (Right) for success responses, or an already-read error string
    // (Left) for non-2xx responses.
    case ResponseHeaders(
        statusCode: Int,
        isSuccess: Boolean,
        contentType: Option[String],
        contentLength: Option[Long],
        body: Either[String, Source[ByteString, Any]]
    )
    // Fired once the passthrough body stream terminates (materialized by the
    // route). BodyTerminated carries the bytes that reached us — the full body
    // on normal completion, or a truncated prefix if the client disconnected
    // mid-body (the eagerCancel tee completes normally in both cases, so we
    // tell them apart by comparing the byte count to the upstream
    // Content-Length). BodyFailed means the upstream stream itself errored.
    case BodyTerminated(bytes: ByteString)
    case BodyFailed(cause: Throwable)

    case HttpRequestFailed(error: Throwable)
  }

  // Passes bytes straight through while teeing a copy into `accSink` for
  // tracing. eagerCancel = true so a client disconnect on the main outlet
  // cancels the upstream body source (and the tee), which closes the upstream
  // connection and stops the backend.
  private def teeFlow(
      accSink: Sink[ByteString, Future[ByteString]]
  ): Flow[ByteString, ByteString, Future[ByteString]] =
    Flow.fromGraph(GraphDSL.createGraph(accSink) { implicit b => acc =>
      import GraphDSL.Implicits._
      val bcast = b.add(Broadcast[ByteString](2, eagerCancel = true))
      bcast.out(1) ~> acc
      FlowShape(bcast.in, bcast.out(0))
    })

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
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = context.system
    given sttpBackend: WebSocketStreamBackend[Future, PekkoStreams] =
      PekkoHttpBackend.usingActorSystem(context.system.classicSystem)

    Behaviors.receiveMessage { case Command.Execute =>
      log.info("Executing completion request {}", requestId)

      val startedAt = System.currentTimeMillis()

      // Reuse the streaming request builder: it sends the request body as-is
      // (stream=false for this path) but reads the response via asStreamUnsafe,
      // giving us a cancellable body stream we can pass straight through.
      val httpRequest = RequestBuilder.buildStreamingRequest(
        request,
        endpointInfo
      )

      val requestBody = requestTracer.map(_ => request.asJson)
      val rawRequestBody = requestTracer.map(_ => request.asJson.noSpaces)

      context.pipeToSelf(httpRequest.send(sttpBackend)) {
        case Success(response) =>
          Command.ResponseHeaders(
            response.code.code,
            response.code.isSuccess,
            response.contentType,
            response.contentLength,
            response.body
          )
        case Failure(error) =>
          Command.HttpRequestFailed(error)
      }

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

  private def executing(
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
    given ec: scala.concurrent.ExecutionContext = context.executionContext
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = context.system

    def recordErrorTrace(errorType: String, statusCode: Int): Unit =
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
            raw_request_body = rawRequestBody
          )
        )
      }

    def fail(errorType: String, errorResponse: ErrorResponse, statusCode: Int)
        : Behavior[Command] = {
      recordErrorTrace(errorType, statusCode)
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
    }

    message match {
      case Command.ResponseHeaders(
            statusCode,
            true,
            contentTypeStr,
            contentLength,
            Right(source)
          ) =>
        val contentType: ContentType = contentTypeStr
          .flatMap(s => ContentType.parse(s).toOption)
          .getOrElse(ContentTypes.`application/json`)

        // Tee the passthrough for tracing and fire a single termination
        // self-message once the route materializes and runs the stream. The
        // accumulator future succeeds with the full body on normal completion,
        // and fails when the eagerCancel broadcast tears the tee down because
        // the client disconnected mid-body — which is how we tell the two
        // apart (watchTermination reports success for a downstream cancel).
        val accSink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
        val finalBody: Source[ByteString, Any] = source
          .viaMat(teeFlow(accSink))(Keep.right)
          .mapMaterializedValue { accF =>
            accF.onComplete {
              case Success(bytes) =>
                context.self ! Command.BodyTerminated(bytes)
              case Failure(cause) =>
                context.self ! Command.BodyFailed(cause)
            }
            NotUsed
          }

        replyTo ! CompletionRequestDispatcher.CompletionResponse.StreamingSuccess(
          statusCode,
          contentType,
          contentLength,
          finalBody
        )

        awaitingBody(
          requestId,
          backendId,
          model,
          startedAt,
          contentLength,
          requestBody,
          rawRequestBody,
          dispatcher,
          requestTracer
        )

      case Command.ResponseHeaders(_, true, _, _, Left(message)) =>
        // Success status but no stream body — unexpected; treat as a backend
        // protocol error.
        log.error("Backend success response had no stream body: {}", message)
        fail(
          "service_error",
          ErrorResponse(
            ErrorDetail(
              s"Failed to communicate with backend: $message",
              "service_error"
            )
          ),
          502
        )

      case Command.ResponseHeaders(statusCode, false, _, _, body) =>
        val bodyMsg = body match {
          case Left(msg) => msg
          case Right(stream) =>
            // Shouldn't happen, but drain to avoid leaking the connection.
            stream.runWith(Sink.ignore)
            "Unknown error"
        }
        log.error("Backend returned error: {} - {}", statusCode, bodyMsg)
        fail(
          "service_error",
          ErrorResponse(
            ErrorDetail(
              s"Failed to communicate with backend: Backend returned error: $statusCode - $bodyMsg",
              "service_error"
            )
          ),
          502
        )

      case Command.HttpRequestFailed(error) =>
        error match {
          case _: java.util.concurrent.TimeoutException =>
            fail(
              "timeout_error",
              ErrorResponse(
                ErrorDetail("Request to backend timed out", "timeout_error")
              ),
              504
            )
          case other =>
            log.error("HTTP request failed", other)
            fail(
              "service_error",
              ErrorResponse(
                ErrorDetail(
                  s"Failed to communicate with backend: ${other.getMessage}",
                  "service_error"
                )
              ),
              502
            )
        }

      case _ =>
        log.warn("Unexpected message in executing state")
        Behaviors.same
    }
  }

  private def awaitingBody(
      requestId: String,
      backendId: String,
      model: String,
      startedAt: Long,
      contentLength: Option[Long],
      requestBody: Option[Json],
      rawRequestBody: Option[String],
      dispatcher: ActorRef[CompletionRequestDispatcher.Command],
      requestTracer: Option[ActorRef[RequestTracer.Command]]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    def complete(): Behavior[Command] = {
      dispatcher ! CompletionRequestDispatcher.Command.RequestCompleted(
        requestId,
        backendId,
        context.self
      )
      Behaviors.stopped
    }

    def recordCancelled(): Unit =
      requestTracer.foreach { tracer =>
        val completedAt = System.currentTimeMillis()
        tracer ! RequestTracer.Command.RecordTrace(
          RequestTracer.RequestTrace(
            request_id = requestId,
            backend_id = backendId,
            model = model,
            request_type = "completion",
            status = "cancelled",
            error_type = Some("client_disconnect"),
            started_at = startedAt,
            completed_at = completedAt,
            duration_ms = completedAt - startedAt,
            request_body = requestBody,
            raw_request_body = rawRequestBody
          )
        )
      }

    message match {
      case Command.BodyTerminated(bytes) =>
        val bodyString = bytes.utf8String
        val decoded = parser.decode[ChatCompletionResponse](bodyString)

        // The eagerCancel tee completes normally whether the client read the
        // whole body or disconnected mid-stream, so distinguish by byte count
        // (against the upstream Content-Length) and fall back to a successful
        // parse when the length is unknown (e.g. chunked upstream).
        val complete0 = contentLength match {
          case Some(len) => bytes.length.toLong >= len
          case None      => decoded.isRight
        }

        if !complete0 then {
          context.log.info(
            "Completion request {} cancelled (client disconnected)",
            requestId
          )
          recordCancelled()
        } else {
          decoded.foreach { d =>
            parser.parse(bodyString).foreach { originalJson =>
              FieldDropDetector.warnOnDroppedFields(
                "ChatCompletionResponse",
                originalJson,
                d
              )
            }
          }
          requestTracer.foreach { tracer =>
            val completedAt = System.currentTimeMillis()
            val base = RequestTracer.RequestTrace(
              request_id = requestId,
              backend_id = backendId,
              model = model,
              request_type = "completion",
              status = "success",
              started_at = startedAt,
              completed_at = completedAt,
              duration_ms = completedAt - startedAt,
              http_status = Some(200),
              request_body = requestBody,
              raw_request_body = rawRequestBody,
              raw_response_body = Some(bodyString)
            )
            val trace = decoded match {
              case Right(response) =>
                base.copy(
                  prompt_tokens = Some(response.usage.prompt_tokens),
                  completion_tokens = Some(response.usage.completion_tokens),
                  total_tokens = Some(response.usage.total_tokens),
                  response_body = Some(response.asJson)
                )
              case Left(err) =>
                context.log.warn(
                  "Could not parse backend response for tracing: {}",
                  err.getMessage
                )
                base
            }
            tracer ! RequestTracer.Command.RecordTrace(trace)
          }
        }
        complete()

      case Command.BodyFailed(cause) =>
        context.log.warn(
          "Completion request {} body stream failed: {}",
          requestId,
          cause.getMessage
        )
        recordCancelled()
        complete()

      case _ =>
        context.log.warn("Unexpected message in awaitingBody state")
        Behaviors.same
    }
  }
}
