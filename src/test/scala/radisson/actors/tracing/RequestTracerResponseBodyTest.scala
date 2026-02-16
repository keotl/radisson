package radisson.actors.tracing

import scala.concurrent.duration._

import io.circe.Json
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import munit.FunSuite

class RequestTracerResponseBodyTest extends FunSuite {
  var testKit: ActorTestKit = null

  override def beforeEach(context: BeforeEach): Unit =
    testKit = ActorTestKit()

  override def afterEach(context: AfterEach): Unit =
    testKit.shutdownTestKit()

  test("trace includes response body when provided") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    val responseBody = Json.obj(
      "id" -> Json.fromString("chatcmpl-123"),
      "model" -> Json.fromString("gpt-4"),
      "choices" -> Json.arr(
        Json.obj(
          "message" -> Json.obj(
            "role" -> Json.fromString("assistant"),
            "content" -> Json.fromString("Hello!")
          )
        )
      )
    )

    val trace = RequestTracer.RequestTrace(
      request_id = "req-1",
      backend_id = "test-backend",
      model = "gpt-4",
      request_type = "completion",
      status = "success",
      started_at = 1000L,
      completed_at = 2000L,
      duration_ms = 1000L,
      response_body = Some(responseBody)
    )

    tracer ! RequestTracer.Command.RecordTrace(trace)

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 1)
    assert(response.traces.head.response_body.isDefined)
    assertEquals(
      response.traces.head.response_body.get.asObject.get("id").get.asString.get,
      "chatcmpl-123"
    )
  }

  test("trace works with None response body") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    val trace = RequestTracer.RequestTrace(
      request_id = "req-1",
      backend_id = "test-backend",
      model = "gpt-4",
      request_type = "streaming",
      status = "success",
      started_at = 1000L,
      completed_at = 2000L,
      duration_ms = 1000L,
      response_body = None
    )

    tracer ! RequestTracer.Command.RecordTrace(trace)

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 1)
    assertEquals(response.traces.head.response_body, None)
  }

  test("trace includes raw request and response bodies") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    val rawRequest = """{"model":"gpt-4","messages":[{"role":"user","content":"Hello"}]}"""
    val rawResponse = """{"id":"chatcmpl-123","object":"chat.completion","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there!"}}]}"""

    val trace = RequestTracer.RequestTrace(
      request_id = "req-1",
      backend_id = "test-backend",
      model = "gpt-4",
      request_type = "completion",
      status = "success",
      started_at = 1000L,
      completed_at = 2000L,
      duration_ms = 1000L,
      raw_request_body = Some(rawRequest),
      raw_response_body = Some(rawResponse)
    )

    tracer ! RequestTracer.Command.RecordTrace(trace)

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 1)
    assertEquals(response.traces.head.raw_request_body, Some(rawRequest))
    assertEquals(response.traces.head.raw_response_body, Some(rawResponse))
  }

  test("trace captures raw response even on deserialization errors") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    val rawRequest = """{"model":"gpt-4","messages":[{"role":"user","content":"Hello"}]}"""
    val rawResponse = """{"id":"chatcmpl-123","choices":[{"message":{"role":"assistant","tool_calls":[{"unexpected_field":"value"}]}}]}"""

    val trace = RequestTracer.RequestTrace(
      request_id = "req-1",
      backend_id = "test-backend",
      model = "gpt-4",
      request_type = "completion",
      status = "error",
      error_type = Some("invalid_response_error"),
      started_at = 1000L,
      completed_at = 2000L,
      duration_ms = 1000L,
      http_status = Some(502),
      raw_request_body = Some(rawRequest),
      raw_response_body = Some(rawResponse)
    )

    tracer ! RequestTracer.Command.RecordTrace(trace)

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 1)
    assertEquals(response.traces.head.status, "error")
    assertEquals(response.traces.head.error_type, Some("invalid_response_error"))
    assertEquals(response.traces.head.raw_request_body, Some(rawRequest))
    assertEquals(response.traces.head.raw_response_body, Some(rawResponse))
  }
}
