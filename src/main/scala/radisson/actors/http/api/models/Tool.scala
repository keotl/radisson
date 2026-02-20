package radisson.actors.http.api.models

import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}

case class Tool(
    `type`: String, // "function"
    function: FunctionDefinition
) derives Codec.AsObject

case class FunctionDefinition(
    name: String,
    description: Option[String] = None,
    parameters: Option[Json] = None, // JSON Schema
    strict: Option[Boolean] =
      None // Enables structured outputs with strict validation
) derives Codec.AsObject

case class ToolCall(
    id: Option[String] =
      None, // Required in non-streaming, optional in streaming deltas
    `type`: Option[String] =
      None, // Required in non-streaming, optional in streaming deltas
    function: FunctionCall,
    index: Option[Int] =
      None // For streaming: identifies which tool call in parallel calls
) derives Codec.AsObject

case class FunctionCall(
    name: Option[String] =
      None, // Present in first delta, absent in subsequent deltas
    arguments: Option[String] = None // Accumulated across streaming deltas
) derives Codec.AsObject

case class ToolChoiceFunction(
    name: String
) derives Codec.AsObject

case class ToolChoiceObject(
    `type`: String, // "function"
    function: ToolChoiceFunction
) derives Codec.AsObject

// tool_choice can be a string ("auto", "none", "required") or an object
enum ToolChoice derives Codec.AsObject {
  case StringChoice(value: String)
  case ObjectChoice(choice: ToolChoiceObject)
}

object ToolChoice {
  given Decoder[ToolChoice] = Decoder.instance { cursor =>
    cursor
      .as[String]
      .map(StringChoice.apply)
      .orElse(cursor.as[ToolChoiceObject].map(ObjectChoice.apply))
  }

  given Encoder[ToolChoice] = Encoder.instance {
    case StringChoice(value) => value.asJson
    case ObjectChoice(obj)   => obj.asJson
  }
}
