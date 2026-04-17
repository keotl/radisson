package radisson.backend

import scala.concurrent.{ExecutionContext, Future}

object ProcessManager {

  def substitutePort(command: String, port: Int): String =
    command.replace("{port}", port.toString)

  def buildProcess(command: String): java.lang.ProcessBuilder = {
    val parts = command.split("\\s+").toList
    new java.lang.ProcessBuilder(parts*)
      .redirectErrorStream(false)
  }

  def startProcessAsync(
      processBuilder: java.lang.ProcessBuilder,
      stdoutHandler: String => Unit,
      stderrHandler: String => Unit
  )(implicit ec: ExecutionContext): Future[java.lang.Process] =
    Future {
      val process = processBuilder.start()
      drainStream(process.getInputStream, stdoutHandler)
      drainStream(process.getErrorStream, stderrHandler)
      process
    }

  private def drainStream(
      stream: java.io.InputStream,
      handler: String => Unit
  )(implicit ec: ExecutionContext): Unit =
    Future {
      val reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(stream)
      )
      try {
        var line = reader.readLine()
        while (line != null) {
          handler(line)
          line = reader.readLine()
        }
      } catch {
        case _: java.io.IOException => ()
      } finally {
        try reader.close()
        catch { case _: Throwable => () }
      }
    }
}
