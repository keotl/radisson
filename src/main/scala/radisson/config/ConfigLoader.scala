package radisson.config

import java.nio.file.{Files, Paths}

import scala.util.Try

import io.circe.yaml.parser

object ConfigLoader:
  def loadConfig(path: String): Either[String, AppConfig] =
    for
      content <- Try(Files.readString(Paths.get(path))).toEither.left.map(e =>
        s"Failed to read config file: ${e.getMessage}"
      )
      json <- parser
        .parse(content)
        .left
        .map(e => s"Failed to parse YAML: ${e.getMessage}")
      config <- json
        .as[AppConfig]
        .left
        .map(e => s"Failed to decode config: ${e.getMessage}")
      _ <- ConfigValidator.validateConfig(config)
    yield config
