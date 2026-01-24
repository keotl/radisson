package radisson.actors.http.api.models

import io.circe.Codec

case class ChatCompletionRequest(
    model: String,
    messages: List[Message],
    temperature: Option[Double] = None,
    max_tokens: Option[Int] = None,
    stream: Option[Boolean] = None,
    top_p: Option[Double] = None,
    n: Option[Int] = None,
    stop: Option[List[String]] = None,
    presence_penalty: Option[Double] = None,
    frequency_penalty: Option[Double] = None,
    user: Option[String] = None
) derives Codec.AsObject
