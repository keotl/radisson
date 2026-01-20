package ca.ligature.radisson.utils.serialization

import io.circe.{Decoder, Encoder}

type StringOrSeq = String | Seq[String]

object StringOrSeq {
  given Decoder[StringOrSeq] =
    Decoder[Seq[String]].map(identity).or(Decoder[String].map(identity))

  given Encoder[StringOrSeq] = Encoder.instance {
    case s: String        => Encoder[String].apply(s)
    case seq: Seq[String] => Encoder[Seq[String]].apply(seq)
  }
}
