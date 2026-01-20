package ca.ligature.radisson

import ca.ligature.radisson.actor.root.RootSupervisor
import org.apache.pekko.actor.typed.ActorSystem

@main def main(): Unit = {
  ActorSystem[Nothing](RootSupervisor(), "radisson-system")
}
