package radisson.actors.http.api.models

import io.circe.Codec

case class Message(
    role: String, // "system", "user", "assistant"
    content: String,
    name: Option[String] = None
) derives Codec.AsObject
