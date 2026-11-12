package okay.desk

import okay.desk.know.{Deduction, FactReader, Knowledge, Relations, SourceText}
import okay.desk.exec.{Computation, Wire}

class RelationsSuite extends munit.FunSuite:
  def learned(text: String, kind: String, a: String, b: String): FactReader.Reading =
    FactReader.Reading("web", text, kind,
      FactReader.Span(text.indexOf(a), text.indexOf(a) + a.length),
      FactReader.Span(text.lastIndexOf(b), text.lastIndexOf(b) + b.length))
  val first = "Harbor is part of Coast."
  val second = "Coast is part of Region."
  val rule = "If X is part of Y and Y is part of Z, then X is part of Z."
  val source = Knowledge.Source("web", "Source", "https://example.org", s"$first $second $rule", "2026-11-08")
  val reads = Vector(learned(first, "part-of", "Harbor", "Coast"), learned(second, "part-of", "Coast", "Region"))
  def result(q: String, src: Knowledge.Source = source, rs: Vector[FactReader.Reading] = reads): Deduction.Result =
    Deduction.run(q, Vector(src), Deduction.plan(q, Vector(src), rs).get, learned = rs).fold(fail(_), identity)

  test("binary composition is compiled by inlay and the proof cites the explicit rule"):
    val q = "Is Harbor part of Region?"
    val r = result(q)
    assertEquals(r.status, Deduction.Status.Proven)
    assertEquals(r.proof.last.evidence.text, rule)
    assertEquals(r.proof.last.premises.map(_.relation), Vector("part-of", "part-of"))
    val request = Deduction.plan(q, Vector(source), reads).get
    assertEquals(Deduction.run(q, Vector(source), request, learned = reads),
      Deduction.run(q, Vector(source), request, compiled = false, learned = reads))
    val without = source.copy(text = s"$first $second")
    assertEquals(result(q, without).status, Deduction.Status.Unknown)
    val job = Computation.Job.Deduce(q, Vector(source), request, reads)
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))

  test("relations are distinct; missing evidence is not negation and unrelated cycles create no facts"):
    assertEquals(result("Does Harbor border Region?").status, Deduction.Status.Unknown)
    assertEquals(result("Is Region part of Harbor?").status, Deduction.Status.Unknown)
    val negative = learned("Harbor is not located in Region.", "not-located-in", "Harbor", "Region")
    val src = source.copy(text = negative.quote)
    assertEquals(result("Is Harbor in Region?", src, Vector(negative)).status, Deduction.Status.Refuted)

  test("open questions enumerate only exact derived answers with the original evidence"):
    val quote = "Luma serves as the capital of Velora."
    val r = learned(quote, "capital-of", "Luma", "Velora")
    val out = result("What is the capital of Velora?", source.copy(text = quote), Vector(r))
    assertEquals(out.status, Deduction.Status.Proven)
    assertEquals(out.answers.map(_.subject), Vector("luma"))
    assertEquals(out.proof.head.evidence.text, quote)
    assertEquals(Relations.goal("Where is Harbor?"),
      Some(Deduction.Atom("harbor", "?", relation = "located-in")))

  test("source segmentation keeps decimals, titles and parenthetical punctuation inside the evidence"):
    val text = "Dr. Mira is in Harbor (population 2.04 million). Harbor borders Coast. Is Harbor in Coast?"
    assertEquals(SourceText.sentences(text), Vector("Dr. Mira is in Harbor (population 2.04 million).",
      "Harbor borders Coast.", "Is Harbor in Coast?"))
    assertEquals(SourceText.sentences("Pi is 3.14. Is it a number?"), Vector("Pi is 3.14.", "Is it a number?"))

  test("cross-relation rules keep their provenance and never become automatic assumptions"):
    val quote = "Luma serves as the capital of Velora."
    val r = learned(quote, "capital-of", "Luma", "Velora")
    val implication = "If X is the capital of Y, then X is located in Y."
    val src = source.copy(text = s"$quote $implication")
    assertEquals(result("Where is Luma?", src, Vector(r)).answers,
      Vector(Deduction.Atom("luma", "velora", relation = "located-in")))
    assertEquals(result("Where is Luma?", src.copy(text = quote), Vector(r)).status, Deduction.Status.Unknown)

  test("complete named arguments and HTTPS document metadata are checked"):
    val text = "South Island borders West Coast."
    val r = learned(text, "borders", "South Island", "West Coast")
    assert(FactReader.meaning(r).isRight)
    assert(FactReader.meaning(r.copy(a = FactReader.Span(0, 5))).isLeft)
    assertEquals(Deduction.goal("Is Alice a parent of Bob?").map(_.relation), Some("parent-of"))
    assertEquals(Deduction.meaning("Paris is the capital and largest city of France."), None)
    assertEquals(Deduction.meaning("Aster is a country in Central Domain."), None)
    assertEquals(Deduction.goal("https://example.org/document\nWhere is Harbor?").map(_.relation), Some("located-in"))
    assert(okay.desk.know.SourceDocument.validUrl("https://example.org/document?q=value"))
    for url <- Vector("javascript:alert(1)", "https://user:secret@example.org/file", "https://example.org/ <tag>") do
      assert(!okay.desk.know.SourceDocument.validUrl(url))
    assertEquals(Knowledge.parse("""{"open":"https://example.org/document"}"""),
      Right(Knowledge.Step.Open("https://example.org/document")))

  test("grounded binary closure equals independent reachability on random graphs, including cycles"):
    val rnd = scala.util.Random(18)
    for _ <- 0 until 30 do
      val names = Vector("Entity0", "Entity1", "Entity2", "Entity3")
      val edges = rnd.shuffle(for a <- names; b <- names yield (a, b)).take(rnd.nextInt(9))
      val statements = edges.map((a, b) => s"$a is part of $b.")
      val rs = statements.zip(edges).map((s, pair) => learned(s, "part-of", pair._1, pair._2))
      val src = source.copy(text = (statements :+ rule).mkString(" "))
      val actual = result("What is Entity0 part of?", src, rs).answers.map(_.category).toSet
      var reached = edges.collect { case ("Entity0", b) => b }.toSet
      var changed = true
      while changed do
        val next = reached ++ edges.collect { case (a, b) if reached(a) => b }
        changed = next != reached
        reached = next
      assertEquals(actual, reached.map(_.toLowerCase))

  test("unrelated, modal and historical prose cannot license a present binary fact by similarity"):
    val text = "The invasion of Poland resulted in the Holocaust."
    assert(FactReader.meaning(learned(text, "orbits", "Poland", "Holocaust")).isLeft)
    for text <- Vector("Perhaps Harbor borders Coast.", "Harbor formerly borders Coast.",
        "Someone believes Harbor borders Coast.") do
      assert(!SourceText.assertive(text))
      assert(FactReader.meaning(learned(text, "borders", "Harbor", "Coast")).isLeft)
    assert(SourceText.assertive("Harbor borders Coast."))
    assertEquals(FactReader.namedSpans("The United States of America borders Canada.").map(_._1),
      Vector(FactReader.Span(0, 28), FactReader.Span(37, 43)))

  test("binary source polarity must agree with the chosen constructor"):
    val text = "Harbor is not located in Coast."
    assert(FactReader.meaning(learned(text, "located-in", "Harbor", "Coast")).isLeft)
    assert(FactReader.meaning(learned(text, "not-located-in", "Harbor", "Coast")).isRight)
