package radisson

import org.apache.pekko.actor.typed.ActorSystem
import radisson.actors.root.RootSupervisor
import radisson.config.ConfigLoader
import radisson.util.Logging

object Main extends Logging {
  val version = BuildInfo.version

  def main(args: Array[String]): Unit = {
    if (args.contains("--help") || args.contains("-h")) {
      printHelp()
      System.exit(0)
    }

    if (args.contains("--version") || args.contains("-v")) {
      println(s"radisson v$version")
      System.exit(0)
    }

    log.info(s"Starting radisson ${version}")

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

        log.info("radisson system initialized")

        scala.concurrent.Await
          .result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)

  }

  private def printHelp(): Unit =
    println(s"""radisson v$version
      |
      |Usage: radisson [OPTIONS]
      |
      |Options:
      |  --config=<path>    Path to configuration file (default: config.yaml)
      |  -h, --help         Show this help message
      |  -v, --version      Show version information
      |
      |Examples:
      |  radisson                        Run with default config
      |  radisson --config=prod.yaml     Run with custom config
      |  radisson --version              Show version
      |""".stripMargin)

}
