package radisson.actors.http.api.models

import io.circe.Codec

case class ErrorResponse(
    error: ErrorDetail
) derives Codec.AsObject

case class ErrorDetail(
    message: String,
    `type`: String,
    code: Option[String] = None
) derives Codec.AsObject
