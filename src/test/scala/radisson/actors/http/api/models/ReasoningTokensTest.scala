package radisson.actors.http.api.models

import io.circe.{Json, parser}
import io.circe.parser.decode
import io.circe.syntax._

class ReasoningTokensTest extends munit.FunSuite {

  test("deserialize Message with reasoning_content") {
    val json = """{
      "role": "assistant",
      "content": null,
      "reasoning_content": "Let me think about this step by step..."
    }"""

    val result = decode[Message](json)
    assert(result.isRight, s"Failed to decode: $result")
    val message = result.toOption.get
    assertEquals(
      message.reasoning_content,
      Some("Let me think about this step by step...")
    )
    assertEquals(message.content, None)
  }

  test("deserialize Message with both content and reasoning_content") {
    val json = """{
      "role": "assistant",
      "content": "The answer is 42.",
      "reasoning_content": "I need to calculate..."
    }"""

    val result = decode[Message](json)
    assert(result.isRight)
    val message = result.toOption.get
    assertEquals(message.content, Some(Json.fromString("The answer is 42.")))
    assertEquals(message.reasoning_content, Some("I need to calculate..."))
  }

  test("deserialize Delta with reasoning_content") {
    val json = """{
      "role": "assistant",
      "reasoning_content": "thinking..."
    }"""

    val result = decode[Delta](json)
    assert(result.isRight)
    val delta = result.toOption.get
    assertEquals(delta.reasoning_content, Some("thinking..."))
    assertEquals(delta.content, None)
  }

  test("deserialize ChatCompletionRequest with reasoning fields") {
    val json = """{
      "model": "o3",
      "messages": [{"role": "user", "content": "test"}],
      "max_completion_tokens": 4096,
      "reasoning_effort": "high",
      "stream_options": {"include_usage": true}
    }"""

    val result = decode[ChatCompletionRequest](json)
    assert(result.isRight, s"Failed to decode: $result")
    val request = result.toOption.get
    assertEquals(request.max_completion_tokens, Some(4096))
    assertEquals(request.reasoning_effort, Some("high"))
    assert(request.stream_options.isDefined)
    assertEquals(request.stream_options.get.include_usage, Some(true))
  }

  test("deserialize Usage with token detail breakdowns") {
    val json = """{
      "prompt_tokens": 100,
      "completion_tokens": 200,
      "total_tokens": 300,
      "completion_tokens_details": {
        "reasoning_tokens": 150,
        "accepted_prediction_tokens": 10,
        "rejected_prediction_tokens": 5
      },
      "prompt_tokens_details": {
        "cached_tokens": 50
      }
    }"""

    val result = decode[Usage](json)
    assert(result.isRight, s"Failed to decode: $result")
    val usage = result.toOption.get
    assertEquals(usage.prompt_tokens, 100)
    assertEquals(usage.completion_tokens, 200)
    assertEquals(usage.total_tokens, 300)

    assert(usage.completion_tokens_details.isDefined)
    val ctd = usage.completion_tokens_details.get
    assertEquals(ctd.reasoning_tokens, Some(150))
    assertEquals(ctd.accepted_prediction_tokens, Some(10))
    assertEquals(ctd.rejected_prediction_tokens, Some(5))

    assert(usage.prompt_tokens_details.isDefined)
    assertEquals(usage.prompt_tokens_details.get.cached_tokens, Some(50))
  }

  test("round-trip serialization preserves reasoning fields") {
    val message = Message(
      role = "assistant",
      content = Some(Json.fromString("The answer is 42.")),
      reasoning_content = Some("Let me think...")
    )

    val json = message.asJson
    val decoded = json.as[Message]
    assert(decoded.isRight)
    val roundtrip = decoded.toOption.get
    assertEquals(roundtrip.reasoning_content, Some("Let me think..."))
    assertEquals(roundtrip.content, Some(Json.fromString("The answer is 42.")))
  }

  test("backward compatibility: Message without reasoning fields") {
    val json = """{
      "role": "assistant",
      "content": "Hello!"
    }"""

    val result = decode[Message](json)
    assert(result.isRight)
    val message = result.toOption.get
    assertEquals(message.content, Some(Json.fromString("Hello!")))
    assertEquals(message.reasoning_content, None)
  }

  test("backward compatibility: Usage without detail breakdowns") {
    val json = """{
      "prompt_tokens": 10,
      "completion_tokens": 20,
      "total_tokens": 30
    }"""

    val result = decode[Usage](json)
    assert(result.isRight)
    val usage = result.toOption.get
    assertEquals(usage.completion_tokens_details, None)
    assertEquals(usage.prompt_tokens_details, None)
  }

  test(
    "backward compatibility: ChatCompletionRequest without reasoning fields"
  ) {
    val json = """{
      "model": "gpt-4",
      "messages": [{"role": "user", "content": "test"}]
    }"""

    val result = decode[ChatCompletionRequest](json)
    assert(result.isRight)
    val request = result.toOption.get
    assertEquals(request.max_completion_tokens, None)
    assertEquals(request.reasoning_effort, None)
    assertEquals(request.stream_options, None)
  }

  test("ChatCompletionChunk with usage in final streaming chunk") {
    val json = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1700000000,
      "model": "o3",
      "choices": [],
      "usage": {
        "prompt_tokens": 10,
        "completion_tokens": 20,
        "total_tokens": 30,
        "completion_tokens_details": {
          "reasoning_tokens": 15
        }
      }
    }"""

    val result = decode[ChatCompletionChunk](json)
    assert(result.isRight, s"Failed to decode: $result")
    val chunk = result.toOption.get
    assert(chunk.usage.isDefined)
    assertEquals(chunk.usage.get.total_tokens, 30)
    assert(chunk.usage.get.completion_tokens_details.isDefined)
    assertEquals(
      chunk.usage.get.completion_tokens_details.get.reasoning_tokens,
      Some(15)
    )
  }

  test("round-trip serialization of request with reasoning fields") {
    val request = ChatCompletionRequest(
      model = "o3",
      messages = List(Message("user", Some(Json.fromString("test")))),
      max_completion_tokens = Some(4096),
      reasoning_effort = Some("medium"),
      stream_options = Some(StreamOptions(include_usage = Some(true)))
    )

    val json = request.asJson
    val decoded = json.as[ChatCompletionRequest]
    assert(decoded.isRight)
    val roundtrip = decoded.toOption.get
    assertEquals(roundtrip.max_completion_tokens, Some(4096))
    assertEquals(roundtrip.reasoning_effort, Some("medium"))
    assertEquals(roundtrip.stream_options.get.include_usage, Some(true))
  }
}
