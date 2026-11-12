package okay.desk.bench

import okay.desk.know.Knowledge

class ToolPlansSuite extends munit.FunSuite:
  test("tool-plan lessons expose exact intermediate results without putting the answer into the question"):
    val question = "An archive has 6 shelves of 8 scrolls and loans 5 scrolls. How many remain?"
    val model = """{"formal":{"vars":{},"where":[],"ask":{"answer":["-",["*",6,8],5]}}}"""
    val lessons = ToolPlans.compile(question, model)
    assertEquals(lessons.size, 3)
    assert(lessons.forall(_.question == question))
    assert(lessons.head.history.isEmpty)
    assertEquals(lessons(1).history.last._2, "Tool result r1 = 48")
    assertEquals(lessons.last.history.last._2, "Tool result r2 = 43")
    assertEquals(Knowledge.authored(lessons.last.program), Right(Knowledge.Step.Return("r2")))
