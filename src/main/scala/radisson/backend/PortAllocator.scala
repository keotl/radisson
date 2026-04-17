package radisson.backend

import java.net.{InetSocketAddress, ServerSocket}

object PortAllocator {

  private val PortRangeStart = 10000
  private val PortRangeEnd = 11000

  def allocatePort(
      usedPorts: Set[Int],
      isAvailable: Int => Boolean = isPortBindable
  ): Option[Int] =
    (PortRangeStart to PortRangeEnd).find { port =>
      !usedPorts.contains(port) && isAvailable(port)
    }

  def isPortBindable(port: Int): Boolean = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket()
      socket.setReuseAddress(true)
      socket.bind(new InetSocketAddress("127.0.0.1", port))
      true
    } catch {
      case _: java.io.IOException => false
    } finally {
      if (socket != null) {
        try socket.close()
        catch { case _: Throwable => () }
      }
    }
  }
}
