package radisson.actors.completion

import radisson.actors.http.api.models.ChatCompletionRequest
import radisson.config.BackendConfig
import sttp.client4._
import scala.concurrent.duration._
import org.apache.pekko.actor.typed.ActorSystem
import io.circe.syntax._

object RequestBuilder {

  case class EndpointInfo(
      requestUrl: String,
      headers: Map[String, String],
      model: Option[String],
      timeout: Int
  )

  def buildEndpointInfo(
      backend: BackendConfig,
      endpoint: String,
      port: Int,
      defaultTimeout: Int
  ): EndpointInfo = {
    val headers = backend.api_key match {
      case Some(key) => Map("Authorization" -> s"Bearer $key")
      case None      => Map.empty[String, String]
    }

    val fullUrl = if (backend.`type` == "remote") {
      s"$endpoint/chat/completions"
    } else {
      s"$endpoint/v1/chat/completions"
    }

    EndpointInfo(
      requestUrl = fullUrl,
      headers = headers,
      model = backend.model,
      timeout = backend.upstream_timeout.getOrElse(defaultTimeout)
    )
  }

  def buildRequest(
      request: ChatCompletionRequest,
      endpointInfo: EndpointInfo
  ): Request[Either[String, String]] = {
    val requestWithHeaders = endpointInfo.headers.foldLeft(quickRequest) {
      case (req, (key, value)) =>
        req.header(key, value)
    }

    requestWithHeaders
      .post(uri"${endpointInfo.requestUrl}")
      .readTimeout(endpointInfo.timeout.seconds)
      .response(asString)
      .body(request.asJson.noSpaces)
  }

  def buildStreamingRequest(
      request: ChatCompletionRequest,
      endpointInfo: EndpointInfo
  )(using ActorSystem[?]) = {
    val requestWithHeaders = endpointInfo.headers.foldLeft(quickRequest) {
      case (req, (key, value)) =>
        req.header(key, value)
    }

    requestWithHeaders
      .post(uri"${endpointInfo.requestUrl}")
      .readTimeout(endpointInfo.timeout.seconds)
      .body(request.asJson.noSpaces)
      .response(asStreamUnsafe(sttp.capabilities.pekko.PekkoStreams))
  }
}
