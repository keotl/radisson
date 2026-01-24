package radisson.backend

object PortAllocator {

  private val PortRangeStart = 10000
  private val PortRangeEnd = 11000

  def allocatePort(usedPorts: Set[Int]): Option[Int] =
    (PortRangeStart to PortRangeEnd).find(port => !usedPorts.contains(port))
}
