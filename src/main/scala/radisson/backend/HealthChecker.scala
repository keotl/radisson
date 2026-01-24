package radisson.backend

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import sttp.client4._

object HealthChecker {

  def checkHealth(host: String, port: Int)(implicit
      backend: Backend[Future],
      ec: ExecutionContext
  ): Future[Boolean] = {
    val uri = uri"http://$host:$port/health"

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
      delay: FiniteDuration
  )(implicit
      backend: Backend[Future],
      ec: ExecutionContext
  ): Future[Boolean] = {
    def attemptCheck(attemptsRemaining: Int): Future[Boolean] =
      checkHealth(host, port).flatMap { isHealthy =>
        if (isHealthy) {
          Future.successful(true)
        } else if (attemptsRemaining > 1) {
          Future {
            Thread.sleep(delay.toMillis)
          }.flatMap { _ =>
            attemptCheck(attemptsRemaining - 1)
          }
        } else {
          Future.successful(false)
        }
      }

    attemptCheck(maxAttempts)
  }
}
