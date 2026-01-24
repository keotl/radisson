package radisson.actors.http.api.routes

import java.util.UUID

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import radisson.actors.http.api.models._
import radisson.util.JsonSupport.given

object ChatCompletionsRoutes {
  def routes: Route =
    pathPrefix("v1") {
      path("chat" / "completions") {
        post {
          entity(as[ChatCompletionRequest]) { request =>
            validateRequest(request) match
              case Left(error) =>
                complete(StatusCodes.BadRequest, error)
              case Right(_) =>
                if request.stream.contains(true) then
                  complete(
                    StatusCodes.NotImplemented,
                    ErrorResponse(
                      ErrorDetail(
                        "Streaming not yet implemented",
                        "not_implemented"
                      )
                    )
                  )
                else
                  val response = generateMockResponse(request)
                  complete(response)
          }
        }
      }
    }

  private def validateRequest(
      req: ChatCompletionRequest
  ): Either[ErrorResponse, Unit] =
    if req.messages.isEmpty then
      Left(
        ErrorResponse(
          ErrorDetail("messages cannot be empty", "invalid_request")
        )
      )
    else if req.model.isEmpty then
      Left(
        ErrorResponse(ErrorDetail("model cannot be empty", "invalid_request"))
      )
    else Right(())

  private def generateMockResponse(
      req: ChatCompletionRequest
  ): ChatCompletionResponse =
    ChatCompletionResponse(
      id = s"chatcmpl-${UUID.randomUUID().toString}",
      `object` = "chat.completion",
      created = System.currentTimeMillis() / 1000,
      model = req.model,
      choices = List(
        Choice(
          index = 0,
          message = Message(
            role = "assistant",
            content =
              "This is a mock response from Radisson. Backend integration coming soon!"
          ),
          finish_reason = Some("stop")
        )
      ),
      usage = Usage(
        prompt_tokens = estimateTokens(req.messages),
        completion_tokens = 15,
        total_tokens = estimateTokens(req.messages) + 15
      )
    )

  private def estimateTokens(messages: List[Message]): Int =
    messages.map(msg => msg.content.split("\\s+").length).sum

}
