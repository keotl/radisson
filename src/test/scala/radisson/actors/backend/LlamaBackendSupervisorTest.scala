package radisson.actors.backend

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import munit.FunSuite
import radisson.config.{
  AppConfig,
  BackendConfig,
  BackendResources,
  ResourceConfig,
  ServerConfig
}
import scala.concurrent.duration._

class LlamaBackendSupervisorTest extends FunSuite {

  var testKit: ActorTestKit = null

  override def beforeEach(context: BeforeEach): Unit =
    testKit = ActorTestKit()

  override def afterEach(context: AfterEach): Unit =
    testKit.shutdownTestKit()

  def createTestConfig(
      totalMemory: String,
      backends: List[BackendConfig]
  ): AppConfig =
    AppConfig(
      server = ServerConfig("localhost", 8080, 30),
      backends = backends,
      resources = ResourceConfig(totalMemory)
    )

  test("handle Initialize command") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)

    Thread.sleep(100)
  }

  test("request remote backend returns endpoint immediately") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val remoteBackend = BackendConfig(
      id = "remote-backend",
      `type` = "remote",
      endpoint = Some("http://example.com:8000")
    )
    val config = createTestConfig("1Gi", List(remoteBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "remote-backend",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    response match {
      case LlamaBackendSupervisor.BackendResponse.Available(ep, p) =>
        assertEquals(ep, "http://example.com:8000")
        assertEquals(p, 0)
      case other =>
        fail(s"Expected Available response, got $other")
    }
  }

  test("request non-existent backend returns Failed") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "non-existent",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    response match {
      case LlamaBackendSupervisor.BackendResponse.Failed(r) =>
        assert(
          r.contains("not found"),
          s"Expected 'not found' in reason, got: $r"
        )
      case other =>
        fail(s"Expected Failed response, got $other")
    }
  }

  test("request local backend without resources returns Failed") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val localBackend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("echo test"),
      resources = None
    )
    val config = createTestConfig("1Gi", List(localBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "local-backend",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    response match {
      case LlamaBackendSupervisor.BackendResponse.Failed(r) =>
        assert(
          r.contains("resources"),
          s"Expected 'resources' in reason, got: $r"
        )
      case other =>
        fail(s"Expected Failed response, got $other")
    }
  }

  test("request local backend without command returns Failed") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val localBackend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = None,
      resources = Some(BackendResources("100Mi"))
    )
    val config = createTestConfig("1Gi", List(localBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "local-backend",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    response match {
      case LlamaBackendSupervisor.BackendResponse.Failed(r) =>
        assert(r.contains("command"), s"Expected 'command' in reason, got: $r")
      case other =>
        fail(s"Expected Failed response, got $other")
    }
  }

  test("request local backend with sufficient memory returns Starting") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val localBackend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("echo test"),
      resources = Some(BackendResources("100Mi"))
    )
    val config = createTestConfig("1Gi", List(localBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "local-backend",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    assertEquals(response, LlamaBackendSupervisor.BackendResponse.Starting)
  }

  test("request backend with invalid memory format returns Failed") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val localBackend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("echo test"),
      resources = Some(BackendResources("invalid"))
    )
    val config = createTestConfig("1Gi", List(localBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val responseProbe =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "local-backend",
      responseProbe.ref
    )

    val response = responseProbe.receiveMessage(3.seconds)
    response match {
      case LlamaBackendSupervisor.BackendResponse.Failed(r) =>
        assert(r.contains("memory"), s"Expected 'memory' in reason, got: $r")
      case other =>
        fail(s"Expected Failed response, got $other")
    }
  }

  test("multiple requests for same backend while starting return Starting") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val localBackend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("echo test"),
      resources = Some(BackendResources("100Mi"))
    )
    val config = createTestConfig("1Gi", List(localBackend))

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val probe1 =
      testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()

    supervisor ! LlamaBackendSupervisor.Command.RequestBackend(
      "local-backend",
      probe1.ref
    )

    val response1 = probe1.receiveMessage(3.seconds)
    assertEquals(response1, LlamaBackendSupervisor.BackendResponse.Starting)
  }

  test("handle BackendStarted notification") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    val runnerProbe = testKit.createTestProbe[LlamaBackendRunner.Command]()
    supervisor ! LlamaBackendSupervisor.Command.BackendStarted(
      "test-backend",
      10001,
      runnerProbe.ref
    )

    Thread.sleep(100)
  }

  test("handle BackendFailed notification") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    supervisor ! LlamaBackendSupervisor.Command.BackendFailed(
      "test-backend",
      "test failure"
    )

    Thread.sleep(100)
  }

  test("handle BackendStopped notification") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    supervisor ! LlamaBackendSupervisor.Command.BackendStopped("test-backend")

    Thread.sleep(100)
  }

  test("StopBackend command for non-existent backend is handled gracefully") {
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val config = createTestConfig("1Gi", List.empty)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    Thread.sleep(100)

    supervisor ! LlamaBackendSupervisor.Command.StopBackend("non-existent")

    Thread.sleep(100)
  }
}
