package ca.ligature.radisson.actor.root

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}

class RootSupervisor(context: ActorContext[Nothing])
    extends AbstractBehavior[Nothing](context) {

  override def onMessage(msg: Nothing): Behavior[Nothing] = {
    this
  }
}

object RootSupervisor {
  def apply(): Behavior[Nothing] =
    Behaviors.setup(context => new RootSupervisor(context))

}
