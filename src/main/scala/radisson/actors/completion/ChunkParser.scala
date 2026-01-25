package radisson.actors.completion

import io.circe.parser.decode
import org.apache.pekko.stream.scaladsl.Flow
import radisson.actors.http.api.models.ChatCompletionChunk

object ChunkParser {
  def flow: Flow[String, ChatCompletionChunk, Any] =
    Flow[String]
      .map(decode[ChatCompletionChunk](_))
      .collect { case Right(chunk) => chunk }
}
