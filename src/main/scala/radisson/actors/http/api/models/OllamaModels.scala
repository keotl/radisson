package radisson.actors.http.api.models

import io.circe.Codec

case class OllamaTagsResponse(
    models: List[OllamaModel]
) derives Codec.AsObject

case class OllamaModel(
    name: String,
    model: String,
    modified_at: String,
    size: Long,
    digest: String,
    details: OllamaModelDetails
) derives Codec.AsObject

case class OllamaModelDetails(
    parent_model: String,
    format: String,
    family: String,
    families: List[String],
    parameter_size: String,
    quantization_level: String
) derives Codec.AsObject
