package radisson.actors.backend

import java.net.InetSocketAddress

import scala.concurrent.duration._

import com.sun.net.httpserver.HttpServer
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import munit.FunSuite

class LlamaBackendRunnerTest extends FunSuite {

  var testKit: ActorTestKit = null

  override def beforeEach(context: BeforeEach): Unit =
    testKit = ActorTestKit()

  override def afterEach(context: AfterEach): Unit =
    testKit.shutdownTestKit()

  test("respond with Idle status initially") {
    val runner = testKit.spawn(LlamaBackendRunner.behavior)
    val probe = testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()

    runner ! LlamaBackendRunner.Command.GetStatus(probe.ref)

    val response = probe.receiveMessage()
    assertEquals(response, LlamaBackendRunner.StatusResponse.Idle)
  }

  test("transition to Starting state when Start command received") {
    val runner = testKit.spawn(LlamaBackendRunner.behavior)
    val supervisorProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.Command]()
    val statusProbe =
      testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()

    runner ! LlamaBackendRunner.Command.Start(
      "test-backend",
      "sleep 60",
      10001,
      supervisorProbe.ref
    )

    Thread.sleep(100)

    runner ! LlamaBackendRunner.Command.GetStatus(statusProbe.ref)
    val response = statusProbe.receiveMessage()

    response match {
      case LlamaBackendRunner.StatusResponse.Idle =>
        fail("Should not be in Idle state after Start command")
      case _ =>
    }
  }

  test("handle Stop command in idle state") {
    val runner = testKit.spawn(LlamaBackendRunner.behavior)
    val statusProbe =
      testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()

    runner ! LlamaBackendRunner.Command.Stop

    Thread.sleep(100)

    runner ! LlamaBackendRunner.Command.GetStatus(statusProbe.ref)
    val response = statusProbe.receiveMessage()
    assert(
      response == LlamaBackendRunner.StatusResponse.Idle ||
        response == LlamaBackendRunner.StatusResponse.Stopped,
      "Response should be Idle or Stopped"
    )
  }

  test("ignore unexpected messages in idle state") {
    val runner = testKit.spawn(LlamaBackendRunner.behavior)
    val statusProbe =
      testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()

    runner ! LlamaBackendRunner.Command.ProcessExited(0)

    runner ! LlamaBackendRunner.Command.GetStatus(statusProbe.ref)
    val response = statusProbe.receiveMessage()
    assertEquals(response, LlamaBackendRunner.StatusResponse.Idle)
  }

  test("multiple status queries return consistent results") {
    val runner = testKit.spawn(LlamaBackendRunner.behavior)
    val probe1 = testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()
    val probe2 = testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()

    runner ! LlamaBackendRunner.Command.GetStatus(probe1.ref)
    runner ! LlamaBackendRunner.Command.GetStatus(probe2.ref)

    val response1 = probe1.receiveMessage()
    val response2 = probe2.receiveMessage()

    assertEquals(response1, response2)
    assertEquals(response1, LlamaBackendRunner.StatusResponse.Idle)
  }
  private def startHealthServer(): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/health",
      exchange => {
        val body = "OK".getBytes
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
      }
    )
    server.start()
    server
  }

  private val fastLiveness = LlamaBackendRunner.LivenessSettings(
    interval = 200.millis,
    retryDelay = 100.millis,
    failureThreshold = 3
  )

  private def startRunnerAgainst(
      server: HttpServer,
      liveness: LlamaBackendRunner.LivenessSettings,
      supervisorProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[
        LlamaBackendSupervisor.Command
      ]
  ) = {
    val runner = testKit.spawn(LlamaBackendRunner.behaviorWith(liveness))
    runner ! LlamaBackendRunner.Command.Start(
      "test-backend",
      "sleep 60",
      server.getAddress.getPort,
      supervisorProbe.ref,
      upstreamUrl = Some(s"http://127.0.0.1:${server.getAddress.getPort}")
    )
    supervisorProbe.expectMessageType[
      LlamaBackendSupervisor.Command.BackendStarted
    ](10.seconds)
    runner
  }

  test("report BackendStopped when a running backend stops answering health checks") {
    val server = startHealthServer()
    val supervisorProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.Command]()
    val runner = startRunnerAgainst(server, fastLiveness, supervisorProbe)

    // The wrapper process (`sleep`) stays alive; only the server goes away.
    server.stop(0)

    supervisorProbe.expectMessage(
      10.seconds,
      LlamaBackendSupervisor.Command.BackendStopped("test-backend")
    )

    val statusProbe =
      testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()
    runner ! LlamaBackendRunner.Command.GetStatus(statusProbe.ref)
    assertEquals(
      statusProbe.receiveMessage(),
      LlamaBackendRunner.StatusResponse.Stopped
    )
  }

  test("CheckHealthNow detects a dead backend without waiting for the interval") {
    val server = startHealthServer()
    val supervisorProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.Command]()
    val runner = startRunnerAgainst(
      server,
      fastLiveness.copy(interval = 1.hour),
      supervisorProbe
    )

    server.stop(0)
    runner ! LlamaBackendRunner.Command.CheckHealthNow

    supervisorProbe.expectMessage(
      10.seconds,
      LlamaBackendSupervisor.Command.BackendStopped("test-backend")
    )
  }

  test("keep running while health checks succeed") {
    val server = startHealthServer()
    try {
      val supervisorProbe =
        testKit.createTestProbe[LlamaBackendSupervisor.Command]()
      val runner = startRunnerAgainst(server, fastLiveness, supervisorProbe)

      supervisorProbe.expectNoMessage(1.second)

      val statusProbe =
        testKit.createTestProbe[LlamaBackendRunner.StatusResponse]()
      runner ! LlamaBackendRunner.Command.GetStatus(statusProbe.ref)
      assertEquals(
        statusProbe.receiveMessage(),
        LlamaBackendRunner.StatusResponse.Running(server.getAddress.getPort)
      )
      runner ! LlamaBackendRunner.Command.Stop
    } finally {
      server.stop(0)
    }
  }
}
