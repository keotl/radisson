package radisson.actors.completion

import org.apache.pekko.stream.scaladsl.Flow
import radisson.actors.http.api.models.ChatCompletionChunk
import io.circe.parser.decode

object ChunkParser {
  def flow: Flow[String, ChatCompletionChunk, Any] = {
    Flow[String]
      .map(decode[ChatCompletionChunk](_))
      .collect { case Right(chunk) => chunk }
  }
}
