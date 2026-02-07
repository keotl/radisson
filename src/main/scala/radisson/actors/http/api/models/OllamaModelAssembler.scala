package radisson.actors.http.api.models

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

import radisson.config.BackendConfig

object OllamaModelAssembler {
  def buildOllamaResponse(backends: List[BackendConfig]): OllamaTagsResponse =
    OllamaTagsResponse(
      models = backends.map(backendToOllamaModel)
    )

  def backendToOllamaModel(backend: BackendConfig): OllamaModel = {
    val modelName = s"${backend.id}:latest"
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    OllamaModel(
      name = modelName,
      model = modelName,
      modified_at = timestamp,
      size = 0L,
      digest = generatePlaceholderDigest(backend.id),
      details = OllamaModelDetails(
        parent_model = "",
        format = formatFromType(backend.`type`),
        family = backend.`type`,
        families = List(backend.`type`),
        parameter_size = "unknown",
        quantization_level = "unknown"
      )
    )
  }

  private def formatFromType(backendType: String): String =
    backendType match {
      case "local" => "gguf"
      case "local-embeddings" => "gguf"
      case _       => "unknown"
    }

  private def generatePlaceholderDigest(modelId: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(modelId.getBytes("UTF-8"))
    val hexString = hash.map("%02x".format(_)).mkString
    s"sha256:$hexString"
  }
}
