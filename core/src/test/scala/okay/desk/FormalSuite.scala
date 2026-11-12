package okay.desk

import okay.desk.know.{Formal, Knowledge}
import okay.desk.exec.{Computation, Wire}

class FormalSuite extends munit.FunSuite:
  def run(s: String, budget: Int = 100000): Formal.Result = Formal.run(s, budget).fold(fail(_), identity)
  test("model programs use arbitrary variable names and exact rational arithmetic"):
    val p = """{"vars":{},"where":[],"ask":{"third":["/",1,3],"remaining":["-",["*",6,8],5]}}"""
    val r = run(p)
    assertEquals(r.status, "proved")
    assertEquals(r.answers, Map("third" -> Vector("1/3"), "remaining" -> Vector("43")))
    val job = Computation.Job.FormalModel(p)
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))
    assertEquals(Computation.run(job), Right(Computation.Output.FormalResult(r)))

  test("ordering, all-different and question projection are independent of the topic"):
    val p = """{"vars":{"quasar":[1,2,3],"amber":[1,2,3],"киви":[1,2,3]},
      |"where":[["distinct","quasar","amber","киви"],["<","quasar","amber"],["=","киви",3]],
      |"ask":{"second":"amber","first":["if",["=","quasar",1],["text","quasar"],["text","amber"]]}}
      |""".stripMargin
    assertEquals(run(p).answers, Map("second" -> Vector("2"), "first" -> Vector("quasar")))

  test("a boolean implication is proved over every assignment; unconstrained goals stay underdetermined"):
    val prefix = """{"vars":{"sensor":[false,true],"alarm":[false,true]},"where": ["""
    val suffix = """],"ask":{"alarm":"alarm"}}"""
    val proved = run(prefix + """["=","sensor",true],["implies","sensor","alarm"]""" + suffix)
    assertEquals(proved.answers("alarm"), Vector("true"))
    assertEquals(proved.status, "proved")
    assert(proved.engine.contains("compiled inlay Horn"))
    assert(proved.proof.exists(_.contains("t:alarm")))
    val open = run(prefix + """["implies","sensor","alarm"]""" + suffix)
    assertEquals(open.status, "underdetermined")
    assertEquals(open.answers("alarm"), Vector("false", "true"))

  test("contradiction and exhausted work budgets never manufacture an answer"):
    val p = """{"vars":{"x":[1,2,3]},"where":[["<","x",2],[">","x",2]],"ask":{"x":"x"}}"""
    assertEquals(run(p).status, "inconsistent")
    val limited = run("""{"vars":{"x":[1,2,3]},"where":[],"ask":{"x":"x"}}""", 2)
    assertEquals(limited.status, "budget exceeded")
    assert(limited.answers.isEmpty)

  test("invalid operations, unbound variables and wrong types are rejected"):
    for expr <- Vector("[\"javascript\",1]", "\"absent\"", "[\"+\",true,2]", "[\"/\",1,0]") do
      assert(Formal.run(s"""{"vars":{},"where":[],"ask":{"value":$expr}}""").isLeft)
    assert(Knowledge.parse("""{"formal":{"vars":{},"where":[],"ask":{"n":2}}}""")
      .exists(_.isInstanceOf[Knowledge.Step.Formal]))

  test("arity feedback states the expected and actual argument counts"):
    val error = Formal.evaluate("""["/",35,46,27,61,31]""", Map.empty).left.toOption.get
    assert(error.contains("/ expects 2 arguments, received 5"))

  test("model authoring cannot silently fall back to a natural-language constraint reader"):
    assert(Knowledge.authored("""{"constraints":"Alice is first. Who owns dog?"}""").isLeft)
    assert(Knowledge.authored("""{"deduce":[{"source":"q","quote":"A is a B."}]}""").isLeft)
    assert(Knowledge.authored("""{"search":["history of Quito"]}""").isRight)
    assert(Knowledge.authored("""{"clarify":"Which year do you mean?"}""").isRight)

  test("model-authored arithmetic steps can only reference actual exact tool receipts"):
    assertEquals(Formal.evaluate("""["*",6,8]""", Map.empty), Right("48"))
    assertEquals(Formal.evaluate("""["-","r1",5]""", Map("r1" -> "48")), Right("43"))
    assertEquals(Formal.evaluate("""["*","r1",3]""", Map("r1" -> "1/3")), Right("1"))
    assert(Formal.evaluate("\"r9\"", Map("r1" -> "48")).isLeft)
    assert(Formal.evaluate("\"r1\"", Map("r1" -> "NaN")).isLeft)
    val job = Computation.Job.Evaluate("""["-","r1",5]""", Map("r1" -> "48"))
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))
