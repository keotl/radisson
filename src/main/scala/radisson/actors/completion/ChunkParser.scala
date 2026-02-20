package radisson.actors.completion

import io.circe.parser
import org.apache.pekko.stream.scaladsl.Flow
import radisson.actors.http.api.models.ChatCompletionChunk
import radisson.util.FieldDropDetector

object ChunkParser {
  def flow: Flow[String, ChatCompletionChunk, Any] =
    Flow[String]
      .statefulMap(() => false)(
        (checked, jsonStr) => {
          val parsed = parser.parse(jsonStr)
          val decoded = parser.decode[ChatCompletionChunk](jsonStr)
          decoded match {
            case Right(chunk) =>
              if (!checked) {
                parsed.foreach { originalJson =>
                  FieldDropDetector.warnOnDroppedFields(
                    "ChatCompletionChunk",
                    originalJson,
                    chunk
                  )
                }
                (true, Some(chunk))
              } else {
                (true, Some(chunk))
              }
            case Left(_) =>
              (checked, None)
          }
        },
        _ => None
      )
      .collect { case Some(chunk) => chunk }
}
