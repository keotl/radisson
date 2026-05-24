package radisson.actors.backend

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import radisson.backend.{HealthChecker, ProcessManager}
import radisson.util.Logging
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend

object LlamaBackendRunner extends Logging {

  enum Command {
    case Start(
        backendId: String,
        command: String,
        port: Int,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        upstreamUrl: Option[String] = None,
        startupTimeout: Option[Int] = None,
        stopCommand: Option[String] = None
    )
    case Stop
    case ForceKill
    case GetStatus(replyTo: ActorRef[StatusResponse])
    case ProcessStarted(process: java.lang.Process)
    case ProcessFailed(cause: Throwable)
    case ProcessExited(exitCode: Int)
    case StopCommandCompleted(exitCode: Int)
    case HealthCheckResult(isHealthy: Boolean)
  }

  enum StatusResponse {
    case Idle
    case Starting
    case Running(port: Int)
    case Stopping
    case Stopped
    case Failed(reason: String)
  }

  enum State {
    case Idle
    case Starting(
        backendId: String,
        command: String,
        port: Int,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        upstreamUrl: Option[String] = None,
        startupTimeout: Option[Int] = None
    )
    case Running(
        backendId: String,
        port: Int,
        process: java.lang.Process,
        replyTo: ActorRef[LlamaBackendSupervisor.Command]
    )
    case Stopping(process: java.lang.Process)
    case Stopped
    case Failed(reason: String)
  }

