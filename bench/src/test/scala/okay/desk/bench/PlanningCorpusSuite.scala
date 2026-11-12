package okay.desk.bench

import okay.desk.know.{Knowledge, Planning}

class PlanningCorpusSuite extends munit.FunSuite:
  test("offline planning supervision contains executable models with both reachable and unreachable goals"):
    val pairs = PlanningCorpus.pairs(779, 160)
    val results = pairs.map { pair =>
      val definition = Knowledge.authored(pair.program).toOption.collect {
        case Knowledge.Step.Plan(p) => p
      }.getOrElse(fail(pair.program))
      Planning.run(definition, true).fold(e => fail(pair.question + "\n" + e), identity)
    }
    assertEquals(results.map(_.status).toSet, Set("proved", "unreachable"))
    assert(results.forall(_.semantics.nonEmpty))
    assert(pairs.exists(_.question.contains("наедине")))
    assert(pairs.exists(_.question.contains("Поездки без груза запрещены")))
    assert(pairs.exists(_.question.contains("Счётчик")))
    assert(pairs.exists(_.question.contains("Переключатели")))
