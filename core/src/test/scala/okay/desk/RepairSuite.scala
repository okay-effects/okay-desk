package okay.desk

import okay.desk.know.Repair

class RepairSuite extends munit.FunSuite:
  test("three unchanged invalid programs stop, including JSON formatting changes"):
    val first = Repair().failed("""["/",1,2,3]""", "bad arity").toOption.get
    val second = first.failed("""[ "/", 1, 2, 3 ]""", "bad arity").toOption.get
    assert(second.failed("""["/",1,2,3]""", "bad arity").left.toOption.get.contains("No answer was produced"))

  test("a changed program or error is a new repair attempt"):
    val first = Repair().failed("""["/",1,2,3]""", "bad arity").toOption.get
    val second = first.failed("""["/",1,2,3]""", "bad arity").toOption.get
    val changed = second.failed("""["/",1,0]""", "division by zero").toOption.get
    assertEquals(changed.attempts, 1)
    assertEquals(second.failed("""["/",1,2,3]""", "different error").toOption.get.attempts, 1)
