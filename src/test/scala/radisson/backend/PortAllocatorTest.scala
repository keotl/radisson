package radisson.backend

import munit.FunSuite

class PortAllocatorTest extends FunSuite {

  private val alwaysAvailable: Int => Boolean = _ => true

  test("allocate first available port when none used") {
    val result = PortAllocator.allocatePort(Set.empty, alwaysAvailable)
    assertEquals(result, Some(10000))
  }

  test("allocate first available port skipping used ports") {
    val usedPorts = Set(10000, 10001)
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, Some(10002))
  }

  test("allocate port with gaps in used ports") {
    val usedPorts = Set(10000, 10002, 10004)
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, Some(10001))
  }

  test("allocate port near end of range") {
    val usedPorts = (10000 to 10998).toSet
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, Some(10999))
  }

  test("allocate last available port") {
    val usedPorts = (10000 to 10999).toSet
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, Some(11000))
  }

  test("return None when all ports are used") {
    val usedPorts = (10000 to 11000).toSet
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, None)
  }

  test("ignore ports outside the range") {
    val usedPorts = Set(8080, 9000, 12000, 10000)
    val result = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(result, Some(10001))
  }

  test("allocate multiple ports sequentially") {
    var usedPorts = Set.empty[Int]

    val port1 = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(port1, Some(10000))
    usedPorts = usedPorts + port1.get

    val port2 = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(port2, Some(10001))
    usedPorts = usedPorts + port2.get

    val port3 = PortAllocator.allocatePort(usedPorts, alwaysAvailable)
    assertEquals(port3, Some(10002))
  }

  test("skip ports the OS probe reports as unavailable") {
    val probe: Int => Boolean = port => port != 10000 && port != 10001
    val result = PortAllocator.allocatePort(Set.empty, probe)
    assertEquals(result, Some(10002))
  }

  test("isPortBindable returns false when port is already bound") {
    val blocker = new java.net.ServerSocket()
    try {
      blocker.setReuseAddress(false)
      blocker.bind(new java.net.InetSocketAddress("127.0.0.1", 0))
      val boundPort = blocker.getLocalPort
      assert(!PortAllocator.isPortBindable(boundPort))
    } finally {
      blocker.close()
    }
  }
}
