package radisson.util

import io.circe.syntax._
import io.circe.{Encoder, Json}

object FieldDropDetector extends Logging {

  def findDroppedFields(
      original: Json,
      reEncoded: Json,
      path: String = ""
  ): Set[String] =
    (original.asObject, reEncoded.asObject) match {
      case (Some(origObj), Some(reObj)) =>
        val reKeys = reObj.keys.toSet
        val droppedHere = origObj.keys.toSet.diff(reKeys).map { key =>
          if (path.isEmpty) key else s"$path.$key"
        }
        val nestedDropped = origObj.keys.flatMap { key =>
          reObj(key) match {
            case Some(reValue) =>
              val childPath = if (path.isEmpty) key else s"$path.$key"
              findDroppedFields(
                origObj(key).getOrElse(Json.Null),
                reValue,
                childPath
              )
            case None => Set.empty[String]
          }
        }.toSet
        droppedHere ++ nestedDropped

      case _ =>
        (original.asArray, reEncoded.asArray) match {
          case (Some(origArr), Some(reArr)) =>
            origArr
              .zip(reArr)
              .zipWithIndex
              .flatMap { case ((origElem, reElem), idx) =>
                val elemPath = if (path.isEmpty) s"[$idx]" else s"$path[$idx]"
                findDroppedFields(origElem, reElem, elemPath)
              }
              .toSet
          case _ => Set.empty
        }
    }

  def warnOnDroppedFields[T: Encoder](
      typeName: String,
      originalJson: Json,
      decoded: T
  ): Unit = {
    val reEncoded = decoded.asJson
    val dropped = findDroppedFields(originalJson, reEncoded)
    if (dropped.nonEmpty) {
      log.warn(
        "{}: unknown fields dropped: {}",
        typeName,
        dropped.mkString(", ")
      )
    }
  }
}
