package radisson.actors.completion

import radisson.actors.http.api.models.{ErrorDetail, ErrorResponse}
import radisson.config.{AppConfig, BackendConfig}

object BackendResolver {

  def resolveBackend(
      modelName: String,
      config: AppConfig
  ): Either[ErrorResponse, BackendConfig] =
    config.backends.find(_.id == modelName) match {
      case Some(backend) => Right(backend)
      case None =>
        val availableModels = config.backends.map(_.id).mkString(", ")
        Left(
          ErrorResponse(
            ErrorDetail(
              s"Model '$modelName' not found. Available models: $availableModels",
              "invalid_request_error"
            )
          )
        )
    }
}
