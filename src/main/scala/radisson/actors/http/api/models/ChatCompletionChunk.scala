package radisson.actors.http.api.models

import io.circe.Codec

case class ChatCompletionChunk(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: List[ChunkChoice],
    system_fingerprint: Option[String] = None,
    service_tier: Option[String] = None,
    usage: Option[Usage] = None
) derives Codec.AsObject

case class ChunkChoice(
    index: Int,
    delta: Delta,
    finish_reason: Option[String],
    logprobs: Option[LogProbs] = None
) derives Codec.AsObject

case class Delta(
    role: Option[String] = None,
    content: Option[String] = None,
    tool_calls: Option[List[ToolCall]] = None,
    reasoning_content: Option[String] = None
) derives Codec.AsObject
