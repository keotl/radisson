package radisson.backend

object MemoryParser {

  def parseMemoryString(memory: String): Either[String, Long] = {
    val pattern = """^(\d+(?:\.\d+)?)\s*(Ki|Mi|Gi|Ti|K|M|G|T|B)?$""".r

    memory.trim match {
      case pattern(amount, unit) =>
        try {
          val numericValue = amount.toDouble
          val multiplier = Option(unit).getOrElse("B") match {
            case "B"     => 1L
            case "K"     => 1000L
            case "Ki"    => 1024L
            case "M"     => 1000L * 1000L
            case "Mi"    => 1024L * 1024L
            case "G"     => 1000L * 1000L * 1000L
            case "Gi"    => 1024L * 1024L * 1024L
            case "T"     => 1000L * 1000L * 1000L * 1000L
            case "Ti"    => 1024L * 1024L * 1024L * 1024L
            case unknown => return Left(s"Unknown unit: $unknown")
          }

          val bytes = (numericValue * multiplier).toLong
          Right(bytes)
        } catch {
          case _: NumberFormatException =>
            Left(s"Invalid numeric value: $amount")
        }

      case _ =>
        Left(
          s"Invalid memory format: $memory (expected format: <number><unit>, e.g., 100Mi, 1Gi)"
        )
    }
  }
}
