package radisson.actors.http.api.models

import java.time.Instant

object OllamaAdapter {

  // Convert Ollama chat request to OpenAI chat request
  def toOpenAIChatRequest(req: OllamaChatRequest): ChatCompletionRequest = {
    val messages = req.messages.map { msg =>
      Message(
        role = msg.role,
        content = msg.content,
        name = None
      )
    }

    ChatCompletionRequest(
      model = req.model,
      messages = messages,
      temperature = req.options.flatMap(_.temperature),
      max_tokens = req.options.flatMap(_.max_tokens),
      stream = req.stream,
      top_p = req.options.flatMap(_.top_p),
      n = None,
      stop = req.options.flatMap(_.stop),
      presence_penalty = None,
      frequency_penalty = None,
      user = None
    )
  }

  // Convert Ollama generate request to OpenAI chat request
  def toOpenAIChatRequestFromGenerate(
      req: OllamaGenerateRequest
  ): ChatCompletionRequest = {
    val messages = req.system match {
      case Some(sys) =>
        List(
          Message("system", sys),
          Message("user", req.prompt)
        )
      case None =>
        List(Message("user", req.prompt))
    }

    ChatCompletionRequest(
      model = req.model,
      messages = messages,
      temperature = req.options.flatMap(_.temperature),
      max_tokens = req.options.flatMap(_.max_tokens),
      stream = req.stream,
      top_p = req.options.flatMap(_.top_p),
      n = None,
      stop = req.options.flatMap(_.stop),
      presence_penalty = None,
      frequency_penalty = None,
      user = None
    )
  }

  // Convert OpenAI response to Ollama chat response
  def toOllamaChatResponse(
      response: ChatCompletionResponse,
      model: String
  ): OllamaChatResponse = {
    val choice = response.choices.headOption.getOrElse(
      Choice(0, Message("assistant", ""), Some("stop"))
    )

    OllamaChatResponse(
      model = model,
      created_at = Instant.now().toString,
      message = OllamaMessage(
        role = choice.message.role,
        content = choice.message.content,
        thinking = None,
        tool_calls = None
      ),
      done = true,
      done_reason = choice.finish_reason,
      total_duration = None,
      load_duration = None,
      prompt_eval_count = Some(response.usage.prompt_tokens),
      prompt_eval_duration = None,
      eval_count = Some(response.usage.completion_tokens),
      eval_duration = None
    )
  }

  // Convert OpenAI response to Ollama generate response
  def toOllamaGenerateResponse(
      response: ChatCompletionResponse,
      model: String
  ): OllamaGenerateResponse = {
    val content = response.choices.headOption
      .map(_.message.content)
      .getOrElse("")

    val finishReason = response.choices.headOption
      .flatMap(_.finish_reason)

    OllamaGenerateResponse(
      model = model,
      created_at = Instant.now().toString,
      response = content,
      done = true,
      done_reason = finishReason,
      context = None,
      total_duration = None,
      load_duration = None,
      prompt_eval_count = Some(response.usage.prompt_tokens),
      prompt_eval_duration = None,
      eval_count = Some(response.usage.completion_tokens),
      eval_duration = None
    )
  }

  // Convert OpenAI chunk to Ollama chat chunk
  def toOllamaChatChunk(
      chunk: ChatCompletionChunk,
      model: String,
      isDone: Boolean
  ): OllamaChatChunk = {
    val delta = chunk.choices.headOption
      .map(_.delta)
      .getOrElse(Delta())

    val finishReason = if isDone then Some("stop") else None

    OllamaChatChunk(
      model = model,
      created_at = Instant.now().toString,
      message = OllamaMessageDelta(
        role = delta.role,
        content = delta.content,
        thinking = None,
        tool_calls = None
      ),
      done = isDone,
      done_reason = finishReason,
      total_duration = None,
      load_duration = None,
      prompt_eval_count = None,
      prompt_eval_duration = None,
      eval_count = None,
      eval_duration = None
    )
  }

  // Convert OpenAI chunk to Ollama generate chunk
  def toOllamaGenerateChunk(
      chunk: ChatCompletionChunk,
      model: String,
      isDone: Boolean
  ): OllamaGenerateChunk = {
    val content = chunk.choices.headOption
      .flatMap(_.delta.content)
      .getOrElse("")

    val finishReason = if isDone then Some("stop") else None

    OllamaGenerateChunk(
      model = model,
      created_at = Instant.now().toString,
      response = content,
      done = isDone,
      done_reason = finishReason,
      context = None,
      total_duration = None,
      load_duration = None,
      prompt_eval_count = None,
      prompt_eval_duration = None,
      eval_count = None,
      eval_duration = None
    )
  }
}
