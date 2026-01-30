package radisson.actors.http.api.models

import io.circe.Codec

case class ListModelsResponse(
    `object`: String,
    data: List[Model]
) derives Codec.AsObject

case class Model(
    id: String,
    `object`: String,
    owned_by: String,
    permission: List[String]
) derives Codec.AsObject
