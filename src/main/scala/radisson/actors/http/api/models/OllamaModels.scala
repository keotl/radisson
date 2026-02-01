package radisson.actors.http.api.models

import io.circe.Codec

case class OllamaTagsResponse(
    models: List[OllamaModel]
) derives Codec.AsObject

case class OllamaModel(
    name: String,
    model: String,
    modified_at: String,
    size: Long,
    digest: String,
    details: OllamaModelDetails
) derives Codec.AsObject

case class OllamaModelDetails(
    parent_model: String,
    format: String,
    family: String,
    families: List[String],
    parameter_size: String,
    quantization_level: String
) derives Codec.AsObject

// Chat API models
case class OllamaChatRequest(
    model: String,
    messages: List[OllamaMessage],
    tools: Option[List[OllamaTool]] = None,
    format: Option[String] = None,
    options: Option[OllamaOptions] = None,
    stream: Option[Boolean] = Some(true),
    keep_alive: Option[String] = None,
    think: Option[Boolean] = None
) derives Codec.AsObject

case class OllamaChatResponse(
    model: String,
    created_at: String,
    message: OllamaMessage,
    done: Boolean,
    done_reason: Option[String] = None,
    total_duration: Option[Long] = None,
    load_duration: Option[Long] = None,
    prompt_eval_count: Option[Int] = None,
    prompt_eval_duration: Option[Long] = None,
    eval_count: Option[Int] = None,
    eval_duration: Option[Long] = None
) derives Codec.AsObject

case class OllamaChatChunk(
    model: String,
    created_at: String,
    message: OllamaMessageDelta,
    done: Boolean,
    done_reason: Option[String] = None,
    total_duration: Option[Long] = None,
    load_duration: Option[Long] = None,
    prompt_eval_count: Option[Int] = None,
    prompt_eval_duration: Option[Long] = None,
    eval_count: Option[Int] = None,
    eval_duration: Option[Long] = None
) derives Codec.AsObject

// Generate API models
case class OllamaGenerateRequest(
    model: String,
    prompt: String,
    images: Option[List[String]] = None,
    format: Option[String] = None,
    system: Option[String] = None,
    stream: Option[Boolean] = Some(true),
    think: Option[Boolean] = None,
    raw: Option[Boolean] = None,
    options: Option[OllamaOptions] = None
) derives Codec.AsObject

case class OllamaGenerateResponse(
    model: String,
    created_at: String,
    response: String,
    done: Boolean,
    done_reason: Option[String] = None,
    context: Option[List[Int]] = None,
    total_duration: Option[Long] = None,
    load_duration: Option[Long] = None,
    prompt_eval_count: Option[Int] = None,
    prompt_eval_duration: Option[Long] = None,
    eval_count: Option[Int] = None,
    eval_duration: Option[Long] = None
) derives Codec.AsObject

case class OllamaGenerateChunk(
    model: String,
    created_at: String,
    response: String,
    done: Boolean,
    done_reason: Option[String] = None,
    context: Option[List[Int]] = None,
    total_duration: Option[Long] = None,
    load_duration: Option[Long] = None,
    prompt_eval_count: Option[Int] = None,
    prompt_eval_duration: Option[Long] = None,
    eval_count: Option[Int] = None,
    eval_duration: Option[Long] = None
) derives Codec.AsObject

// Shared models
case class OllamaMessage(
    role: String,
    content: String,
    thinking: Option[String] = None,
    tool_calls: Option[List[OllamaToolCall]] = None
) derives Codec.AsObject

case class OllamaMessageDelta(
    role: Option[String] = None,
    content: Option[String] = None,
    thinking: Option[String] = None,
    tool_calls: Option[List[OllamaToolCall]] = None
) derives Codec.AsObject

case class OllamaOptions(
    temperature: Option[Double] = None,
    top_p: Option[Double] = None,
    max_tokens: Option[Int] = None,
    stop: Option[List[String]] = None
) derives Codec.AsObject

case class OllamaTool(
    `type`: String,
    function: OllamaFunction
) derives Codec.AsObject

case class OllamaFunction(
    name: String,
    description: String,
    parameters: Option[io.circe.Json] = None
) derives Codec.AsObject

case class OllamaToolCall(
    id: Option[String] = None,
    `type`: String,
    function: OllamaFunctionCall
) derives Codec.AsObject

case class OllamaFunctionCall(
    name: String,
    arguments: String
) derives Codec.AsObject
