package radisson.config

import io.circe.Codec

case class AppConfig(
    server: ServerConfig,
    backends: List[BackendConfig],
    resources: ResourceConfig
) derives Codec.AsObject

case class ServerConfig(
    host: String,
    port: Int,
    request_timeout: Int, // seconds
    request_tracing: Option[Boolean] = None
) derives Codec.AsObject

case class BackendConfig(
    id: String,
    `type`: String, // "remote", "local", "local-embeddings", "local-stub", or "first-available"
    endpoint: Option[String] = None,
    api_key: Option[String] = None,
    model: Option[String] = None,
    command: Option[String] = None,
    upstream_url: Option[String] = None,
    resources: Option[BackendResources] = None,
    upstream_timeout: Option[Int] = None,
    startup_timeout: Option[Int] = None, // seconds, for slow-starting backends
    backends: Option[List[String]] =
      None // for "first-available" type: list of backend IDs to try
) derives Codec.AsObject

case class BackendResources(
    memory: String // e.g., "100Mi"
) derives Codec.AsObject

case class ResourceConfig(
    total_memory: String // e.g., "1024Mi"
) derives Codec.AsObject
