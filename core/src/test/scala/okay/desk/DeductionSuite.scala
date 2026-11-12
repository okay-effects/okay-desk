package okay.desk

import okay.desk.know.{Deduction, Knowledge}
import Deduction.{Atom, Request, Status}
import Knowledge.{Quote, Source, Step}

class DeductionSuite extends munit.FunSuite:
  val source = Source(
    "taxonomy",
    "Taxonomy",
    "https://example.org/taxonomy",
    "Every whale is a mammal. Every mammal is an animal.",
    "2026-11-08"
  )
  val question = "Moby is a whale. Is Moby an animal?"

  def run(q: String, sources: Vector[Source] = Vector(source)): Deduction.Result =
    val request = Deduction.plan(q, sources).get
    Deduction.run(q, sources, request).fold(fail(_), identity)

  test("the same operation reasons over local premises and autonomously collected source rules"):
    val request = Deduction.plan(question, Vector(source)).get
    val result = run(question)
    assertEquals(request.goal, Atom("moby", "animal"))
    assertEquals(result.status, Status.Proven)
    assertEquals(result.proof.map(_.atom.category), Vector("whale", "mammal", "animal"))
    assertEquals(result.proof.map(_.evidence.source), Vector("input:question", "taxonomy", "taxonomy"))
    assertEquals(
      Deduction.run(question, Vector(source), request),
      Deduction.run(question, Vector(source), request, compiled = false)
    )
    assert(Knowledge.baseline(question, Vector(source)).isInstanceOf[Step.Deduce])

  test("unknown and explicit refutation differ; contradiction does not license an unrelated conclusion"):
    assertEquals(run("Moby is a whale. Is Moby a fish?").status, Status.Unknown)
    assertEquals(run("Moby is not a fish. Is Moby a fish?").status, Status.Refuted)
    assertEquals(run("Moby is not a fish. Is Moby not a fish?").status, Status.Proven)
    assertEquals(run("Moby is a fish. Moby is not a fish. Is Moby a fish?").status, Status.Conflict)
    assertEquals(run("Moby is a fish. Moby is not a fish. Is Moby a bird?").status, Status.Unknown)

  test("conjunction needs both premises and a cyclic rule does not create its own seed"):
    val rules = source.copy(text = "If Moby is a whale and Moby is a swimmer, then Moby is a diver.")
    assertEquals(run("Moby is a whale. Is Moby a diver?", Vector(rules)).status, Status.Unknown)
    assertEquals(run("Moby is a whale. Moby is a swimmer. Is Moby a diver?", Vector(rules)).status, Status.Proven)
    val cycle = source.copy(text = "Every whale is a mammal. Every mammal is a whale.")
    assertEquals(run("Is Moby a mammal?", Vector(cycle)).status, Status.Unknown)

  test("source identifiers, whole-span readings and the question goal cannot be fabricated"):
    val request = Deduction.plan(question, Vector(source)).get
    for bad <- Vector(
        request.copy(goal = Atom("moby", "fish")),
        request.copy(evidence = Vector(Quote("invented", source.text))),
        request.copy(evidence = Vector(Quote("taxonomy", "Every whale is a fish."))),
        request.copy(evidence = Vector(Quote("taxonomy", "whale is a mammal")))
      )
    do assert(Deduction.run(question, Vector(source), bad).isLeft)
    val unread = source.copy(text = "The facts are complicated and uncertain.")
    assert(Deduction.run(question, Vector(unread), Request(request.goal, Vector(Quote(unread.id, unread.text)))).isLeft)

  test("source identity and quote budgets are checked before inference"):
    val request = Deduction.plan(question, Vector(source)).get
    assert(Deduction.run(question, Vector(source, source), request).isLeft)
    assert(Deduction.run(question, Vector(source.copy(id = "input:question")), request).isLeft)
    val long = Vector.tabulate(24)(i => s"Entity$i is a very long named category.").mkString(" ")
    val sources = Vector(source.copy(text = long))
    val proposed = Deduction.plan("Is Entity0 an animal?", sources).get
    assert(Deduction.run("Is Entity0 an animal?", sources, proposed).isLeft)

  test("a recognized Russian reading uses the same compiled machine without guessing inflections"):
    val text = "Моби — кит. Каждый кит — млекопитающее. Каждое млекопитающее — животное. Моби — животное?"
    assertEquals(run(text, Vector.empty).status, Status.Proven)
    assertEquals(run("Моби — не рыба. Моби — рыба?", Vector.empty).status, Status.Refuted)

  test("a proposed deduction operation selects evidence rather than authoring facts or conclusions"):
    val json = """{"deduce":[{"source":"taxonomy","quote":"Every whale is a mammal."}]}"""
    assertEquals(Knowledge.parse(json), Right(Step.Deduce(Vector(Quote("taxonomy", "Every whale is a mammal.")))))
    assert(Knowledge.parse("""{"deduce":{"fact":"Moby is a fish"}}""").isLeft)
