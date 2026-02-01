package radisson.actors.http.api.routes

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

object HealthRoutes {
  def routes: Route =
    path("health") {
      get {
        complete(StatusCodes.OK, "OK")
      }
    } ~
      pathEndOrSingleSlash {
        get {
          complete(StatusCodes.OK, "radisson is running")
        }
      }

}
