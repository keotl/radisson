package radisson.actors.http.api.models

import io.circe.derivation.Configuration
import io.circe.{Codec, Json, JsonObject}
import radisson.util.PassthroughCodec

case class Message(
    role: String, // "system", "user", "assistant", "tool"
    content: Option[Json] = None,
    name: Option[String] = None,
    tool_calls: Option[List[ToolCall]] =
      None, // For assistant messages invoking tools
    tool_call_id: Option[String] = None, // For tool response messages
    reasoning_content: Option[String] = None,
    // Fields not modelled above; forwarded unchanged.
    passthrough: JsonObject = JsonObject.empty
)

object Message {
  private given Configuration = Configuration.default.withDefaults

  given Codec.AsObject[Message] = PassthroughCodec(
    Codec.AsObject.derivedConfigured[Message],
    _.passthrough,
    (m, extra) => m.copy(passthrough = extra)
  )
}
