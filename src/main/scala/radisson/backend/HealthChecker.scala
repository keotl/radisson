package radisson.backend

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

import org.apache.pekko.actor.Scheduler
import sttp.client4._

object HealthChecker {

  def checkHealth(host: String, port: Int, path: String = "/health")(implicit
      backend: Backend[Future],
      ec: ExecutionContext
  ): Future[Boolean] = {
    val healthUrl = s"http://$host:$port$path"
    val uri = uri"$healthUrl"

    quickRequest
      .get(uri)
      .readTimeout(5.seconds)
      .send(backend)
      .map { response =>
        response.code.isSuccess
      }
      .recover { case _ =>
        false
      }
  }

  def retryHealthCheck(
      host: String,
      port: Int,
      maxAttempts: Int,
      delay: FiniteDuration,
      path: String = "/health"
  )(implicit
      backend: Backend[Future],
      ec: ExecutionContext,
      scheduler: Scheduler
  ): Future[Boolean] = {
    def attemptCheck(attemptsRemaining: Int): Future[Boolean] =
      checkHealth(host, port, path).flatMap { isHealthy =>
        if (isHealthy) {
          Future.successful(true)
        } else if (attemptsRemaining > 1) {
          afterDelay(delay).flatMap { _ =>
            attemptCheck(attemptsRemaining - 1)
          }
        } else {
          Future.successful(false)
        }
      }

    attemptCheck(maxAttempts)
  }

  private def afterDelay(
      delay: FiniteDuration
  )(implicit scheduler: Scheduler, ec: ExecutionContext): Future[Unit] = {
    val promise = Promise[Unit]()
    scheduler.scheduleOnce(delay)(promise.success(()))
    promise.future
  }
}
