package radisson.config

import munit.FunSuite

class LocalStubBackendTest extends FunSuite {

  test("local-stub backend with command validates successfully") {
    val backend = BackendConfig(
      id = "stub-test",
      `type` = "local-stub",
      command = Some("python3 mock_server.py"),
      resources = Some(BackendResources("100Mi")),
      upstream_url = Some("http://127.0.0.1:9000")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isRight, s"Expected validation to succeed but got: $result")
  }

  test("local-stub backend without command fails validation") {
    val backend = BackendConfig(
      id = "stub-test",
      `type` = "local-stub",
      command = None,
      resources = Some(BackendResources("100Mi")),
      upstream_url = Some("http://127.0.0.1:9000")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft, "Expected validation to fail for missing command")
    assert(
      result.left.exists(_.contains("must have 'command'")),
      s"Expected error about missing command, got: $result"
    )
  }

  test("local-stub backend with resources validates successfully") {
    val backend = BackendConfig(
      id = "stub-test",
      `type` = "local-stub",
      command = Some("python3 mock_server.py"),
      resources = Some(BackendResources("200Mi")),
      upstream_url = None
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isRight, s"Expected validation to succeed but got: $result")
  }

  test("local-stub backend with invalid upstream_url fails validation") {
    val backend = BackendConfig(
      id = "stub-test",
      `type` = "local-stub",
      command = Some("python3 mock_server.py"),
      resources = Some(BackendResources("100Mi")),
      upstream_url = Some("invalid-url")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(
      result.isLeft,
      "Expected validation to fail for invalid upstream_url"
    )
    assert(
      result.left.exists(_.contains("must start with http://")),
      s"Expected error about invalid upstream_url, got: $result"
    )
  }
}
