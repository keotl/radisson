package ca.ligature.radisson.dto

import ca.ligature.radisson.utils.serialization.{StringEnumCodec, _}
import io.circe.{Codec, Decoder, Encoder, JsonObject}

// Message role type
enum MessageRole {
  case system
  case user
  case assistant
  case tool
}
object MessageRole {
  given Encoder[MessageRole] = StringEnumCodec.encoder[MessageRole]
  given Decoder[MessageRole] = StringEnumCodec.decoder(MessageRole.values)
}

// Core message structure
case class Message(
    role: MessageRole,
    content: String,
    name: Option[String] = None,
    toolCalls: Option[Seq[ToolCall]] = None,
    toolCallId: Option[String] = None
) derives Codec.AsObject

// Tool definition types
case class Tool(
    `type`: String = "function",
    function: FunctionDefinition
) derives Codec.AsObject

case class FunctionDefinition(
    name: String,
    description: String,
    parameters: JsonObject
) derives Codec.AsObject

// Tool call types (in assistant responses)
case class ToolCall(
    id: String,
    `type`: String = "function",
    function: FunctionCall
) derives Codec.AsObject

case class FunctionCall(
    name: String,
    arguments: String // JSON string
) derives Codec.AsObject

// Tool choice types
case class ToolChoiceFunction(
    name: String
) derives Codec.AsObject

enum ToolChoice {
  case none, auto, required
}
object ToolChoice {
  given Encoder[ToolChoice] = StringEnumCodec.encoder[ToolChoice]
  given Decoder[ToolChoice] = StringEnumCodec.decoder(ToolChoice.values)
}

// Request type
case class ChatCompletionRequest(
    // Required
    model: String,
    messages: Seq[Message],

    // Common optional parameters (supported by both APIs)
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    maxTokens: Option[Int] = None,
    stop: Option[Seq[String]] = None,
    seed: Option[Int] = None,

    // Tool support
    tools: Option[Seq[Tool]] = None,
    toolChoice: Option[ToolChoice] = None
) derives Codec.AsObject

// Response types
case class ChatCompletionResponse(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: Seq[Choice],
    usage: Option[Usage] = None
) derives Codec.AsObject

case class Choice(
    index: Int,
    message: Message,
    finishReason: Option[String] = None
) derives Codec.AsObject

case class Usage(
    promptTokens: Int,
    completionTokens: Int,
    totalTokens: Int
) derives Codec.AsObject

// Embeddings types
enum EncodingFormat {
  case float, base64
}
object EncodingFormat {
  given Encoder[EncodingFormat] = StringEnumCodec.encoder[EncodingFormat]
  given Decoder[EncodingFormat] = StringEnumCodec.decoder(EncodingFormat.values)
}

import StringOrSeq.given

case class EmbeddingsRequest(
    input: StringOrSeq,
    model: String,
    dimensions: Option[Int] = None,
    encodingFormat: Option[EncodingFormat] = None,
    user: Option[String] = None
) derives Codec.AsObject

case class EmbeddingObject(
    embedding: Seq[Double],
    index: Int,
    `object`: String
) derives Codec.AsObject

case class EmbeddingsUsage(
    promptTokens: Int,
    totalTokens: Int
) derives Codec.AsObject

case class EmbeddingsResponse(
    `object`: String,
    data: Seq[EmbeddingObject],
    model: String,
    usage: EmbeddingsUsage
) derives Codec.AsObject
