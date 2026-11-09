package okay.desk

import okay.desk.calc.Term
import okay.desk.logic.{Compile, Corpus, LogicGrammar}
import okay.desk.skill.{LogicRead, Read, Reading}
import okay.desk.skill.Extract
import okay.desk.skill.Extract.Evidence
import okay.desk.skill.LogicRead.Parameters

/**
 * THE READING HEADS' LAWS (specs/logic.md §5.4): without a model, a synthetic head whose weights prefer the gold
 * letter at each step proves what the reader may and may not do — it reproduces the gold word exactly, every reading
 * it writes replays to a closed term, a margin above the gap stops the reading open (an abstention, not a wrong
 * answer), a head that prefers another constant closes wrong and the verdict names the value, and a threshold the
 * text does not say stays `Known`.
 */
class LogicReadSuite extends munit.FunSuite:
  def items(split: Corpus.Split): Vector[Corpus.Item] =
    Corpus.items(split, 8, seed = 7).groupBy(_.family).values.map(_.head).toVector.sortBy(_.family)

  def grammar(i: Corpus.Item): LogicGrammar = LogicGrammar(i.ontology)
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(fail(_), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(fail(_), identity)
    Compile.ofProgram(p, a, i.ontology).fold(fail(_), identity)
  def gold(i: Corpus.Item): Extract.Reading =
    Extract.gold(grammar(i), i.text, term(i)).fold(fail(_), identity)

  final class Fake(states: Vector[Array[Float]], words: Vector[Array[Float]] = Vector.empty) extends Reading:
    private var i = 0
    def answer[X](op: Read[X]): X = op match
      case Read.Prompt(_, _) => Array(0)
      case Read.States(_, at) =>
        val s = states(i)
        i += 1
        if at.size == words.size + 1 then words :+ s else Vector(s)
      case Read.Encode(_) => Array.empty[Int]
      case Read.Decode(_) => ""
      case Read.Logits(_) => Array.empty[Float]
      case Read.Note(_) => ()

  /** one-hot states at the steps, and weights that put 2 on the gold letter (or the wrong one) at its step */
  def synthetic(g: LogicGrammar, want: Extract.Reading, wrong: Map[Int, String] = Map.empty)
      : (Parameters, Vector[Array[Float]]) =
    val steps = LogicRead.steps(g, want)
    val d = steps.size
    val letters = (steps.map(_.target) ++ wrong.values).distinct
    val weights = letters.map(_ => Array.fill(d)(0f))
    for (s, i) <- steps.zipWithIndex do
      val target = wrong.getOrElse(i, s.target)
      weights(letters.indexOf(target))(i) = 2f
    val states = Vector.tabulate(d)(i => Array.tabulate(d)(j => if i == j then 1f else 0f))
    (Parameters(12, Vector.fill(d)(0f), Vector.fill(d)(1f), letters,
      weights.toVector.map(_.toVector), Vector.fill(letters.size)(0f), 0f), states)

  def read(reader: LogicRead.Reader, g: LogicGrammar, task: String, states: Vector[Array[Float]],
      layout: LogicRead.Layout = LogicRead.Layout.empty, words: Vector[Array[Float]] = Vector.empty): Extract.Reading =
    Read.run(reader.read[Read](g, task, layout), Fake(states, words))

  def closed(g: LogicGrammar, r: Extract.Reading): Boolean =
    g.closed(Extract.replay(g, r).fold(fail(_), identity))

  test("a head that prefers the gold letter at each step reads the gold word exactly"):
    for split <- Vector(Corpus.Split.Train, Corpus.Split.Test); i <- items(split) do
      val g = grammar(i)
      val want = gold(i)
      val (p, states) = synthetic(g, want)
      val got = read(LogicRead.Reader(p), g, i.text, states)
      assertEquals(Extract.word(got), Extract.word(want), s"${i.family}: ${i.text}")
      assertEquals(got, want, i.text)
      assert(closed(g, got), i.text)

  test("a margin above the gap abstains: the reading stays open and is counted abstained, not wrong"):
    val i = items(Corpus.Split.Train).head
    val g = grammar(i)
    val want = gold(i)
    val (p, states) = synthetic(g, want)
    val got = read(LogicRead.Reader(p.copy(margin = 3f)), g, i.text, states)
    assert(!closed(g, got))
    assert(Extract.word(got).size < Extract.word(want).size)
    val s = Extract.summarize(Vector((g, want, got)))
    assertEquals((s.exact, s.abstained, s.closedWrong), (0, 1, 0))

  test("a head that prefers another constant closes wrong, and the verdict names the value"):
    val copied = for
      i <- items(Corpus.Split.Train)
      k <- gold(i).choices.indices
      if gold(i).choices(k).evidence.exists { case Evidence.Text(_) => true; case _ => false }
    yield (i, k)
    val (i, k) = copied.head
    val g = grammar(i)
    val want = gold(i)
    val at = Extract.replay(g, want.copy(choices = want.choices.take(k))).fold(fail(_), identity)
    val ty = g.nextHole(at).map(_._1).getOrElse(fail("no hole"))
    val other = g.constructors(ty).find(c => Extract.copies(ty, c) && c != want.choices(k).con)
      .getOrElse(fail("no other constant"))
    val (p, states) = synthetic(g, want, Map(k -> other))
    val got = read(LogicRead.Reader(p), g, i.text, states)
    assert(closed(g, got))
    assert(Extract.replay(g, got).isRight)
    val v = Extract.verdict(g, want, got)
    assertEquals(v.stage, Some(Extract.Stage.Value))
    assert(!v.exact)
    val s = Extract.summarize(Vector((g, want, got)))
    assertEquals((s.exact, s.abstained, s.closedWrong, s.valueWrong), (0, 0, 1, 1))

  /** an item, a copied constant step, and another constant option the task says */
  def pointerCase(): (Corpus.Item, Int, String) =
    (for
      i <- items(Corpus.Split.Train)
      k <- gold(i).choices.indices
      if gold(i).choices(k).evidence.exists { case Evidence.Text(_) => true; case _ => false }
      at = Extract.replay(grammar(i), gold(i).copy(choices = gold(i).choices.take(k))).fold(fail(_), identity)
      ty = grammar(i).nextHole(at).map(_._1).getOrElse(fail("no hole"))
      other <- grammar(i).constructors(ty).find(c => Extract.copies(ty, c) && c != gold(i).choices(k).con &&
        Extract.find(i.text, c).isDefined)
    yield (i, k, other)).head

  /** a pointer with one row per copied constant: the query is the step's state, the key is the gold word's
   * state in the same prompt, and a `Known` step points at the "none" key */
  def pointed(g: LogicGrammar, want: Extract.Reading, wrong: Map[Int, String], margin: Float)
      : (LogicRead.Reader, Vector[Array[Float]], LogicRead.Layout, Vector[Array[Float]]) =
    val (p, states) = synthetic(g, want, wrong)
    val d = states.head.length
    val ws = LogicRead.wordsOf(want.task)
    assert(ws.size <= d, s"${ws.size} words, $d steps")
    val keyStates = Vector.tabulate(ws.size)(i => Array.tabulate(d)(j => if j == i then 1f else 0f))
    val rows = want.choices.indices.flatMap { i =>
      want.choices(i).evidence.map {
        case Evidence.Text(span) => (i, ws.indexWhere(w => w.from == span.start && w.until == span.end), false)
        case _                   => (i, -1, true)
      }
    }
    val query = rows.map((i, _, _) => Vector.tabulate(d)(j => if j == i then 1f else 0f)).toVector
    val key = rows.map { (_, j, _) =>
      if j < 0 then Vector.fill(d)(0f) else Vector.tabulate(d)(k => if k == j then 1f else 0f)
    }.toVector
    val none = rows.map((_, _, known) => if known then 1f else 0f).toVector
    val pointer = p.copy(pointerQuery = query, pointerKey = key, pointerNone = none, pointerMargin = margin)
    (LogicRead.Reader(pointer), states, LogicRead.Layout(ws, Vector.tabulate(ws.size)(identity)), keyStates)

  test("a pointer corrects a copied constant the linear head prefers wrongly"):
    val (i, k, other) = pointerCase()
    val g = grammar(i)
    val want = gold(i)
    val (reader, states, layout, words) = pointed(g, want, Map(k -> other), margin = 0.5f)
    val got = read(reader, g, i.text, states, layout, words)
    assertEquals(Extract.word(got), Extract.word(want), i.text)
    assert(closed(g, got), i.text)

  test("a pointer margin above the gap abstains at the constant hole"):
    val (i, k, other) = pointerCase()
    val g = grammar(i)
    val want = gold(i)
    val (reader, states, layout, words) = pointed(g, want, Map(k -> other), margin = 2f)
    val got = read(reader, g, i.text, states, layout, words)
    assert(!closed(g, got), i.text)
    assertEquals(Extract.word(got).size, k)
    val s = Extract.summarize(Vector((g, want, got)))
    assertEquals((s.exact, s.abstained, s.closedWrong), (0, 1, 0))

  test("a threshold the text does not say stays Known in the reading too"):
    val g = LogicGrammar(Corpus.numbers)
    val task = "Eve is 30. Who is an adult?"
    val program = okay.datalog.Parse.program("adult(X) :- age(X, N), N >= 18. age(eve, 30).")
      .fold(fail(_), identity)
    val goal = okay.datalog.Parse.atom("adult(X)").fold(fail(_), identity)
    val term = Compile.ofProgram(program, goal, Corpus.numbers).fold(fail(_), identity)
    val want = Extract.gold(g, task, term).fold(fail(_), identity)
    val (p, states) = synthetic(g, want)
    val got = read(LogicRead.Reader(p), g, task, states)
    assertEquals(got, want)
    assert(got.choices.exists(c => c.con == "18" && c.evidence.contains(Evidence.Known)), task)
