package radisson.actors.http.api.models

import io.circe.{Json, parser}
import io.circe.parser._
import io.circe.syntax._
import munit.FunSuite

class ExtendedFieldsTest extends FunSuite {

  test("ChatCompletionRequest serializes with new fields") {
    val request = ChatCompletionRequest(
      model = "gpt-4",
      messages = List(Message("user", Some(Json.fromString("Hello")))),
      parallel_tool_calls = Some(false),
      logprobs = Some(true),
      top_logprobs = Some(3),
      service_tier = Some("default")
    )

    val json = request.asJson
    val jsonString = json.noSpaces

    assert(jsonString.contains("parallel_tool_calls"))
    assert(jsonString.contains("logprobs"))
    assert(jsonString.contains("top_logprobs"))
    assert(jsonString.contains("service_tier"))

    // Verify round-trip
    val decoded = decode[ChatCompletionRequest](jsonString)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.parallel_tool_calls, Some(false))
    assertEquals(decoded.toOption.get.logprobs, Some(true))
    assertEquals(decoded.toOption.get.top_logprobs, Some(3))
    assertEquals(decoded.toOption.get.service_tier, Some("default"))
  }

  test("ChatCompletionResponse deserializes with new fields") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "message": {
          "role": "assistant",
          "content": "Hello!"
        },
        "finish_reason": "stop"
      }],
      "usage": {
        "prompt_tokens": 10,
        "completion_tokens": 5,
        "total_tokens": 15
      },
      "system_fingerprint": "fp_abc123",
      "service_tier": "default"
    }"""

    val decoded = decode[ChatCompletionResponse](jsonString)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.system_fingerprint, Some("fp_abc123"))
    assertEquals(decoded.toOption.get.service_tier, Some("default"))
  }

  test("FunctionDefinition with strict field") {
    val func = FunctionDefinition(
      name = "get_weather",
      description = Some("Get weather"),
      parameters = Some(parse("""{"type":"object"}""").toOption.get),
      strict = Some(true)
    )

    val json = func.asJson
    val jsonString = json.noSpaces

    assert(jsonString.contains("strict"))

    val decoded = decode[FunctionDefinition](jsonString)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.strict, Some(true))
  }

  test("ToolCall with index for streaming") {
    val toolCall = ToolCall(
      id = Some("call_abc123"),
      `type` = Some("function"),
      function =
        FunctionCall(Some("get_weather"), Some("""{"location":"SF"}""")),
      index = Some(0)
    )

    val json = toolCall.asJson
    val jsonString = json.noSpaces

    assert(jsonString.contains("index"))

    val decoded = decode[ToolCall](jsonString)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.index, Some(0))
  }

  test("Choice with logprobs") {
    val jsonString = """{
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello"
      },
      "finish_reason": "stop",
      "logprobs": {
        "content": [{
          "token": "Hello",
          "logprob": -0.31725305,
          "bytes": [72, 101, 108, 108, 111],
          "top_logprobs": [{
            "token": "Hello",
            "logprob": -0.31725305,
            "bytes": [72, 101, 108, 108, 111]
          }]
        }]
      }
    }"""

    val decoded = decode[Choice](jsonString)
    assert(decoded.isRight)
    val choice = decoded.toOption.get
    assert(choice.logprobs.isDefined)
    assert(choice.logprobs.get.content.isDefined)
    assertEquals(choice.logprobs.get.content.get.head.token, "Hello")
    assertEquals(choice.logprobs.get.content.get.head.logprob, -0.31725305)
  }

  test("ChatCompletionChunk with new fields") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "delta": {
          "content": "Hello"
        },
        "finish_reason": null
      }],
      "system_fingerprint": "fp_abc123",
      "service_tier": "default"
    }"""

    val decoded = decode[ChatCompletionChunk](jsonString)
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.system_fingerprint, Some("fp_abc123"))
    assertEquals(decoded.toOption.get.service_tier, Some("default"))
  }

  test("LogProbs structure serialization") {
    val logprobs = LogProbs(
      content = Some(
        List(
          TokenLogProb(
            token = "Hello",
            logprob = -0.31725305,
            bytes = Some(List(72, 101, 108, 108, 111)),
            top_logprobs = Some(
              List(
                TopLogProb(
                  "Hello",
                  -0.31725305,
                  Some(List(72, 101, 108, 108, 111))
                ),
                TopLogProb("Hi", -1.3190403, Some(List(72, 105)))
              )
            )
          )
        )
      )
    )

    val json = logprobs.asJson
    val decoded = json.as[LogProbs]
    assert(decoded.isRight)
    assertEquals(decoded.toOption.get.content.get.size, 1)
    assertEquals(decoded.toOption.get.content.get.head.token, "Hello")
  }

  test("Streaming tool call with index in delta") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "delta": {
          "tool_calls": [{
            "index": 0,
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": ""
            }
          }]
        },
        "finish_reason": null
      }]
    }"""

    val decoded = decode[ChatCompletionChunk](jsonString)
    assert(decoded.isRight)
    val chunk = decoded.toOption.get
    assert(chunk.choices.head.delta.tool_calls.isDefined)
    assertEquals(chunk.choices.head.delta.tool_calls.get.head.index, Some(0))
  }

  test("Streaming tool call - first chunk with id and type") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "delta": {
          "tool_calls": [{
            "index": 0,
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": ""
            }
          }]
        },
        "finish_reason": null
      }]
    }"""

    val decoded = decode[ChatCompletionChunk](jsonString)
    assert(decoded.isRight)
    val chunk = decoded.toOption.get
    val toolCall = chunk.choices.head.delta.tool_calls.get.head
    assertEquals(toolCall.index, Some(0))
    assertEquals(toolCall.id, Some("call_abc123"))
    assertEquals(toolCall.`type`, Some("function"))
    assertEquals(toolCall.function.name, Some("get_weather"))
    assertEquals(toolCall.function.arguments, Some(""))
  }

  test("Streaming tool call - subsequent chunk with only arguments") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "delta": {
          "tool_calls": [{
            "index": 0,
            "function": {
              "arguments": "{\"location\""
            }
          }]
        },
        "finish_reason": null
      }]
    }"""

    val decoded = decode[ChatCompletionChunk](jsonString)
    assert(decoded.isRight)
    val chunk = decoded.toOption.get
    val toolCall = chunk.choices.head.delta.tool_calls.get.head
    assertEquals(toolCall.index, Some(0))
    assertEquals(toolCall.id, None)
    assertEquals(toolCall.`type`, None)
    assertEquals(toolCall.function.name, None)
    assertEquals(toolCall.function.arguments, Some("{\"location\""))
  }

  test("Streaming tool call - final chunk with remaining arguments") {
    val jsonString = """{
      "id": "chatcmpl-123",
      "object": "chat.completion.chunk",
      "created": 1234567890,
      "model": "gpt-4",
      "choices": [{
        "index": 0,
        "delta": {
          "tool_calls": [{
            "index": 0,
            "function": {
              "arguments": ": \"SF\"}"
            }
          }]
        },
        "finish_reason": null
      }]
    }"""

    val decoded = decode[ChatCompletionChunk](jsonString)
    assert(decoded.isRight)
    val chunk = decoded.toOption.get
    val toolCall = chunk.choices.head.delta.tool_calls.get.head
    assertEquals(toolCall.index, Some(0))
    assertEquals(toolCall.id, None)
    assertEquals(toolCall.`type`, None)
    assertEquals(toolCall.function.name, None)
    assertEquals(toolCall.function.arguments, Some(": \"SF\"}"))
  }
}
