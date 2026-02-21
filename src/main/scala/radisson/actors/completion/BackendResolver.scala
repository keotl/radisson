package radisson.actors.completion

import radisson.actors.http.api.models.{ErrorDetail, ErrorResponse}
import radisson.config.{AppConfig, BackendConfig}

object BackendResolver {

  def resolveBackend(
      modelName: String,
      config: AppConfig
  ): Either[ErrorResponse, BackendConfig] =
    resolveBackend(modelName, config, Set("local", "remote", "first-available"))

  def resolveBackend(
      modelName: String,
      config: AppConfig,
      allowedTypes: Set[String]
  ): Either[ErrorResponse, BackendConfig] = {
    val filteredBackends =
      config.backends.filter(b => allowedTypes.contains(b.`type`))
    filteredBackends.find(_.id == modelName) match {
      case Some(backend) => Right(backend)
      case None =>
        val availableModels = filteredBackends.map(_.id).mkString(", ")
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

  def resolveFirstAvailableBackend(
      firstAvailableBackend: BackendConfig,
      config: AppConfig
  ): Either[ErrorResponse, BackendConfig] =
    firstAvailableBackend.backends match {
      case None | Some(Nil) =>
        Left(
          ErrorResponse(
            ErrorDetail(
              s"Backend '${firstAvailableBackend.id}' has no backends to try",
              "invalid_request_error"
            )
          )
        )
      case Some(backendIds) =>
        resolveFirstAvailableBackendRecursive(
          backendIds,
          config,
          firstAvailableBackend
        )
    }

  private def resolveFirstAvailableBackendRecursive(
      backendIds: List[String],
      config: AppConfig,
      firstAvailableBackend: BackendConfig
  ): Either[ErrorResponse, BackendConfig] =
    backendIds match {
      case Nil =>
        Left(
          ErrorResponse(
            ErrorDetail(
              s"None of the backends for '${firstAvailableBackend.id}' are available",
              "service_unavailable"
            )
          )
        )
      case backendId :: remaining =>
        config.backends.find(_.id == backendId) match {
          case None =>
            resolveFirstAvailableBackendRecursive(
              remaining,
              config,
              firstAvailableBackend
            )
          case Some(backend) =>
            Right(backend)
        }
    }
}
