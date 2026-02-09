package radisson.config

object ConfigValidator {

  def validateBackendConfig(backend: BackendConfig): Either[String, Unit] =
    backend.`type` match {
      case "local"            => validateLocalBackend(backend)
      case "local-embeddings" => validateLocalBackend(backend)
      case "local-stub"       => validateLocalBackend(backend)
      case "remote"           => validateRemoteBackend(backend)
      case other =>
        Left(s"Backend '${backend.id}': Unknown type '$other'")
    }

  private def validateLocalBackend(
      backend: BackendConfig
  ): Either[String, Unit] =
    backend.command match {
      case None =>
        Left(
          s"Backend '${backend.id}': Local backend must have 'command'"
        )
      case Some(_) =>
        backend.upstream_url match {
          case Some(url)
              if !url.startsWith("http://") && !url.startsWith("https://") =>
            Left(
              s"Backend '${backend.id}': upstream_url must start with " +
                "http:// or https://"
            )
          case _ => Right(())
        }
    }

  private def validateRemoteBackend(
      backend: BackendConfig
  ): Either[String, Unit] =
    backend.endpoint match {
      case None =>
        Left(s"Backend '${backend.id}': Remote backend must have 'endpoint'")
      case Some(_) =>
        backend.upstream_url match {
          case Some(_) =>
            Left(
              s"Backend '${backend.id}': Remote backend should not " +
                "have 'upstream_url'"
            )
          case None => Right(())
        }
    }

  def validateConfig(config: AppConfig): Either[String, Unit] =
    config.backends.foldLeft(Right(()): Either[String, Unit]) {
      (acc, backend) => acc.flatMap(_ => validateBackendConfig(backend))
    }
}
