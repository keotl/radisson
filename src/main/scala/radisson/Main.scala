package radisson

import org.apache.pekko.actor.typed.ActorSystem
import radisson.actors.root.RootSupervisor
import radisson.config.ConfigLoader
import radisson.util.Logging

object Main extends Logging {
  def main(args: Array[String]): Unit = {
    log.info("Starting Radisson LLM Proxy")

    // Parse command line arguments for config path
    val configPath = args
      .find(_.startsWith("--config="))
      .map(_.stripPrefix("--config="))
      .getOrElse("config.yaml")

    log.info("Loading configuration from: {}", configPath)

    // Load configuration
    ConfigLoader.loadConfig(configPath) match
      case Left(error) =>
        log.error("Configuration error: {}", error)
        System.err.println(s"Configuration error: $error")
        System.exit(1)

      case Right(config) =>
        log.info("Configuration loaded successfully")

        // Create actor system with RootSupervisor
        val system = ActorSystem(
          RootSupervisor.behavior,
          "radisson-system"
        )

        // Initialize the system
        system ! RootSupervisor.Command.Initialize(config)

        // Setup shutdown hook
        sys.addShutdownHook {
          log.info("Shutdown hook triggered")
          system ! RootSupervisor.Command.Shutdown
        }

        log.info("Radisson system initialized")

        // Wait for termination
        scala.concurrent.Await
          .result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)

  }

}
