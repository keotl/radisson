package ca.ligature.radisson.utils.serialization

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor}

object StringEnumCodec {

  /** Creates a Circe Encoder for any Scala 3 enum that serializes to the enum's
    * toString value
    */
  def encoder[E <: scala.reflect.Enum]: Encoder[E] =
    (a: E) => io.circe.Json.fromString(a.toString)

  /** Creates a Circe Decoder for any Scala 3 enum that deserializes from a
    * string. Pass the enum's .values array explicitly (e.g., MyEnum.values).
    */
  def decoder[E <: scala.reflect.Enum](values: Array[E]): Decoder[E] =
    (c: HCursor) =>
      Decoder.decodeString(c).flatMap { str =>
        values.find(_.toString == str) match {
          case Some(v) => Right(v)
          case None =>
            Left(
              DecodingFailure(
                s"No enum value matched for '$str'. Valid values: ${values.map(_.toString).mkString(", ")}",
                c.history
              )
            )
        }
      }
}
