package radisson.actors.http.api.models

import io.circe.parser

class ToolCallResponseDeserializationTest extends munit.FunSuite {

  test("deserialize response with tool_calls and null content") {
    val jsonWithNullContent =
      """{
        |  "id": "chatcmpl-123",
        |  "object": "chat.completion",
        |  "created": 1677652288,
        |  "model": "gpt-4",
        |  "choices": [{
        |    "index": 0,
        |    "message": {
        |      "role": "assistant",
        |      "content": null,
        |      "tool_calls": [{
        |        "id": "call_123",
        |        "type": "function",
        |        "function": {
        |          "name": "get_weather",
        |          "arguments": "{\"location\": \"Boston\"}"
        |        }
        |      }]
        |    },
        |    "finish_reason": "tool_calls"
        |  }],
        |  "usage": {
        |    "prompt_tokens": 82,
        |    "completion_tokens": 17,
        |    "total_tokens": 99
        |  }
        |}""".stripMargin

    val result = parser.decode[ChatCompletionResponse](jsonWithNullContent)

    result match {
      case Right(response) =>
        assertEquals(response.id, "chatcmpl-123")
        assertEquals(response.choices.head.message.role, "assistant")
        assert(
          response.choices.head.message.tool_calls.isDefined,
          "tool_calls should be defined"
        )
        assertEquals(
          response.choices.head.message.tool_calls.get.head.function.name,
          Some("get_weather")
        )
      case Left(error) =>
        fail(s"Failed to deserialize: $error")
    }
  }

  test("deserialize response with tool_calls and empty string content") {
    val jsonWithEmptyContent =
      """{
        |  "id": "chatcmpl-456",
        |  "object": "chat.completion",
        |  "created": 1677652288,
        |  "model": "gpt-4",
        |  "choices": [{
        |    "index": 0,
        |    "message": {
        |      "role": "assistant",
        |      "content": "",
        |      "tool_calls": [{
        |        "id": "call_456",
        |        "type": "function",
        |        "function": {
        |          "name": "search",
        |          "arguments": "{\"query\": \"scala\"}"
        |        }
        |      }]
        |    },
        |    "finish_reason": "tool_calls"
        |  }],
        |  "usage": {
        |    "prompt_tokens": 100,
        |    "completion_tokens": 20,
        |    "total_tokens": 120
        |  }
        |}""".stripMargin

    val result = parser.decode[ChatCompletionResponse](jsonWithEmptyContent)

    result match {
      case Right(response) =>
        assertEquals(response.id, "chatcmpl-456")
        assertEquals(response.choices.head.message.content, Some(""))
      case Left(error) =>
        fail(s"Failed to deserialize: $error")
    }
  }
}
