package radisson.util

import io.circe.Json
import io.circe.parser

class FieldDropDetectorTest extends munit.FunSuite {

  test("detects top-level dropped fields") {
    val original = parser.parse("""{
      "name": "Alice",
      "age": 30,
      "secret_field": "dropped"
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "name": "Alice",
      "age": 30
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assertEquals(dropped, Set("secret_field"))
  }

  test("detects nested dropped fields") {
    val original = parser.parse("""{
      "name": "Alice",
      "usage": {
        "prompt_tokens": 10,
        "audio_tokens": 5
      }
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "name": "Alice",
      "usage": {
        "prompt_tokens": 10
      }
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assertEquals(dropped, Set("usage.audio_tokens"))
  }

  test("returns empty set when no fields are dropped") {
    val original = parser.parse("""{
      "name": "Alice",
      "age": 30
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "name": "Alice",
      "age": 30
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assert(dropped.isEmpty)
  }

  test("handles arrays with dropped fields in elements") {
    val original = parser.parse("""{
      "choices": [
        {"index": 0, "extra": "dropped"},
        {"index": 1, "extra": "also dropped"}
      ]
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "choices": [
        {"index": 0},
        {"index": 1}
      ]
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assertEquals(dropped, Set("choices[0].extra", "choices[1].extra"))
  }

  test("detects both top-level and nested dropped fields") {
    val original = parser.parse("""{
      "id": "123",
      "unknown_top": true,
      "usage": {
        "prompt_tokens": 10,
        "completion_tokens": 20,
        "total_tokens": 30,
        "unknown_nested": 99
      }
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "id": "123",
      "usage": {
        "prompt_tokens": 10,
        "completion_tokens": 20,
        "total_tokens": 30
      }
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assertEquals(dropped, Set("unknown_top", "usage.unknown_nested"))
  }

  test("handles deeply nested structures") {
    val original = parser.parse("""{
      "a": {
        "b": {
          "c": 1,
          "d": 2
        }
      }
    }""").toOption.get

    val reEncoded = parser.parse("""{
      "a": {
        "b": {
          "c": 1
        }
      }
    }""").toOption.get

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assertEquals(dropped, Set("a.b.d"))
  }

  test("handles non-object json values") {
    val original = Json.fromString("hello")
    val reEncoded = Json.fromString("hello")

    val dropped = FieldDropDetector.findDroppedFields(original, reEncoded)
    assert(dropped.isEmpty)
  }
}
