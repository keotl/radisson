package radisson.actors.completion

import org.apache.pekko.stream.scaladsl.{Flow, Framing}
import org.apache.pekko.util.ByteString

object SSEParser {
  private val MaxFrameLength = 1024 * 1024

  def flow: Flow[ByteString, String, Any] =
    Flow[ByteString]
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = MaxFrameLength,
          allowTruncation = true
        )
      )
      .map(_.utf8String.trim)
      .filter(_.startsWith("data: "))
      .map(_.stripPrefix("data: "))
      .filterNot(_ == "[DONE]")
}
