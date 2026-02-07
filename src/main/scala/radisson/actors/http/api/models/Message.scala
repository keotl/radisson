package radisson.actors.http.api.models

import io.circe.Codec

case class Message(
    role: String, // "system", "user", "assistant", "tool"
    content: Option[String] = None,
    name: Option[String] = None,
    tool_calls: Option[List[ToolCall]] =
      None, // For assistant messages invoking tools
    tool_call_id: Option[String] = None // For tool response messages
) derives Codec.AsObject
