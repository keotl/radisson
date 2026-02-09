package radisson.actors.http.api.models

import radisson.config.BackendConfig

object OpenAIModelAssembler {
  def buildModelsResponse(backends: List[BackendConfig]): ListModelsResponse =
    ListModelsResponse(
      `object` = "list",
      data = backends
        .filter(_.`type` != "local-stub")
        .map(backendToModel)
    )

  private def backendToModel(backend: BackendConfig): Model =
    Model(
      id = backend.id,
      `object` = "model",
      owned_by = "radisson",
      permission = List.empty
    )
}
