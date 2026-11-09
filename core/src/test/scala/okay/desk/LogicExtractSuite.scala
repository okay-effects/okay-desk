package okay.desk

import okay.desk.calc.{Encode, Term, Ty}
import okay.desk.calc.Term.*
import okay.desk.logic.{Compile, Corpus, LogicGrammar, Ontology}
import okay.desk.skill.Extract
import okay.desk.skill.Extract.{Choice, Evidence, Reading, Span}
import okay.datalog.{Atom, Parse, Program}

/**
 * THE EXTRACTION CONTRACT'S LAWS (specs/logic.md §5.2): six tasks, one or two per family, each with the task's text,
 * a gold program and a hand-computed answer. The laws are the contract's, not a reader's: a gold reading replays to
 * its term, every copied span says its constant, a choice outside the grammar and a constant without evidence are
 * refused, an abstention stays open, and a wrong choice is named by its stage. No model is measured here.
 */
class LogicExtractSuite extends munit.FunSuite:
  final case class Task(name: String, ontology: Ontology, text: String, rules: String, goal: String)

  val family: Ontology = Corpus.family

  val numbers: Ontology = Corpus.numbers

  val plan: Ontology = Corpus.plan

  val data: Ontology = Corpus.data

  val reach = "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z)."
  val closure = "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y)."

  val tasks: Vector[Task] = Vector(
    Task("grandparent", family,
      "Alice is a parent of Bob, and Bob is a parent of Carol. Who are Alice's grandchildren?",
      "parent(alice, bob). parent(bob, carol). grandparent(X, Z) :- parent(X, Y), parent(Y, Z).",
      "grandparent(alice, X)"),
    Task("uncle", family,
      "Alice is a parent of Bob and Carol. Carol is a parent of Dave. Bob is male. Who is Dave's uncle?",
      """parent(alice, bob). parent(alice, carol). parent(carol, dave). male(bob).
        |sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.
        |uncle(X, Y) :- sibling(X, P), parent(P, Y), male(X).""".stripMargin, "uncle(X, dave)"),
    Task("reachable", plan,
      "You start at s0. From s0 you can go to s1, and from s1 to s2. Which states are reachable?",
      s"initial(s0). edge(s0, s1). edge(s1, s2). $closure", "reachable(X)"),
    Task("adult", numbers, "Ann is 30 and Bob is 17. Who is an adult?",
      "age(ann, 30). age(bob, 17). adult(X) :- age(X, N), N >= 18.", "adult(X)"),
    Task("depend", data, "Which services does web depend on?", reach, "reach(web, X)"),
    Task("no orders", data, "Which customers have no orders?",
      """has(C) :- orders(I, C, A, S, D, City).
        |noOrders(C) :- customers(C, Country, Since), not has(C).""".stripMargin, "noOrders(C)"))

  def program(text: String): Program = Parse.program(text).fold(fail(_), identity)
  def atom(text: String): Atom = Parse.atom(text).fold(fail(_), identity)
  def grammar(t: Task): LogicGrammar = LogicGrammar(t.ontology)
  def term(t: Task): Term = Compile.ofProgram(program(t.rules), atom(t.goal), t.ontology).fold(fail(_), identity)
  def gold(t: Task): Reading = Extract.gold(grammar(t), t.text, term(t)).fold(fail(_), identity)

  def spanOf(text: String, word: String): Span =
    val at = text.toLowerCase.indexOf(word.toLowerCase)
    require(at >= 0, s"the text does not say $word")
    Span(at, at + word.length, text.substring(at, at + word.length))

  def tyAt(g: LogicGrammar, r: Reading, i: Int): Ty =
    val t = Extract.replay(g, r.copy(choices = r.choices.take(i))).fold(fail(_), identity)
    g.nextHole(t).map(_._1).getOrElse(fail(s"no hole at $i"))

  test("six tasks: every gold reading replays to its term, and every copied span says its constant"):
    assertEquals(tasks.size, 6)
    var copied = 0
    for t <- tasks do
      val g = grammar(t)
      val r = gold(t)
      val tm = term(t)
      assertEquals(Extract.word(r), Encode.word(tm), t.name)
      assertEquals(Extract.replay(g, r), Right(tm), t.name)
      for c <- r.choices do c.evidence.foreach {
        case Evidence.Text(s) =>
          copied += 1
          assert(t.text.substring(s.start, s.end).equalsIgnoreCase(c.con), s"${t.name}: $s is not ${c.con}")
        case _ => ()
      }
    assert(copied > 0, "the corpus copies some constants")

  test("the heads see the hole: its type, its options, and the stage it decides"):
    val g = grammar(tasks.head)
    assertEquals(Extract.next(g, Hole(Ty.LogicT)),
      Some(Extract.Ask(Ty.LogicT, Vector("Program"), Extract.Stage.Structure)))
    val r = gold(tasks.head)
    val afterGoal = Extract.replay(g, r.copy(choices = r.choices.take(4))).fold(fail(_), identity)
    assertEquals(Extract.next(g, afterGoal).map(_.stage), Some(Extract.Stage.Structure)) // the facts: nil or cons

  test("a threshold the text does not say is the ontology's knowledge, not a copy"):
    val r = gold(tasks(3))
    assertEquals(r.choices.find(_.con == "18").flatMap(_.evidence), Some(Evidence.Known))

  test("refused: a constructor the grammar does not offer, an unknown constant, a constant without evidence, a " +
    "span that says another word, a span outside the task, a choice after the end"):
    val t = tasks.head
    val g = grammar(t)
    val r = gold(t)
    def refuses(made: Reading): Unit = assert(Extract.replay(g, made).isLeft, made.choices.map(_.con).mkString(" "))
    refuses(r.copy(choices = r.choices.updated(0, Choice("Nope"))))
    refuses(r.copy(choices = r.choices.updated(2, Choice("zed"))))
    refuses(r.copy(choices = r.choices.updated(2, Choice("alice", None))))
    refuses(r.copy(choices = r.choices.updated(2, Choice("alice", Some(Evidence.Text(spanOf(t.text, "Bob")))))))
    refuses(r.copy(choices = r.choices.updated(2, Choice("alice", Some(Evidence.Text(Span(10000, 10005, "alice")))))))
    refuses(r.copy(choices = r.choices :+ Choice("nil")))

  test("a data evidence names a real cell"):
    assert(Extract.dataHolds(Sample.tables, "orders.status", "unpaid"))
    assert(!Extract.dataHolds(Sample.tables, "orders.status", "nope"))
    assert(!Extract.dataHolds(Sample.tables, "orders.nope", "unpaid"))
    assert(!Extract.dataHolds(Sample.tables, "status", "unpaid"))

  test("abstention: a reading that stops before a value stays open and is counted abstained, not closed wrong"):
    val t = tasks.head
    val g = grammar(t)
    val r = gold(t)
    val stopped = r.copy(choices = r.choices.take(2))
    val partial = Extract.replay(g, stopped).fold(fail(_), identity)
    assert(!g.closed(partial))
    val v = Extract.verdict(g, r, stopped)
    assertEquals(v.stage, Some(Extract.Stage.Value))
    assert(v.stopped && !v.closed && !v.exact)
    val s = Extract.summarize(Vector((g, r, stopped)))
    assertEquals((s.exact, s.abstained, s.closedWrong, s.valueWrong), (0, 1, 0, 0))

  test("a wrong choice is named by its stage: the goal, a relation, a copied value"):
    val t = tasks.head
    val g = grammar(t)
    val r = gold(t)
    val goal = r.copy(choices = r.choices.updated(1, Choice("parent")))
    assertEquals(Extract.verdict(g, r, goal).stage, Some(Extract.Stage.Goal))
    assert(Extract.verdict(g, r, goal).closed)
    val factAt = r.choices.indexWhere(_.con == "cons") + 1
    assertEquals(r.choices(factAt).con, "parent")
    val relation = r.copy(choices = r.choices.updated(factAt, Choice("grandparent")))
    assertEquals(Extract.verdict(g, r, relation).stage, Some(Extract.Stage.Relation))
    assert(Extract.verdict(g, r, relation).closed)
    val value = r.copy(choices = r.choices.updated(2, Choice("bob", Some(Evidence.Text(spanOf(t.text, "Bob"))))))
    assertEquals(Extract.verdict(g, r, value).stage, Some(Extract.Stage.Value))
    assert(Extract.verdict(g, r, value).closed)
    val s = Extract.summarize(Vector((g, r, goal), (g, r, relation), (g, r, value)))
    assertEquals((s.goalWrong, s.relationWrong, s.valueWrong, s.closedWrong), (1, 1, 1, 3))

  test("span F1 measures copying: the same word known rather than read is exact with no spans"):
    val t = tasks.head
    val g = grammar(t)
    val r = gold(t)
    val known = r.copy(choices = r.choices.map(c => c.copy(evidence = c.evidence.map(_ => Evidence.Known))))
    val v = Extract.verdict(g, r, known)
    assert(v.exact)
    val s = Extract.summarize(Vector((g, r, known)))
    assertEquals((s.spanMatched, s.spanPredicted, s.spanGold), (0, 0, 5))
    assertEquals(s.spanF1, 0.0)

  test("the corpus: every gold reading exact, none closed wrong, span F1 1"):
    val s = Extract.summarize(tasks.map(t => (grammar(t), gold(t), gold(t))))
    assertEquals((s.tasks, s.exact, s.abstained, s.closedWrong), (6, 6, 0, 0))
    assertEquals(s.spanF1, 1.0)
