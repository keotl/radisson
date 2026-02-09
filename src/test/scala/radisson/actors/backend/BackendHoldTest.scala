package radisson.actors.backend

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import munit.FunSuite
import radisson.config.{AppConfig, BackendConfig, BackendResources, ResourceConfig, ServerConfig}

class BackendHoldTest extends FunSuite {
  var testKit: ActorTestKit = null

  override def beforeEach(context: BeforeEach): Unit = {
    testKit = ActorTestKit()
  }

  override def afterEach(context: AfterEach): Unit = {
    testKit.shutdownTestKit()
  }

  private def createTestConfig(backends: List[BackendConfig]): AppConfig = {
    AppConfig(
      server = ServerConfig("127.0.0.1", 8081, 120),
      backends = backends,
      resources = ResourceConfig("1Gi")
    )
  }

  test("acquire hold on non-existent backend returns NotFound") {
    val config = createTestConfig(List.empty)
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val probe = testKit.createTestProbe[LlamaBackendSupervisor.HoldResponse]()

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
      .awaitAssert(
        {
          supervisor ! LlamaBackendSupervisor.Command.AcquireHold(
            "nonexistent",
            300,
            probe.ref
          )
          probe.expectMessageType[LlamaBackendSupervisor.HoldResponse.NotFound](
            1.second
          )
        },
        1.second
      )
  }

  test("acquire hold on backend without resources returns Failed") {
    val backend = BackendConfig(
      id = "test-backend",
      `type` = "local",
      command = Some("echo test"),
      resources = None
    )
    val config = createTestConfig(List(backend))
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val probe = testKit.createTestProbe[LlamaBackendSupervisor.HoldResponse]()

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
      .awaitAssert(
        {
          supervisor ! LlamaBackendSupervisor.Command.AcquireHold(
            "test-backend",
            300,
            probe.ref
          )
          val response =
            probe.expectMessageType[LlamaBackendSupervisor.HoldResponse.Failed](
              1.second
            )
          assert(
            response.reason.contains("missing resources configuration"),
            s"Expected error about missing resources, got: ${response.reason}"
          )
        },
        1.second
      )
  }

  test("acquire hold on remote backend returns Failed") {
    val backend = BackendConfig(
      id = "remote-backend",
      `type` = "remote",
      endpoint = Some("http://example.com/v1"),
      command = None,
      resources = None
    )
    val config = createTestConfig(List(backend))
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val probe = testKit.createTestProbe[LlamaBackendSupervisor.HoldResponse]()

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
      .awaitAssert(
        {
          supervisor ! LlamaBackendSupervisor.Command.AcquireHold(
            "remote-backend",
            300,
            probe.ref
          )
          val response =
            probe.expectMessageType[LlamaBackendSupervisor.HoldResponse.Failed](
              1.second
            )
          assert(
            response.reason.contains("Cannot hold remote backends"),
            s"Expected error about remote backends, got: ${response.reason}"
          )
        },
        1.second
      )
  }

  test("release hold on non-held backend returns Released (idempotent)") {
    val config = createTestConfig(List.empty)
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)
    val probe = testKit.createTestProbe[LlamaBackendSupervisor.HoldResponse]()

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)
    testKit.createTestProbe[LlamaBackendSupervisor.BackendResponse]()
      .awaitAssert(
        {
          supervisor ! LlamaBackendSupervisor.Command.ReleaseHold(
            "nonexistent",
            probe.ref
          )
          probe.expectMessageType[LlamaBackendSupervisor.HoldResponse.Released](
            1.second
          )
        },
        1.second
      )
  }

  test("clean expired holds removes expired entries") {
    val backend = BackendConfig(
      id = "test-backend",
      `type` = "local-stub",
      command = Some("echo test"),
      resources = Some(BackendResources("100Mi")),
      upstream_url = Some("http://127.0.0.1:9000")
    )
    val config = createTestConfig(List(backend))
    val supervisor = testKit.spawn(LlamaBackendSupervisor.behavior)

    supervisor ! LlamaBackendSupervisor.Command.Initialize(config)

    Thread.sleep(200)

    supervisor ! LlamaBackendSupervisor.Command.CleanExpiredHolds
  }
}
