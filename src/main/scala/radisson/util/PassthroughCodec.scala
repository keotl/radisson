package radisson.util

import io.circe.{Codec, Decoder, Encoder, Json, JsonObject}

/** Makes a case-class codec lossless for fields the case class doesn't model.
  *
  * The proxy decodes requests into typed models to validate and route them,
  * then re-encodes them for the backend. With a plain derived codec, any field
  * not declared on the case class (e.g. llama.cpp's `chat_template_kwargs`,
  * `top_k`, `min_p`, `response_format`) is silently dropped on the way through.
  * This wrapper captures such fields on decode and writes them back on encode,
  * so backends receive everything the client sent.
  *
  * The case class must declare a `passthrough: JsonObject = JsonObject.empty`
  * field; it never appears on the wire under that name.
  */
object PassthroughCodec {
  val Key = "passthrough"

  def apply[T](
      derived: Codec.AsObject[T],
      get: T => JsonObject,
      set: (T, JsonObject) => T
  ): Codec.AsObject[T] = {
    val decoder: Decoder[T] = Decoder.instance { cursor =>
      cursor.value.asObject match {
        case None      => derived(cursor)
        case Some(obj) =>
          // Strip any client-sent field clashing with our internal name so
          // the derived decoder doesn't interpret it; it is kept as an extra.
          derived
            .decodeJson(Json.fromJsonObject(obj.remove(Key)))
            .map { value =>
              val known = derived.encodeObject(value).keys.toSet - Key
              set(value, obj.filterKeys(k => !known.contains(k)))
            }
      }
    }

    val encoder: Encoder.AsObject[T] = Encoder.AsObject.instance { value =>
      val base = derived.encodeObject(value).remove(Key)
      // Modelled fields always win over passthrough values.
      get(value).toIterable.foldLeft(base) { case (acc, (k, v)) =>
        if (acc.contains(k)) acc else acc.add(k, v)
      }
    }

    Codec.AsObject.from(decoder, encoder)
  }
}
