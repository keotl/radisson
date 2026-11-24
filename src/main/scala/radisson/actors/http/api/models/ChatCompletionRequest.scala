package radisson.actors.http.api.models

import io.circe.derivation.Configuration
import io.circe.{Codec, JsonObject}
import radisson.util.PassthroughCodec

case class ChatCompletionRequest(
    model: String,
    messages: List[Message],
    temperature: Option[Double] = None,
    max_tokens: Option[Int] = None,
    stream: Option[Boolean] = None,
    top_p: Option[Double] = None,
    n: Option[Int] = None,
    stop: Option[List[String]] = None,
    presence_penalty: Option[Double] = None,
    frequency_penalty: Option[Double] = None,
    user: Option[String] = None,
    tools: Option[List[Tool]] = None,
    tool_choice: Option[ToolChoice] = None,
    parallel_tool_calls: Option[Boolean] = None,
    logprobs: Option[Boolean] = None,
    top_logprobs: Option[Int] = None,
    service_tier: Option[String] =
      None, // "auto", "default", "flex", "priority"
    max_completion_tokens: Option[Int] = None,
    stream_options: Option[StreamOptions] = None,
    reasoning_effort: Option[String] =
      None, // e.g. "low", "medium", "high"; passed to the backend as-is
    // Fields not modelled above (e.g. llama.cpp's chat_template_kwargs,
    // top_k, min_p, response_format). Forwarded to the backend unchanged.
    passthrough: JsonObject = JsonObject.empty
)

object ChatCompletionRequest {
  private given Configuration = Configuration.default.withDefaults

  given Codec.AsObject[ChatCompletionRequest] = PassthroughCodec(
    Codec.AsObject.derivedConfigured[ChatCompletionRequest],
    _.passthrough,
    (r, extra) => r.copy(passthrough = extra)
  )
}

case class StreamOptions(
    include_usage: Option[Boolean] = None
) derives Codec.AsObject
