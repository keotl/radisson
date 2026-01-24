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
    request_timeout: Int // seconds
) derives Codec.AsObject

case class BackendConfig(
    id: String,
    `type`: String, // "remote" or "local"
    endpoint: Option[String] = None,
    api_key: Option[String] = None,
    model: Option[String] = None,
    command: Option[String] = None,
    resources: Option[BackendResources] = None,
    upstream_timeout: Option[Int] = None
) derives Codec.AsObject

case class BackendResources(
    memory: String // e.g., "100Mi"
) derives Codec.AsObject

case class ResourceConfig(
    total_memory: String // e.g., "1024Mi"
) derives Codec.AsObject
