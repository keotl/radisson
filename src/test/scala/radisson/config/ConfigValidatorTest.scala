package radisson.config

import munit.FunSuite

class ConfigValidatorTest extends FunSuite {

  test("validate local backend with command only") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port {port}"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("validate local backend with command and http upstream_url") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port 8080"),
      upstream_url = Some("http://172.16.0.2:8080"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("validate local backend with command and https upstream_url") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port 8080"),
      upstream_url = Some("https://example.com:8080"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("validate local backend with command and upstream_url with path") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port 8080"),
      upstream_url = Some("http://example.com:8080/v1"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("reject local backend without command") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = None,
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("must have 'command'"))
  }

  test("reject local backend with invalid upstream_url (no protocol)") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port 8080"),
      upstream_url = Some("172.16.0.2:8080"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(
      result.left.toOption.get.contains("must start with http:// or https://")
    )
  }

  test("reject local backend with invalid upstream_url (ftp protocol)") {
    val backend = BackendConfig(
      id = "local-backend",
      `type` = "local",
      command = Some("llama-server -m model.gguf --port 8080"),
      upstream_url = Some("ftp://172.16.0.2:8080"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(
      result.left.toOption.get.contains("must start with http:// or https://")
    )
  }

  test("validate remote backend with endpoint") {
    val backend = BackendConfig(
      id = "remote-backend",
      `type` = "remote",
      endpoint = Some("https://api.openai.com/v1"),
      api_key = Some("sk-test")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("reject remote backend without endpoint") {
    val backend = BackendConfig(
      id = "remote-backend",
      `type` = "remote",
      endpoint = None,
      api_key = Some("sk-test")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("must have 'endpoint'"))
  }

  test("reject remote backend with upstream_url") {
    val backend = BackendConfig(
      id = "remote-backend",
      `type` = "remote",
      endpoint = Some("https://api.openai.com/v1"),
      upstream_url = Some("http://localhost:8080"),
      api_key = Some("sk-test")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("should not have 'upstream_url'"))
  }

  test("validate local-embeddings backend with command") {
    val backend = BackendConfig(
      id = "embeddings-backend",
      `type` = "local-embeddings",
      command = Some("llama-embedding -m model.gguf --port {port}"),
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assertEquals(result, Right(()))
  }

  test("reject local-embeddings backend without command") {
    val backend = BackendConfig(
      id = "embeddings-backend",
      `type` = "local-embeddings",
      command = None,
      resources = Some(BackendResources("100Mi"))
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("must have 'command'"))
  }

  test("reject backend with unknown type") {
    val backend = BackendConfig(
      id = "unknown-backend",
      `type` = "unknown",
      command = Some("test")
    )

    val result = ConfigValidator.validateBackendConfig(backend)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("Unknown type 'unknown'"))
  }

  test("validate config with multiple valid backends") {
    val config = AppConfig(
      server = ServerConfig("0.0.0.0", 8080, 300),
      backends = List(
        BackendConfig(
          id = "local-1",
          `type` = "local",
          command = Some("llama-server -m model1.gguf --port {port}"),
          resources = Some(BackendResources("100Mi"))
        ),
        BackendConfig(
          id = "local-2",
          `type` = "local",
          command = Some("llama-server -m model2.gguf --port 8080"),
          upstream_url = Some("http://localhost:8080"),
          resources = Some(BackendResources("200Mi"))
        ),
        BackendConfig(
          id = "remote-1",
          `type` = "remote",
          endpoint = Some("https://api.openai.com/v1"),
          api_key = Some("sk-test")
        )
      ),
      resources = ResourceConfig("1024Mi")
    )

    val result = ConfigValidator.validateConfig(config)
    assertEquals(result, Right(()))
  }

  test("reject config with one invalid backend") {
    val config = AppConfig(
      server = ServerConfig("0.0.0.0", 8080, 300),
      backends = List(
        BackendConfig(
          id = "local-1",
          `type` = "local",
          command = Some("llama-server -m model1.gguf --port {port}"),
          resources = Some(BackendResources("100Mi"))
        ),
        BackendConfig(
          id = "local-2",
          `type` = "local",
          command = None,
          resources = Some(BackendResources("200Mi"))
        )
      ),
      resources = ResourceConfig("1024Mi")
    )

    val result = ConfigValidator.validateConfig(config)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("local-2"))
    assert(result.left.toOption.get.contains("must have 'command'"))
  }

  test("validate config with empty backends list") {
    val config = AppConfig(
      server = ServerConfig("0.0.0.0", 8080, 300),
      backends = List.empty,
      resources = ResourceConfig("1024Mi")
    )

    val result = ConfigValidator.validateConfig(config)
    assertEquals(result, Right(()))
  }

  test("reject config with first backend invalid") {
    val config = AppConfig(
      server = ServerConfig("0.0.0.0", 8080, 300),
      backends = List(
        BackendConfig(
          id = "invalid-backend",
          `type` = "remote",
          endpoint = None
        ),
        BackendConfig(
          id = "valid-backend",
          `type` = "local",
          command = Some("test"),
          resources = Some(BackendResources("100Mi"))
        )
      ),
      resources = ResourceConfig("1024Mi")
    )

    val result = ConfigValidator.validateConfig(config)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("invalid-backend"))
  }

  test("reject config with last backend invalid") {
    val config = AppConfig(
      server = ServerConfig("0.0.0.0", 8080, 300),
      backends = List(
        BackendConfig(
          id = "valid-backend",
          `type` = "local",
          command = Some("test"),
          resources = Some(BackendResources("100Mi"))
        ),
        BackendConfig(
          id = "invalid-backend",
          `type` = "remote",
          endpoint = None
        )
      ),
      resources = ResourceConfig("1024Mi")
    )

    val result = ConfigValidator.validateConfig(config)
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("invalid-backend"))
  }
}
