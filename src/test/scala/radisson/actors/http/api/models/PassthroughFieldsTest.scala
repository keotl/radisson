package radisson.actors.http.api.models

import io.circe.parser._
import io.circe.syntax._
import io.circe.{Json, JsonObject, Printer}
import munit.FunSuite
import radisson.actors.completion.RequestBuilder
import radisson.util.FieldDropDetector

class PassthroughFieldsTest extends FunSuite {

  private val jsonPrinter = Printer.noSpaces.copy(dropNullValues = true)

  private val requestJson = """{
    "model": "inkling",
    "messages": [
      {"role": "system", "content": "be brief"},
      {"role": "user", "content": "hi", "cache_hint": {"ttl": 60}}
    ],
    "max_tokens": 100,
    "reasoning_effort": "low",
    "chat_template_kwargs": {"enable_thinking": false},
    "top_k": 20,
    "min_p": 0.05,
    "response_format": {"type": "json_object"}
  }"""

  private def decoded: ChatCompletionRequest =
    decode[ChatCompletionRequest](requestJson)
      .fold(e => fail(e.toString), identity)

  test("unknown top-level fields are captured on decode") {
    val req = decoded
    assertEquals(req.max_tokens, Some(100))
    assertEquals(req.reasoning_effort, Some("low"))
    assertEquals(
      req.passthrough.keys.toSet,
      Set("chat_template_kwargs", "top_k", "min_p", "response_format")
    )
  }

  test("round trip preserves every field the client sent") {
    val original = parse(requestJson).toOption.get
    val reEncoded = parse(decoded.asJson.printWith(jsonPrinter)).toOption.get
    assertEquals(reEncoded, original)
    assertEquals(
      FieldDropDetector.findDroppedFields(original, decoded.asJson),
      Set.empty[String]
    )
  }

  test("unknown message fields are preserved") {
    val msg = decoded.messages(1)
    assertEquals(
      msg.passthrough("cache_hint"),
      Some(Json.obj("ttl" -> Json.fromInt(60)))
    )
    assertEquals(
      msg.asJson.hcursor.downField("cache_hint").downField("ttl").as[Int],
      Right(60)
    )
  }

  test("no passthrough key leaks onto the wire") {
    val json = decoded.asJson
    assert(json.hcursor.downField(PassthroughCodecKey).failed)
    assert(
      json.hcursor
        .downField("messages")
        .downArray
        .downField(PassthroughCodecKey)
        .failed
    )
  }

  test("modelled fields win over passthrough values") {
    val req = ChatCompletionRequest(
      model = "real",
      messages = Nil,
      passthrough = JsonObject(
        "model" -> Json.fromString("shadow"),
        "top_k" -> Json.fromInt(5)
      )
    )
    val c = req.asJson.hcursor
    assertEquals(c.downField("model").as[String], Right("real"))
    assertEquals(c.downField("top_k").as[Int], Right(5))
  }

  test(
    "client field literally named passthrough is forwarded, not interpreted"
  ) {
    val req = decode[ChatCompletionRequest](
      """{"model": "m", "messages": [], "passthrough": {"x": 1}}"""
    ).toOption.get
    assertEquals(
      req.asJson.hcursor.downField("passthrough").downField("x").as[Int],
      Right(1)
    )
  }

  test("upstream body forwards unknown fields with backend model override") {
    val endpoint = RequestBuilder.EndpointInfo(
      requestUrl = "http://127.0.0.1:1/v1/chat/completions",
      headers = Map.empty,
      model = Some("/models/inkling.gguf"),
      timeout = 30
    )
    val body = RequestBuilder.upstreamBody(decoded, endpoint)
    val c = parse(body).toOption.get.hcursor
    assertEquals(c.downField("model").as[String], Right("/models/inkling.gguf"))
    assertEquals(c.downField("reasoning_effort").as[String], Right("low"))
    assertEquals(
      c.downField("chat_template_kwargs")
        .downField("enable_thinking")
        .as[Boolean],
      Right(false)
    )
    assertEquals(c.downField("top_k").as[Int], Right(20))
    assertEquals(
      c.downField("response_format").downField("type").as[String],
      Right("json_object")
    )
  }

  private val PassthroughCodecKey = radisson.util.PassthroughCodec.Key
}
