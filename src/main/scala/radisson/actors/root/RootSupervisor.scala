package radisson.actors.root

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import radisson.actors.backend.LlamaBackendSupervisor
import radisson.actors.completion.CompletionRequestDispatcher
import radisson.actors.http.HttpServerActor
import radisson.actors.http.api.RouteBuilder
import radisson.config.AppConfig
import radisson.util.Logging

object RootSupervisor extends Logging {
  enum Command {
    case Initialize(config: AppConfig)
    case Shutdown
    case HttpServerStarted(binding: ServerBinding)
    case HttpServerFailed(cause: Throwable)
  }

  def behavior: Behavior[Command] = Behaviors.setup { context =>
    log.info("RootSupervisor starting")

    Behaviors.receiveMessage {
      case Command.Initialize(config) =>
        log.info(
          "Initializing system with config: server={}:{}",
          config.server.host,
          config.server.port
        )

        // Spawn LlamaBackendSupervisor with supervision
        val backendSupervisor = context.spawn(
          Behaviors
            .supervise(LlamaBackendSupervisor.behavior)
            .onFailure[Exception](
              SupervisorStrategy.restart
                .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
            ),
          "backend-supervisor"
        )

        backendSupervisor ! LlamaBackendSupervisor.Command.Initialize(config)

        // Spawn CompletionRequestDispatcher with supervision
        val completionDispatcher = context.spawn(
          Behaviors
            .supervise(CompletionRequestDispatcher.behavior)
            .onFailure[Exception](
              SupervisorStrategy.restart
                .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
            ),
          "completion-dispatcher"
        )

        completionDispatcher ! CompletionRequestDispatcher.Command.Initialize(
          config,
          backendSupervisor
        )

        backendSupervisor ! LlamaBackendSupervisor.Command.RegisterDispatcher(
          completionDispatcher
        )

        given system: org.apache.pekko.actor.typed.ActorSystem[?] =
          context.system
        val routes = RouteBuilder.buildRoutes(config, completionDispatcher)

        // Spawn HttpServerActor with supervision
        val httpServer = context.spawn(
          Behaviors
            .supervise(HttpServerActor.behavior)
            .onFailure[Exception](
              SupervisorStrategy.restart
                .withLimit(maxNrOfRetries = 3, withinTimeRange = 1.minute)
            ),
          "http-server"
        )

        // Start the HTTP server
        httpServer ! HttpServerActor.Command.Start(
          config.server,
          routes,
          context.self
        )

        Behaviors.same

      case Command.HttpServerStarted(binding) =>
        log.info("HTTP server successfully bound to {}", binding.localAddress)
        Behaviors.same

      case Command.HttpServerFailed(cause) =>
        log.error("HTTP server failed to start", cause)
        Behaviors.stopped

      case Command.Shutdown =>
        log.info("Shutting down RootSupervisor")
        Behaviors.stopped
    }
  }

}
