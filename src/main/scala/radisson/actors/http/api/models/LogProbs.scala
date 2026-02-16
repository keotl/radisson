package radisson.actors.http.api.models

import io.circe.Codec

case class LogProbs(
    content: Option[List[TokenLogProb]] = None
) derives Codec.AsObject

case class TokenLogProb(
    token: String,
    logprob: Double,
    bytes: Option[List[Int]] = None,
    top_logprobs: Option[List[TopLogProb]] = None
) derives Codec.AsObject

case class TopLogProb(
    token: String,
    logprob: Double,
    bytes: Option[List[Int]] = None
) derives Codec.AsObject
