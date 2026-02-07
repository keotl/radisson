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
    parameters: Option[Json] = None // JSON Schema
) derives Codec.AsObject

case class ToolCall(
    id: String,
    `type`: String, // "function"
    function: FunctionCall
) derives Codec.AsObject

case class FunctionCall(
    name: String,
    arguments: String // JSON string
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
