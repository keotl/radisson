package radisson.backend

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process._

object ProcessManager {

  def substitutePort(command: String, port: Int): String =
    command.replace("{port}", port.toString)

  def buildProcess(command: String): ProcessBuilder =
    Process(command.split("\\s+").toSeq)

  def startProcessAsync(
      processBuilder: ProcessBuilder,
      logger: ProcessLogger
  )(implicit ec: ExecutionContext): Future[Process] =
    Future {
      processBuilder.run(logger)
    }
}
