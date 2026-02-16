package radisson.actors.http.api.models

import io.circe.Codec

case class ChatCompletionResponse(
    id: String,
    `object`: String, // "chat.completion"
    created: Long, // Unix timestamp
    model: String,
    choices: List[Choice],
    usage: Usage,
    system_fingerprint: Option[String] = None,
    service_tier: Option[String] = None
) derives Codec.AsObject
