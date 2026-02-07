package radisson.actors.completion

import munit.FunSuite
import radisson.config.{AppConfig, BackendConfig, ResourceConfig, ServerConfig}

class BackendResolverTest extends FunSuite {

  val testConfig = AppConfig(
    server = ServerConfig("0.0.0.0", 8080, 300),
    backends = List(
      BackendConfig(
        id = "local-chat",
        `type` = "local",
        command = Some("llama-server -m model.gguf --port {port}")
      ),
      BackendConfig(
        id = "remote-chat",
        `type` = "remote",
        endpoint = Some("https://api.openai.com/v1")
      ),
      BackendConfig(
        id = "local-embed",
        `type` = "local-embeddings",
        command = Some("llama-embedding -m model.gguf --port {port}")
      )
    ),
    resources = ResourceConfig("1024Mi")
  )

  test("resolve backend without type filtering") {
    val result = BackendResolver.resolveBackend("local-chat", testConfig)
    assert(result.isRight)
    assertEquals(result.toOption.get.id, "local-chat")
  }

  test("resolve backend with type filtering - local and remote only") {
    val result = BackendResolver.resolveBackend(
      "local-chat",
      testConfig,
      Set("local", "remote")
    )
    assert(result.isRight)
    assertEquals(result.toOption.get.id, "local-chat")
  }

  test("resolve backend with type filtering - local-embeddings only") {
    val result = BackendResolver.resolveBackend(
      "local-embed",
      testConfig,
      Set("local-embeddings")
    )
    assert(result.isRight)
    assertEquals(result.toOption.get.id, "local-embed")
  }

  test("reject embedding model when filtering for chat types") {
    val result = BackendResolver.resolveBackend(
      "local-embed",
      testConfig,
      Set("local", "remote")
    )
    assert(result.isLeft)
    assert(
      result.left.toOption.get.error.message
        .contains("Model 'local-embed' not found")
    )
  }

  test("reject chat model when filtering for embedding types") {
    val result = BackendResolver.resolveBackend(
      "local-chat",
      testConfig,
      Set("local-embeddings")
    )
    assert(result.isLeft)
    assert(
      result.left.toOption.get.error.message
        .contains("Model 'local-chat' not found")
    )
  }

  test("error message lists only filtered backends") {
    val result = BackendResolver.resolveBackend(
      "nonexistent",
      testConfig,
      Set("local", "remote")
    )
    assert(result.isLeft)
    val errorMsg = result.left.toOption.get.error.message
    assert(errorMsg.contains("local-chat"))
    assert(errorMsg.contains("remote-chat"))
    assert(!errorMsg.contains("local-embed"))
  }

  test("error message for embeddings only shows embedding models") {
    val result = BackendResolver.resolveBackend(
      "nonexistent",
      testConfig,
      Set("local-embeddings")
    )
    assert(result.isLeft)
    val errorMsg = result.left.toOption.get.error.message
    assert(errorMsg.contains("local-embed"))
    assert(!errorMsg.contains("local-chat"))
    assert(!errorMsg.contains("remote-chat"))
  }
}
