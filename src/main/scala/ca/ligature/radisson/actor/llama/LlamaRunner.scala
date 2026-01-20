package ca.ligature.radisson.actor.llama

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}

class LlamaRunner(context: ActorContext[LlamaRunner.Message])
    extends AbstractBehavior[LlamaRunner.Message](context) {
  import LlamaRunner._

  override def onMessage(msg: Message): Behavior[Message] = {
    this
  }
}

object LlamaRunner {
  enum Message {
    case Noop
    // TODO - other messages  - keotl 2026-01-20
  }

  def apply(): Behavior[Message] =
    Behaviors.setup(context => new LlamaRunner(context))

}
