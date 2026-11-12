package okay.desk.bench

import okay.desk.know.{Formal, Knowledge}

class SemanticCorpusSuite extends munit.FunSuite:
  test("training programs include missing and contradictory premises, and remain executable"):
    val pairs = SemanticCorpus.pairs(461, 1024)
    val statuses = pairs.map { pair =>
      val program = Knowledge.authored(pair.program).toOption.get match
        case Knowledge.Step.Formal(p) => p
        case other => fail(other.toString)
      val result = Formal.run(program).fold(e => fail(pair.question + "\n" + e), identity)
      assert(result.status != "budget-exceeded")
      result.status
    }.toSet
    assertEquals(statuses, Set("proved", "underdetermined", "inconsistent"))
    assert(pairs.exists(_.question.contains("How many did it hand out?")))
    assert(pairs.exists(_.question.contains("is false then")))