  def behavior: Behavior[Command] = Behaviors.setup { context =>
    given ec: scala.concurrent.ExecutionContext = context.executionContext
    given scheduler: org.apache.pekko.actor.Scheduler =
      context.system.classicSystem.scheduler
    given sttpBackend: Backend[scala.concurrent.Future] =
      HttpClientFutureBackend()

    def idle(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.Start(
            backendId,
            command,
            port,
            replyTo,
            upstreamUrl,
            startupTimeout,
            stopCommand
          ) =>
        log.info("Starting backend {} on port {}", backendId, port)

        val substitutedCommand = ProcessManager.substitutePort(command, port)

        val processBuilder = ProcessManager.buildProcess(substitutedCommand)
        val processFuture =
          ProcessManager.startProcessAsync(
            processBuilder,
            stdout => log.debug("[{}] {}", backendId, stdout),
            stderr => log.warn("[{}] {}", backendId, stderr)
          )

        context.pipeToSelf(processFuture) {
          case Success(process) => Command.ProcessStarted(process)
          case Failure(cause)   => Command.ProcessFailed(cause)
        }

        starting(
          backendId,
          command,
          port,
          replyTo,
          upstreamUrl,
          startupTimeout,
          stopCommand
        )

      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Idle
        Behaviors.same

      case _ =>
        log.warn("Unexpected message in idle state")
        Behaviors.same
    }

    def starting(
        backendId: String,
        command: String,
        port: Int,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        upstreamUrl: Option[String],
        startupTimeout: Option[Int],
        stopCommand: Option[String]
    ): Behavior[Command] = Behaviors.receiveMessage {
      case Command.ProcessStarted(process) =>
        log.info("Backend {} process started, checking health", backendId)

        context.pipeToSelf(scala.concurrent.Future {
          process.waitFor()
        }) { exitCode =>
          Command.ProcessExited(exitCode.getOrElse(-1))
        }

        val (healthHost, healthPort, healthPath) = upstreamUrl match {
          case Some(baseUrl) =>
            val healthUrl = s"$baseUrl/health"
            val uri = new java.net.URI(healthUrl)
            val host = uri.getHost
            val port =
              if (uri.getPort != -1) uri.getPort
              else {
                if (uri.getScheme == "https") 443 else 80
              }
            val path = uri.getPath
            (host, port, path)
          case None =>
            ("127.0.0.1", port, "/health")
        }

        val delay = 5.seconds
        val defaultTimeout = 100 // 20 attempts * 5s = 100s
        val effectiveTimeout = startupTimeout.getOrElse(defaultTimeout)
        val maxAttempts = (effectiveTimeout / delay.toSeconds).toInt.max(1)

        log.info(
          "Backend {} health check: maxAttempts={}, delay={}, timeout={}s",
          backendId,
          maxAttempts,
          delay,
          effectiveTimeout
        )

        val healthFuture = HealthChecker.retryHealthCheck(
          host = healthHost,
          port = healthPort,
          maxAttempts = maxAttempts,
          delay = delay,
          path = healthPath
        )

        context.pipeToSelf(healthFuture) { isHealthy =>
          Command.HealthCheckResult(isHealthy.getOrElse(false))
        }

        waitingForHealth(backendId, port, process, replyTo, stopCommand)

      case Command.ProcessFailed(cause) =>
        log.error("Failed to start backend {} process", backendId, cause)
        replyTo ! LlamaBackendSupervisor.Command.BackendFailed(
          backendId,
          s"Process failed to start: ${cause.getMessage}"
        )
        failed(s"Process failed to start: ${cause.getMessage}")

      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Starting
        Behaviors.same

      case Command.Stop =>
        log.info("Stop requested while starting backend {}", backendId)
        stopped()

      case _ =>
        log.warn("Unexpected message in starting state")
        Behaviors.same
    }

    def waitingForHealth(
        backendId: String,
        port: Int,
        process: java.lang.Process,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        stopCommand: Option[String]
    ): Behavior[Command] = Behaviors.receiveMessage {
      case Command.HealthCheckResult(isHealthy) =>
        if (isHealthy) {
          log.info(
            "Backend {} is healthy and running on port {}",
            backendId,
            port
          )
          replyTo ! LlamaBackendSupervisor.Command.BackendStarted(
            backendId,
            port,
            context.self
          )
          running(backendId, port, process, replyTo, stopCommand)
        } else {
          log.error("Backend {} health check failed after retries", backendId)
          Try(process.destroy())
          replyTo ! LlamaBackendSupervisor.Command.BackendFailed(
            backendId,
            "Health check failed"
          )
          failed("Health check failed")
        }

      case Command.ProcessExited(exitCode) =>
        log.error(
          "Backend {} process exited unexpectedly with code {}",
          backendId,
          exitCode
        )
        replyTo ! LlamaBackendSupervisor.Command.BackendFailed(
          backendId,
          s"Process exited with code $exitCode"
        )
        failed(s"Process exited with code $exitCode")

      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Starting
        Behaviors.same

      case Command.Stop =>
        log.info(
          "Stop requested while waiting for health check for backend {}",
          backendId
        )
        Try(process.destroy())
        stopped()

      case _ =>
        log.warn("Unexpected message in waitingForHealth state")
        Behaviors.same
    }

    def running(
        backendId: String,
        port: Int,
        process: java.lang.Process,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        stopCommand: Option[String]
    ): Behavior[Command] = Behaviors.receiveMessage {
      case Command.ProcessExited(exitCode) =>
        log.error(
          "Backend {} process exited unexpectedly with code {}",
          backendId,
          exitCode
        )
        replyTo ! LlamaBackendSupervisor.Command.BackendStopped(backendId)
        failed(s"Process exited with code $exitCode")

      case Command.Stop =>
        log.info("Stopping backend {}", backendId)
        process.destroy()

        val stopCommandPending = stopCommand match {
          case Some(cmd) =>
            log.info("Backend {} running stop_command: {}", backendId, cmd)
            val pb = ProcessManager.buildProcess(cmd)
            val stopFuture = ProcessManager
              .startProcessAsync(
                pb,
                stdout => log.debug("[{} stop_command] {}", backendId, stdout),
                stderr => log.warn("[{} stop_command] {}", backendId, stderr)
              )
              .flatMap(p => scala.concurrent.Future(p.waitFor()))
            context.pipeToSelf(stopFuture) { result =>
              Command.StopCommandCompleted(result.getOrElse(-1))
            }
            true
          case None =>
            false
        }

        context.scheduleOnce(
          30.seconds,
          context.self,
          Command.ForceKill
        )
        stopping(
          backendId,
          process,
          replyTo,
          processExited = false,
          stopCommandPending = stopCommandPending
        )

      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Running(port)
        Behaviors.same

      case _ =>
        log.warn("Unexpected message in running state")
        Behaviors.same
    }

    def stopping(
        backendId: String,
        process: java.lang.Process,
        replyTo: ActorRef[LlamaBackendSupervisor.Command],
        processExited: Boolean,
        stopCommandPending: Boolean
    ): Behavior[Command] = {
      def finishIfReady(
          nextProcessExited: Boolean,
          nextStopPending: Boolean
      ): Behavior[Command] =
        if (nextProcessExited && !nextStopPending) {
          replyTo ! LlamaBackendSupervisor.Command.BackendStopped(backendId)
          stopped()
        } else {
          stopping(
            backendId,
            process,
            replyTo,
            processExited = nextProcessExited,
            stopCommandPending = nextStopPending
          )
        }

      Behaviors.receiveMessage {
        case Command.ProcessExited(_) =>
          log.info("Backend {} process stopped", backendId)
          finishIfReady(nextProcessExited = true, nextStopPending = stopCommandPending)

        case Command.StopCommandCompleted(exitCode) =>
          if (exitCode == 0) {
            log.info("Backend {} stop_command completed", backendId)
          } else {
            log.warn(
              "Backend {} stop_command exited with code {}",
              backendId,
              exitCode
            )
          }
          finishIfReady(nextProcessExited = processExited, nextStopPending = false)

        case Command.ForceKill =>
          if (process.isAlive()) {
            log.warn(
              "Backend {} did not exit 30s after SIGTERM; sending SIGKILL",
              backendId
            )
            process.destroyForcibly()
          }
          replyTo ! LlamaBackendSupervisor.Command.BackendStopped(backendId)
          stopped()

        case Command.GetStatus(replyTo) =>
          replyTo ! StatusResponse.Stopping
          Behaviors.same

        case _ =>
          log.warn("Unexpected message in stopping state")
          Behaviors.same
      }
    }

    def stopped(): Behavior[Command] = Behaviors.receiveMessage {
      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Stopped
        Behaviors.same

      case _ =>
        log.warn("Unexpected message in stopped state")
        Behaviors.same
    }

    def failed(reason: String): Behavior[Command] = Behaviors.receiveMessage {
      case Command.GetStatus(replyTo) =>
        replyTo ! StatusResponse.Failed(reason)
        Behaviors.same

      case _ =>
        log.warn("Unexpected message in failed state")
        Behaviors.same
    }

    idle()
  }
}
