package radisson.actors.http.api

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import radisson.actors.completion.CompletionRequestDispatcher
import radisson.actors.http.api.models.{ErrorDetail, ErrorResponse}
import radisson.actors.http.api.routes.{ChatCompletionsRoutes, HealthRoutes}
import radisson.config.AppConfig
import radisson.util.JsonSupport.given
import radisson.util.Logging

object RouteBuilder extends Logging {
  def buildRoutes(
      config: AppConfig,
      dispatcher: ActorRef[CompletionRequestDispatcher.Command]
  )(using system: org.apache.pekko.actor.typed.ActorSystem[?]): Route = {
    // Exception handler for uncaught errors
    val exceptionHandler = ExceptionHandler { case ex: Exception =>
      log.error("Unhandled exception in route", ex)
      complete(
        StatusCodes.InternalServerError,
        ErrorResponse(
          ErrorDetail(
            message = "Internal server error",
            `type` = "internal_error"
          )
        )
      )
    }

    // Combine all routes with exception handling
    handleExceptions(exceptionHandler) {
      concat(
        HealthRoutes.routes,
        ChatCompletionsRoutes.routes(config, dispatcher)
      )
    }

  }
}
