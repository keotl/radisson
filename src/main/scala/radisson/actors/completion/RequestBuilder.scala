package radisson.actors.completion

import scala.concurrent.duration._

import io.circe.Printer
import io.circe.syntax._
import org.apache.pekko.actor.typed.ActorSystem
import radisson.actors.http.api.models.{ChatCompletionRequest, EmbeddingRequest}
import radisson.config.BackendConfig
import sttp.client4._

object RequestBuilder {

  private val printer = Printer.noSpaces.copy(dropNullValues = true)

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

    val requestToSend = endpointInfo.model match {
      case Some(backendModel) => request.copy(model = backendModel)
      case None               => request
    }

    val jsonBody = requestToSend.asJson.printWith(printer)

    requestWithHeaders
      .post(uri"${endpointInfo.requestUrl}")
      .readTimeout(endpointInfo.timeout.seconds)
      .response(asString)
      .contentType("application/json")
      .body(jsonBody)
  }

  def buildStreamingRequest(
      request: ChatCompletionRequest,
      endpointInfo: EndpointInfo
  )(using ActorSystem[?]) = {
    val requestWithHeaders = endpointInfo.headers.foldLeft(quickRequest) {
      case (req, (key, value)) =>
        req.header(key, value)
    }

    val requestToSend = endpointInfo.model match {
      case Some(backendModel) => request.copy(model = backendModel)
      case None               => request
    }

    requestWithHeaders
      .post(uri"${endpointInfo.requestUrl}")
      .readTimeout(endpointInfo.timeout.seconds)
      .contentType("application/json")
      .body(requestToSend.asJson.printWith(printer))
      .response(asStreamUnsafe(sttp.capabilities.pekko.PekkoStreams))
  }

  def buildEmbeddingEndpointInfo(
      backend: BackendConfig,
      endpoint: String,
      port: Int,
      defaultTimeout: Int
  ): EndpointInfo = {
    val headers = backend.api_key match {
      case Some(key) => Map("Authorization" -> s"Bearer $key")
      case None      => Map.empty[String, String]
    }

    val fullUrl = s"$endpoint/v1/embeddings"

    EndpointInfo(
      requestUrl = fullUrl,
      headers = headers,
      model = backend.model,
      timeout = backend.upstream_timeout.getOrElse(defaultTimeout)
    )
  }

  def buildEmbeddingRequest(
      request: EmbeddingRequest,
      endpointInfo: EndpointInfo
  ): Request[Either[String, String]] = {
    val requestWithHeaders = endpointInfo.headers.foldLeft(quickRequest) {
      case (req, (key, value)) =>
        req.header(key, value)
    }

    val requestToSend = endpointInfo.model match {
      case Some(backendModel) => request.copy(model = backendModel)
      case None               => request
    }

    val jsonBody = requestToSend.asJson.printWith(printer)

    requestWithHeaders
      .post(uri"${endpointInfo.requestUrl}")
      .readTimeout(endpointInfo.timeout.seconds)
      .response(asString)
      .contentType("application/json")
      .body(jsonBody)
  }
}
