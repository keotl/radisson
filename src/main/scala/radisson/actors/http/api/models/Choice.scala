package radisson.actors.http.api.models

import io.circe.Codec

case class Choice(
    index: Int,
    message: Message,
    finish_reason: Option[
      String
    ], // "stop", "length", "content_filter", "tool_calls"
    logprobs: Option[LogProbs] = None
) derives Codec.AsObject
