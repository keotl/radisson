package radisson.actors.tracing

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import munit.FunSuite

class RequestTracerTest extends FunSuite {
  var testKit: ActorTestKit = null

  override def beforeEach(context: BeforeEach): Unit =
    testKit = ActorTestKit()

  override def afterEach(context: AfterEach): Unit =
    testKit.shutdownTestKit()

  private def makeTrace(
      requestId: String,
      startedAt: Long = 1000L,
      completedAt: Long = 2000L
  ): RequestTracer.RequestTrace =
    RequestTracer.RequestTrace(
      request_id = requestId,
      backend_id = "test-backend",
      model = "test-model",
      request_type = "completion",
      status = "success",
      started_at = startedAt,
      completed_at = completedAt,
      duration_ms = completedAt - startedAt
    )

  test("empty initial state returns no traces") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces, List.empty)
    assertEquals(response.total_captured, 0)
  }

  test("records traces in newest-first order") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    tracer ! RequestTracer.Command.RecordTrace(makeTrace("req-1"))
    tracer ! RequestTracer.Command.RecordTrace(makeTrace("req-2"))
    tracer ! RequestTracer.Command.RecordTrace(makeTrace("req-3"))

    tracer ! RequestTracer.Command.GetTraces(10, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 3)
    assertEquals(
      response.traces.map(_.request_id),
      List("req-3", "req-2", "req-1")
    )
    assertEquals(response.total_captured, 3)
  }

  test("limit parameter restricts returned traces") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    for (i <- 1 to 10)
      tracer ! RequestTracer.Command.RecordTrace(makeTrace(s"req-$i"))

    tracer ! RequestTracer.Command.GetTraces(3, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, 3)
    assertEquals(response.total_captured, 10)
    assertEquals(response.traces.head.request_id, "req-10")
  }

  test("caps stored traces at MaxTraces") {
    val tracer = testKit.spawn(RequestTracer.behavior)
    val probe = testKit.createTestProbe[RequestTracer.TracesResponse]()

    for (i <- 1 to 60)
      tracer ! RequestTracer.Command.RecordTrace(makeTrace(s"req-$i"))

    tracer ! RequestTracer.Command.GetTraces(100, probe.ref)
    val response = probe.receiveMessage(1.second)

    assertEquals(response.traces.size, RequestTracer.MaxTraces)
    assertEquals(response.total_captured, 60)
    assertEquals(response.traces.head.request_id, "req-60")
    assertEquals(response.traces.last.request_id, "req-11")
  }
}
