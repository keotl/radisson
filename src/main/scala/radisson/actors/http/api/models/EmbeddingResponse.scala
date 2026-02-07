package radisson.actors.http.api.models

import io.circe.Codec

case class EmbeddingObject(
  `object`: String,
  embedding: List[Double],
  index: Int
) derives Codec.AsObject

case class EmbeddingUsage(
  prompt_tokens: Int,
  total_tokens: Int
) derives Codec.AsObject

case class EmbeddingResponse(
  `object`: String,
  data: List[EmbeddingObject],
  model: String,
  usage: EmbeddingUsage
) derives Codec.AsObject
