package radisson.actors.tracing

import io.circe.{Codec, Json}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object RequestTracer {

  val MaxTraces = 50

  case class RequestTrace(
      request_id: String,
      backend_id: String,
      model: String,
      request_type: String, // "completion", "streaming", "embedding"
      status: String, // "success", "error"
      error_type: Option[String] = None,
      started_at: Long,
      completed_at: Long,
      duration_ms: Long,
      prompt_tokens: Option[Int] = None,
      completion_tokens: Option[Int] = None,
      total_tokens: Option[Int] = None,
      http_status: Option[Int] = None,
      request_body: Option[Json] = None
  ) derives Codec.AsObject

  case class TracesResponse(
      traces: List[RequestTrace],
      total_captured: Int
  ) derives Codec.AsObject

  enum Command {
    case RecordTrace(trace: RequestTrace)
    case GetTraces(limit: Int, replyTo: ActorRef[TracesResponse])
  }

  def behavior: Behavior[Command] = active(List.empty, 0)

  private def active(traces: List[RequestTrace], totalCaptured: Int): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Command.RecordTrace(trace) =>
        val updated = (trace :: traces).take(MaxTraces)
        active(updated, totalCaptured + 1)

      case Command.GetTraces(limit, replyTo) =>
        replyTo ! TracesResponse(traces.take(limit), totalCaptured)
        Behaviors.same
    }
  }
}
