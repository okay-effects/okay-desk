package okay.desk.tasks

import okay.desk.statement.{Arg, Budget, Compile, Statement, Writing}

/**
 * THE GOLD STATEMENTS OF THE COLLECTION, CHECKED (specs/formalization.md §3, §7 step 2), with no model anywhere: a
 * task written together with its statement has, in both languages, a statement that is of its text (every piece
 * copied from its own sentence), that says the same frames in English and in Russian, and whose COMPILED program
 * gives the task's exact result on the solver — the denotation law of the statement language.
 */
class StatedSuite extends munit.FunSuite:
  lazy val tasks: Vector[Task] = Tasks.entries.map(_.task)
  lazy val stated: Vector[(Task, Boolean, Writing.Stated)] =
    for t <- tasks; read <- t.stated.toVector; ru <- Vector(false, true) yield (t, ru, read.in(ru))
  def label(t: Task, ru: Boolean): String = s"${t.title.en} (${if ru then "ru" else "en"})"

  /** the solver's result with every thing under the collection's key, alternatives in order */
  def keyed(result: Vector[String], keys: Vector[(String, String)]): Vector[String] =
    def key(name: String): String =
      keys.collectFirst { case (words, k) if Statement.same(words, name) => k.drop(k.indexOf('/') + 1) }.getOrElse(name)
    result.map(_.split('|').toVector.map(key).sorted.mkString("|"))

  test("a statement is of its task's text: the same sentences, every piece copied from its own sentence"):
    assert(stated.nonEmpty)
    stated.foreach { (t, ru, s) =>
      assertEquals(s.text, t.text.in(ru), label(t, ru))
      assertEquals(s.statement.check(s.text), Right(()), label(t, ru)) }

  test("a task says the same in both languages: the same constructors of the same things"):
    def shape(s: Writing.Stated): Vector[String] = s.statement.frames.map(f => s"${f.con}(" + f.args.map {
      case Arg.Mention(_, of, denied) => (if denied then "not " else "") + keyed(Vector(of.in(s.text)), s.keys).head
      case Arg.Number(at) => at.in(s.text)
      case Arg.Pick(i) => s"#$i" }.mkString(", ") + ")")
    tasks.flatMap(_.stated).foreach(read => assertEquals(shape(read.en), shape(read.ru)))

  test("the compiled statement gives the task's exact result on its solver"):
    val failed = stated.flatMap { (t, ru, s) =>
      val got = Compile.of(s.statement, s.text).flatMap(gold => gold.run(s.text).map(keyed(_, s.keys)).map(found =>
        if gold.isInstanceOf[Gold.Rules] then found.sorted else found))
      Option.when(got != Right(t.expect))(s"${label(t, ru)}: expected ${t.expect.mkString("; ")}, got $got") }
    assert(failed.isEmpty, failed.mkString("\n"))

  test("no two things of a task share a name, and the data never links a name to another thing"):
    stated.foreach { (t, ru, s) =>
      val names = s.statement.things.map(_.in(s.text).toLowerCase)
      assertEquals(names.distinct, names, label(t, ru))
      s.statement.mentions.foreach(m => Budget.linked(s.statement, s.text, m.at).foreach(thing =>
        assertEquals(thing, m.of, s"${label(t, ru)}: «${m.at.in(s.text)}»"))) }

  test("what of the collection is stated: every task of the classes the language says"):
    val stated = Coverage.rows(tasks).filterNot(_.russian).map(r => r.kind -> (r.stated, r.tasks)).filter(_._2._1 > 0)
    assertEquals(stated, Vector(Kind.Einstein -> (33, 33), Kind.Places -> (22, 22), Kind.Matching -> (16, 16),
      Kind.Order -> (20, 20), Kind.Deduction -> (15, 15), Kind.Kinship -> (4, 12), Kind.Routes -> (16, 17)))
    // a statement is a few tens of local decisions, and the data settles a part of what a name names
    Coverage.rows(tasks).filter(_.stated > 0).foreach { r =>
      assert(r.decisions.forall(d => d > 0 && d <= 150), r.kind)
      assert(r.budgets.map(_.free).sum > 0, r.kind) }

  test("one decision wrong, over a class: the exact side stops a part, and the estimate of the gate adds up"):
    import okay.desk.statement.Faults.Verdict
    val order = Checked.tally(tasks.filter(_.kind == Kind.Order).flatMap(Checked.faults).map(_._2))
    assert(order.all > 500, order.all)
    Vector(Verdict.Refused, Verdict.Signalled, Verdict.Harmless, Verdict.Silent).foreach(v =>
      assert(order.share(v) > 5, v))
    // a reader that is never wrong is always right; a weaker one is right less often, and the rest is asked or wrong
    assertEquals(Checked.gate(1.0, 20, order), (1.0, 0.0, 0.0))
    val (right, asked, wrong) = Checked.gate(0.98, 20, order)
    assertEqualsDouble(right + asked + wrong, 1.0, 1e-9)
    assert(right > 0.7 && asked > wrong && wrong > 0, (right, asked, wrong))
