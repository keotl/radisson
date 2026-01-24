package radisson.util

import scala.concurrent.Future

import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Printer}
import org.apache.pekko.http.scaladsl.marshalling.{
  Marshaller,
  ToEntityMarshaller
}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.unmarshalling.{
  FromEntityUnmarshaller,
  Unmarshaller
}

object JsonSupport {
  private val printer = Printer.noSpaces.copy(dropNullValues = true)

  given [T: Encoder]: ToEntityMarshaller[T] =
    Marshaller.withFixedContentType(ContentTypes.`application/json`) { value =>
      HttpEntity(
        ContentTypes.`application/json`,
        value.asJson.printWith(printer)
      )
    }

  given [T: Decoder]: FromEntityUnmarshaller[T] =
    Unmarshaller.stringUnmarshaller.flatMap { _ => _ => string =>
      decode[T](string).fold(
        error => Future.failed(error),
        value => Future.successful(value)
      )
    }
}
