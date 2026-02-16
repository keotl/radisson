package radisson.actors.http.api.models

import io.circe.Codec

case class Usage(
    prompt_tokens: Int,
    completion_tokens: Int,
    total_tokens: Int,
    completion_tokens_details: Option[CompletionTokensDetails] = None,
    prompt_tokens_details: Option[PromptTokensDetails] = None
) derives Codec.AsObject

case class CompletionTokensDetails(
    reasoning_tokens: Option[Int] = None,
    accepted_prediction_tokens: Option[Int] = None,
    rejected_prediction_tokens: Option[Int] = None
) derives Codec.AsObject

case class PromptTokensDetails(
    cached_tokens: Option[Int] = None
) derives Codec.AsObject
