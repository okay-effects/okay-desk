package okay.desk

import okay.desk.calc.*
import okay.desk.Space.{Col, Link, Program}
import okay.scala3.*

/** THE CALCULUS' LAWS (specs/calculus.md): a term and its point round-trip; every point is reached by refinement
 * from one hole with a perfect source; the data alone (the exact fill) closes a share of the sample's questions,
 * never wrongly; an answer's facts are cells and its sentence reads back */
class CalculusSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = Grammar(s)
  def col(t: String, c: String): Col = s.of(t).find(_.name == c).get
  val link = Link("orders", "customer", "customers")
  /** the sample's thirteen gold programs as points of the space */
  val gold: Vector[Program] = Vector(
    Program.Rel("orders", None, Vector((col("orders", "status"), "=", Value.Str("unpaid"))), None, Some(("count", None)), None, None),
    Program.Rel("orders", None, Vector.empty, Some(col("orders", "customer")), Some(("sum", Some(col("orders", "amount")))), Some(("sum", true)), None),
    Program.Rel("orders", None, Vector((col("orders", "amount"), ">", Value.Num(100))), None, None, None, None),
    Program.Rel("orders", None, Vector.empty, None, None, Some(("amount", true)), Some(3)),
    Program.Rel("orders", None, Vector.empty, Some(col("orders", "city")), Some(("avg", Some(col("orders", "amount")))), None, None),
    Program.Rel("orders", None, Vector.empty, Some(col("orders", "status")), Some(("count", None)), None, None),
    Program.Rel("orders", Some(link), Vector((col("orders", "status"), "=", Value.Str("unpaid")), (col("customers", "country"), "=", Value.Str("UA"))), None, None, None, None),
    Program.Rel("orders", Some(link), Vector.empty, Some(col("customers", "country")), Some(("sum", Some(col("orders", "amount")))), None, None),
    Program.Rel("orders", None, Vector((col("orders", "day"), "=", Value.Str("2026-11-02"))), None, None, None, None),
    Program.Closure("edges", "web", forward = true),
    Program.Closure("edges", "orders-db", forward = false),
    Program.Rel("orders", None, Vector((col("orders", "status"), "=", Value.Str("unpaid"))), None, None, Some(("amount", true)), Some(1)),
    Program.Absent("customers", link, Vector.empty))
  val randoms: Vector[Program] = { val rnd = new scala.util.Random(21); Vector.fill(300)(Space.random(rnd, s)) }

  test("the gold points agree with the gold programs' results"):
    for (p, q) <- gold.zip(Sample.questions) do
      val got = Space.compile(p, Sample.tables).flatMap(Sample.run).toOption.get
      val want = Sample.run(Query.parse(q.gold).toOption.get).toOption.get
      assert(Sample.same(got, want), s"${q.en}: $p")

  test("a point as a term and back is the point; every term of a point is closed"):
    for p <- gold ++ randoms do
      val t = Den.term(s, p)
      assert(t.closed, t.show)
      assertEquals(Den.program(s, t), Right(p))

  test("refinement from one hole with the oracle reaches every point (read ∘ phrase = id with a perfect source)"):
    for p <- gold ++ randoms do
      val t = Den.term(s, p)
      val done = run(Refine.greedy[Pure](g, Fill.oracle(g, t), Context(s, "", Term.Hole(Ty.Program))))
      assert(done.unsure.isEmpty && g.closed(done.term), s"${p}: ${done.term.show} unsure ${done.unsure}")
      assertEquals(Den.program(s, done.term), Right(p))

  test("the data alone (the exact fill): what it closes of the sample is right; the rest stays open, never wrong"):
    var closed = 0; var right = 0; var wrong = Vector.empty[String]
    for (p, q) <- gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      val done = run(Refine.greedy[Pure](g, Fill.exact, Context(s, text, Term.Hole(Ty.Program))))
      if g.closed(done.term) then
        closed += 1
        if Den.program(s, done.term) == Right(p) then right += 1 else wrong = wrong :+ s"$text → ${done.term.show}"
      else println(s"    open: $text → ${done.term.show} ?${done.unsure.map((t, cs) => s"$t among ${cs.take(4).mkString("|")}").getOrElse("")}")
    println(s"  the exact fill alone: $closed of 26 closed, $right right; ${wrong.size} wrong")
    assertEquals(wrong, Vector.empty)

  test("answers: every fact a cell of the result, the sentence reads back, in both languages"):
    for (p, q) <- gold.zip(Sample.questions) do
      val (_, r) = Den.run(s, Den.term(s, p)).toOption.get
      val a = Answer.of(p, r)
      assert(Answer.grounded(a, r), s"${q.en}: $a")
      for ru <- Vector(false, true) do
        val sentence = Answer.text(a, ru)
        assert(Answer.readBack(sentence, a), s"${q.en}: $sentence")
    println("  e.g. " + gold.zip(Sample.questions).take(4).map((p, q) => s"${q.en} → ${Answer.text(Answer.of(p, Den.run(s, Den.term(s, p)).toOption.get._2), ru = false)}").mkString("\n       "))
