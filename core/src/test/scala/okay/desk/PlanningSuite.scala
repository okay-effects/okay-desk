package okay.desk

import okay.desk.know.{Knowledge, Planning}
import okay.desk.exec.{Computation, Wire}

class PlanningSuite extends munit.FunSuite:
  val river = """{"vars":["f","a","b","c"],"start":[0,0,0,0],"goal":[1,1,1,1],
    |"moves":[{"flip":["f"]},{"flip":["f","a"],"if":["=","f","a"]},
    |{"flip":["f","b"],"if":["=","f","b"]},{"flip":["f","c"],"if":["=","f","c"]}],
    |"safe":[["or",["!=","a","b"],["=","f","a"]],["or",["!=","a","c"],["=","f","a"]]]}"""
    .stripMargin

  test("a supplied transition model gives a checked shortest seven-step plan"):
    val result = Planning.run(river).fold(fail(_), identity)
    assertEquals(result.status, "proved")
    assertEquals(result.moves.size, 7)
    assertEquals(result.states.head, Vector("0", "0", "0", "0"))
    assertEquals(result.states.last, Vector("1", "1", "1", "1"))
    assert(result.engine.contains("Datalog") && result.engine.contains("compiled inlay Horn"))
    assert(result.proof.nonEmpty)
    result.states.foreach { s =>
      assert(s(0) == s(1) || (s(1) != s(2) && s(1) != s(3)))
    }
    result.moves.foreach { moved => assert(moved.contains("f") && moved.size <= 2) }
    val job = Computation.Job.PlanModel(river)
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))
    assertEquals(Computation.run(job), Right(Computation.Output.PlanResult(result)))
    assert(Knowledge.authored("{\"plan\":" + river + "}").isRight)
    val readback = Planning.describe(river, false)
    assert(readback.contains("Target: f=1, a=1, b=1, c=1"))
    assert(readback.contains("2. ((a ≠ c) ∨ (f = a))"))

  test("the same tool handles integer resources and simultaneous updates without transport vocabulary"):
    val program = """{"vars":{"charge":[0,1,2,3,4]},"start":[0],"goal":[3],"safe":[],
      |"moves":[{"set":{"charge":["+","charge",2]}},{"set":{"charge":["-","charge",1]}}]}""".stripMargin
    assertEquals(Planning.run(program).toOption.get.moves.size, 3)
    val impossible = program.replace("[\"-\",\"charge\",1]", "[\"-\",\"charge\",2]")
    assertEquals(Planning.run(impossible).toOption.get.status, "unreachable")

  test("declarative subset actions preserve guards and implement both cardinality bounds"):
    val compact = river.replace("\"moves\":[{\"flip\":[\"f\"]},{\"flip\":[\"f\",\"a\"],\"if\":[\"=\",\"f\",\"a\"]},\n" +
      "{\"flip\":[\"f\",\"b\"],\"if\":[\"=\",\"f\",\"b\"]},{\"flip\":[\"f\",\"c\"],\"if\":[\"=\",\"f\",\"c\"]}]",
      """"moves":[{"flip":["f"],"choose":{"vars":["a","b","c"],"min":0,"max":1,"equal":"f"}}]""")
    assert(compact.contains("choose"))
    assertEquals(Planning.run(compact, true).toOption.get.semantics, Planning.run(river, true).toOption.get.semantics)
    assertEquals(Planning.run(compact.replace("\"max\":1", "\"max\":2")).toOption.get.moves.size, 3)
    assertEquals(Planning.run(compact.replace("\"min\":0", "\"min\":1")).toOption.get.status, "unreachable")
    assert(Planning.run(compact.replace("\"max\":1", "\"max\":9")).isLeft)
    assert(Planning.run(compact.replace("\"equal\":\"f\"", "\"equal\":\"unknown\"")).isLeft)

  test("missing guards and prohibitions change the full transition semantics even if an answer happens to match"):
    val original = Planning.run(river, includeSemantics = true).toOption.get
    val omitted = river.replace("[\"or\",[\"!=\",\"a\",\"c\"],[\"=\",\"f\",\"a\"]]", "true")
    val changed = Planning.run(omitted, includeSemantics = true).toOption.get
    assert(original.semantics != changed.semantics)
    assertEquals(changed.moves.size, 5)
    assert(Planning.run(river.replace("\"f\",\"a\"],\"if\":[\"=\",\"f\",\"a\"]",
      "\"absent\"],\"if\":[\"=\",\"f\",\"a\"]")).isLeft)

  test("oversized state spaces and unsafe initial states are rejected before search"):
    assert(Planning.run(river.replace("\"vars\":", "\"labels\":{\"f\":\"operator\"},\"vars\":")).isLeft)
    assert(Planning.run(river.replace("\"start\":[0,0,0,0]", "\"start\":[1,0,0,0]")).isLeft)
    val large = """{"vars":{"x":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15],
      |"y":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15],"z":[0,1]},
      |"start":[0,0,0],"goal":[1,1,1],"moves":[],"safe":[]}""".stripMargin
    assert(Planning.run(large).left.toOption.get.contains("256"))
