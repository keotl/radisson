package radisson.actors.http.api.models

import io.circe.Codec

case class Usage(
    prompt_tokens: Int,
    completion_tokens: Int,
    total_tokens: Int
) derives Codec.AsObject
