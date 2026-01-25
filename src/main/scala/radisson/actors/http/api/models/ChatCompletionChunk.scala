package radisson.actors.http.api.models

import io.circe.Codec

case class ChatCompletionChunk(
  id: String,
  `object`: String,
  created: Long,
  model: String,
  choices: List[ChunkChoice]
) derives Codec.AsObject

case class ChunkChoice(
  index: Int,
  delta: Delta,
  finish_reason: Option[String]
) derives Codec.AsObject

case class Delta(
  role: Option[String] = None,
  content: Option[String] = None
) derives Codec.AsObject
