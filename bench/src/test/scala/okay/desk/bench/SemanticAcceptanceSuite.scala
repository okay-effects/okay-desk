package okay.desk.bench

import okay.desk.know.Formal

class SemanticAcceptanceSuite extends munit.FunSuite:
  test("every mutation changes the gold consequence, including ambiguity and inconsistency"):
    for group <- SemanticAcceptance.groups do
      val results = group.tasks.map(t => Formal.run(t.program).toOption.get)
      assertEquals(results.size, 2)
      assert(!SemanticAcceptance.agrees(SemanticExecution.Answer(results.head.status, results.head.answers),
        results.last), group.name)
    val statuses = SemanticAcceptance.groups.flatMap(_.tasks).map(t => Formal.run(t.program).toOption.get.status)
    assert(statuses.contains("underdetermined"))
    assert(statuses.contains("inconsistent"))

  test("a correct scalar cannot pass the ambiguous or contradictory version"):
    val ambiguous = Formal.run(SemanticAcceptance.groups(3).tasks(1).program).toOption.get
    assert(!SemanticAcceptance.agrees(SemanticExecution.Answer("proved", Map("answer" -> Vector("4"))), ambiguous))
    val contradictory = Formal.run(SemanticAcceptance.groups(5).tasks(1).program).toOption.get
    assert(!SemanticAcceptance.agrees(SemanticExecution.Answer("proved", Map("answer" -> Vector("false"))),
      contradictory))
