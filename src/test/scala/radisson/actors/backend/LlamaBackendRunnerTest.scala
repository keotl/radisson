package radisson.actors.backend

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
}
