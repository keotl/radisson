package radisson.actors.http.api.models

import io.circe.parser.decode
import io.circe.syntax._

class ToolSerializationTest extends munit.FunSuite {

  test("deserialize ChatCompletionRequest with tools") {
    val json = """{
      "model": "gpt-4",
      "messages": [
        {"role": "user", "content": "What's the weather?"}
      ],
      "tools": [
        {
          "type": "function",
          "function": {
            "name": "get_weather",
            "description": "Get the current weather",
            "parameters": {
              "type": "object",
              "properties": {
                "location": {"type": "string"}
              }
            }
          }
        }
      ],
      "tool_choice": "auto"
    }"""

    val result = decode[ChatCompletionRequest](json)
    assert(result.isRight, s"Failed to decode: $result")

    val request = result.toOption.get
    assert(request.tools.isDefined, "tools should be defined")
    assert(request.tools.get.length == 1, "should have one tool")
    assert(request.tools.get.head.function.name == "get_weather")
    assert(request.tool_choice.isDefined, "tool_choice should be defined")
  }

  test("deserialize ChatCompletionRequest with tool_choice as string") {
    val json = """{
      "model": "gpt-4",
      "messages": [{"role": "user", "content": "test"}],
      "tool_choice": "auto"
    }"""

    val result = decode[ChatCompletionRequest](json)
    assert(result.isRight)
    val request = result.toOption.get
    assert(request.tool_choice.isDefined)
  }

  test("deserialize ChatCompletionRequest with tool_choice as object") {
    val json = """{
      "model": "gpt-4",
      "messages": [{"role": "user", "content": "test"}],
      "tool_choice": {
        "type": "function",
        "function": {"name": "get_weather"}
      }
    }"""

    val result = decode[ChatCompletionRequest](json)
    assert(result.isRight)
    val request = result.toOption.get
    assert(request.tool_choice.isDefined)
  }

  test(
    "serialize and deserialize ChatCompletionRequest with tools preserves data"
  ) {
    val tool = Tool(
      `type` = "function",
      function = FunctionDefinition(
        name = "get_weather",
        description = Some("Get weather"),
        parameters = Some(io.circe.Json.obj("type" -> "object".asJson))
      )
    )

    val request = ChatCompletionRequest(
      model = "gpt-4",
      messages = List(Message("user", Some("test"))),
      tools = Some(List(tool)),
      tool_choice = Some(ToolChoice.StringChoice("auto"))
    )

    val json = request.asJson
    val decoded = json.as[ChatCompletionRequest]

    assert(decoded.isRight)
    val roundtrip = decoded.toOption.get
    assert(roundtrip.tools.isDefined)
    assert(
      roundtrip.tools.get.head.function.name == "get_weather",
      "tool name should be get_weather"
    )
    assert(roundtrip.tool_choice.isDefined)
  }

  test("deserialize Message with tool_calls") {
    val json = """{
      "role": "assistant",
      "content": "",
      "tool_calls": [
        {
          "id": "call_123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"location\": \"San Francisco\"}"
          }
        }
      ]
    }"""

    val result = decode[Message](json)
    assert(result.isRight)
    val message = result.toOption.get
    assert(message.tool_calls.isDefined)
    assert(message.tool_calls.get.length == 1)
    assert(message.tool_calls.get.head.id == "call_123")
    assert(message.tool_calls.get.head.function.name == "get_weather")
  }

  test("deserialize Message with tool_call_id") {
    val json = """{
      "role": "tool",
      "content": "{\"temperature\": 72}",
      "tool_call_id": "call_123"
    }"""

    val result = decode[Message](json)
    assert(result.isRight)
    val message = result.toOption.get
    assert(message.tool_call_id.isDefined)
    assert(message.tool_call_id.get == "call_123")
  }

  test("deserialize Delta with tool_calls") {
    val json = """{
      "role": "assistant",
      "tool_calls": [
        {
          "id": "call_123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"location\""
          }
        }
      ]
    }"""

    val result = decode[Delta](json)
    assert(result.isRight)
    val delta = result.toOption.get
    assert(delta.tool_calls.isDefined)
    assert(delta.tool_calls.get.head.id == "call_123")
  }
}
