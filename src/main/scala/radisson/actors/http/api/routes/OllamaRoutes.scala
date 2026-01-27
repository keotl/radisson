package radisson.actors.http.api.routes

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import radisson.actors.http.api.models.OllamaModelAssembler
import radisson.config.AppConfig
import radisson.util.JsonSupport.given

object OllamaRoutes {
  def routes(config: AppConfig): Route =
    pathPrefix("api") {
      path("tags") {
        get {
          val response =
            OllamaModelAssembler.buildOllamaResponse(config.backends)
          complete(response)
        }
      }
    }
}
