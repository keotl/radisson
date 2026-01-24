package radisson.backend

import munit.FunSuite

class MemoryParserTest extends FunSuite {

  test("parse 100Mi") {
    val result = MemoryParser.parseMemoryString("100Mi")
    assertEquals(result, Right(104857600L))
  }

  test("parse 1Gi") {
    val result = MemoryParser.parseMemoryString("1Gi")
    assertEquals(result, Right(1073741824L))
  }

  test("parse 512Mi") {
    val result = MemoryParser.parseMemoryString("512Mi")
    assertEquals(result, Right(536870912L))
  }

  test("parse 2Gi") {
    val result = MemoryParser.parseMemoryString("2Gi")
    assertEquals(result, Right(2147483648L))
  }

  test("parse 1024Ki") {
    val result = MemoryParser.parseMemoryString("1024Ki")
    assertEquals(result, Right(1048576L))
  }

  test("parse bytes without unit") {
    val result = MemoryParser.parseMemoryString("1024")
    assertEquals(result, Right(1024L))
  }

  test("parse with B unit") {
    val result = MemoryParser.parseMemoryString("1024B")
    assertEquals(result, Right(1024L))
  }

  test("parse 1M (decimal)") {
    val result = MemoryParser.parseMemoryString("1M")
    assertEquals(result, Right(1000000L))
  }

  test("parse 1G (decimal)") {
    val result = MemoryParser.parseMemoryString("1G")
    assertEquals(result, Right(1000000000L))
  }

  test("parse with whitespace") {
    val result = MemoryParser.parseMemoryString("  100Mi  ")
    assertEquals(result, Right(104857600L))
  }

  test("parse decimal values") {
    val result = MemoryParser.parseMemoryString("1.5Gi")
    assertEquals(result, Right(1610612736L))
  }

  test("reject invalid format") {
    val result = MemoryParser.parseMemoryString("invalid")
    assert(result.isLeft)
  }

  test("reject empty string") {
    val result = MemoryParser.parseMemoryString("")
    assert(result.isLeft)
  }

  test("reject unknown unit") {
    val result = MemoryParser.parseMemoryString("100Zi")
    assert(result.isLeft)
  }

  test("reject negative values") {
    val result = MemoryParser.parseMemoryString("-100Mi")
    assert(result.isLeft)
  }
}
