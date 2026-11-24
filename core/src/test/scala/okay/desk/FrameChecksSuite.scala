package okay.desk

class FrameChecksSuite extends munit.FunSuite:
  test("a count asked, rows given; for each asked, no group; an empty result"):
    assert(FrameChecks.suspicious("Сколько заказов из Львова?", Vector("table: orders", "answer: rows", "end"), None)
      .nonEmpty)
    assert(FrameChecks.suspicious("Sum of orders for each city", Vector("table: orders", "answer: sum amount", "end"),
      None).nonEmpty)
    assertEquals(FrameChecks.suspicious("Sum of orders for each city",
      Vector("table: orders", "answer: sum amount", "group: city", "end"), None), None)
    assertEquals(FrameChecks.suspicious("How many orders?", Vector("table: orders", "answer: count", "end"), None), None)
