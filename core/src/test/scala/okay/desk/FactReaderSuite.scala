package okay.desk

import okay.desk.exec.{Computation, Wire}
import okay.desk.know.{Deduction, FactReader, Knowledge}
import FactReader.{Reading, Span}

class FactReaderSuite extends munit.FunSuite:
  val sentence = "Zeno belongs to the class whale."
  val source = Knowledge.Source("web:1", "Source", "https://example.org/source", sentence, "2026-11-08")
  val read = Reading(source.id, sentence, "fact", Span(0, 4), Span(26, 31))

  test("a learned constructor and copied spans enter the same compiled inference as source rules"):
    val q = "Every whale is a mammal. Every mammal is an animal. Is Zeno an animal?"
    val plan = Deduction.plan(q, Vector(source), Vector(read)).get
    val result = Deduction.run(q, Vector(source), plan, learned = Vector(read)).toOption.get
    assertEquals(result.status, Deduction.Status.Proven)
    assertEquals(result.proof.head.evidence, Knowledge.Quote(source.id, sentence))
    val job = Computation.Job.Deduce(q, Vector(source), plan, Vector(read))
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))
    assertEquals(Computation.run(job), Right(Computation.Output.Deduced(result)))
    assertEquals(Deduction.run(q, Vector(source), plan).isLeft, true)

  test("negative facts and universal exclusions retain their explicit polarity"):
    val negative = read.copy(kind = "not-fact")
    assertEquals(FactReader.meaning(negative), Right(Deduction.Meaning.Fact(Deduction.Atom("zeno", "whale", true))))
    assertEquals(FactReader.meaning(read.copy(kind = "every-not")),
      Right(Deduction.Meaning.Every("zeno", "whale", true)))

  test("only exact collected whole sentences and disjoint word spans can be imported"):
    val bad = Vector(read.copy(source = "invented"), read.copy(quote = "Zeno belongs to the class fish."),
      read.copy(quote = "belongs to the class whale."), read.copy(a = Span(1, 4)),
      read.copy(b = Span(0, 4)), read.copy(kind = "generated-axiom"))
    for r <- bad do assert(Deduction.collected("Is Zeno an animal?", Vector(source), Vector(r)).isLeft)
    assert(Deduction.collected("Is Zeno an animal?", Vector(source), Vector(read, read)).isLeft)
    assertEquals(FactReader.words("Моби — морское млекопитающее.").map(_.text),
      Vector("Моби", "морское", "млекопитающее"))

  test("semantic relation phrases do not masquerade as canonical category names"):
    assertEquals(Deduction.meaning("Zeno is an instance of whale."), None)
    assertEquals(Deduction.meaning("The class moon is included in the class satellite."), None)
    assertEquals(Deduction.meaning("No moon is a star."), None)
    assertEquals(Deduction.meaning("Some moon is a star."), None)
    assertEquals(Deduction.meaning("Zeno is outside the class whale."), None)
    assertEquals(Deduction.meaning("Zeno is a member of the class whale."), None)
