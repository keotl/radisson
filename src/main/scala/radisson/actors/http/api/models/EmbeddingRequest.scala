package radisson.actors.http.api.models

import io.circe._
import io.circe.syntax._

enum EmbeddingInput {
  case Single(value: String)
  case Multiple(values: List[String])

  def toList: List[String] = this match {
    case Single(value)    => List(value)
    case Multiple(values) => values
  }
}

object EmbeddingInput {
  given Decoder[EmbeddingInput] = Decoder.instance { cursor =>
    cursor
      .as[String]
      .map(Single.apply)
      .orElse(cursor.as[List[String]].map(Multiple.apply))
  }

  given Encoder[EmbeddingInput] = Encoder.instance {
    case Single(value)    => value.asJson
    case Multiple(values) => values.asJson
  }
}

case class EmbeddingRequest(
    model: String,
    input: EmbeddingInput,
    encoding_format: Option[String] = None,
    dimensions: Option[Int] = None
) derives Codec.AsObject
