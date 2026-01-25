package radisson.actors.http

import scala.util.{Failure, Success, Try}

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Route
import radisson.actors.root.RootSupervisor
import radisson.config.ServerConfig
import radisson.util.Logging

object HttpServerActor extends Logging {
  enum Command {
    case Start(
        config: ServerConfig,
        routes: Route,
        replyTo: ActorRef[RootSupervisor.Command]
    )
    case Stop
    case BindingCompleted(
        result: Try[ServerBinding],
        replyTo: ActorRef[RootSupervisor.Command]
    )
  }

  def behavior: Behavior[Command] = Behaviors.setup { context =>
    idle()
  }

  private def idle(): Behavior[Command] = Behaviors.receive {
    (context, message) =>
      message match
        case Command.Start(config, routes, replyTo) =>
          log.info("Starting HTTP server on {}:{}", config.host, config.port)

          given system: org.apache.pekko.actor.typed.ActorSystem[?] =
            context.system

          val bindingFuture = Http()
            .newServerAt(config.host, config.port)
            .bind(routes)

          context.pipeToSelf(bindingFuture) { result =>
            Command.BindingCompleted(result, replyTo)
          }

          Behaviors.same

        case Command.BindingCompleted(result, replyTo) =>
          result match
            case Success(binding) =>
              log.info(
                "HTTP server successfully bound to {}",
                binding.localAddress
              )
              replyTo ! RootSupervisor.Command.HttpServerStarted(binding)
              active(binding)

            case Failure(cause) =>
              log.error("Failed to bind HTTP server", cause)
              replyTo ! RootSupervisor.Command.HttpServerFailed(cause)
              Behaviors.stopped

        case Command.Stop =>
          log.info("HTTP server not started yet, stopping")
          Behaviors.stopped
  }

  private def active(binding: ServerBinding): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case Command.Stop =>
          log.info("Stopping HTTP server")

          val unbindFuture = binding.unbind()

          // Wait for unbind to complete
          context.pipeToSelf(unbindFuture) { _ =>
            Command.Stop // Will be handled in stopping state
          }

          stopping()

        case Command.Start(_, _, replyTo) =>
          log.warn("HTTP server already running, ignoring Start command")
          Behaviors.same

        case Command.BindingCompleted(_, _) =>
          // Ignore duplicate binding messages
          Behaviors.same
    }

  private def stopping(): Behavior[Command] = Behaviors.receive {
    (context, message) =>
      message match
        case Command.Stop =>
          log.info("HTTP server stopped")
          Behaviors.stopped

        case _ =>
          // Ignore other messages while stopping
          Behaviors.same
  }

}
