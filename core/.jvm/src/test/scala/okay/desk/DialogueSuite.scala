package okay.desk

import okay.desk.calc.*
import okay.desk.calc.Dialogue.{Act, Outcome, Turn}
import okay.scala3.*

/** THE DIALOGUE LEVEL (specs/pilot.md §3.2): a hole no source decides is a question whose options hold the gold;
 * the answer is a source asked first, so the SAME term is refined; the acts are read before the question */
class DialogueSuite extends munit.FunSuite:
  val s = Space.Schema(Sample.tables)
  val g = new Grammar(s)
  val gold = (new CalculusSuite).gold.map(p => Den.term(s, p))
  type P[X] = Strategy.Rest[Pure][X]
  def lang(code: String): Language =
    val file = Vector(s"web/page/lang/$code.json", s"../web/page/lang/$code.json", s"../../web/page/lang/$code.json")
      .map(java.nio.file.Path.of(_)).find(java.nio.file.Files.exists(_)).getOrElse(fail(s"no lang/$code.json"))
    Language.parse(java.nio.file.Files.readString(file)).fold(e => fail(e), identity)
  val languages = Vector(Language.En.language, Language.Ru.language, lang("uk"), lang("pl"))

  /** the oracle silent on one type of hole: what the dialogue has to ask */
  def silenced(hole: Ty, gold: Term): Fill[P] = new Fill[P]:
    val oracle = Fill.oracle[P](g, gold)
    def apply(ty: Ty, cs: Vector[String], ctx: Context): Dist ! P = if ty == hole then pure(Vector.empty) else oracle(ty, cs, ctx)

  test("a hole no source decides is a question whose options hold the gold; the answer refines the same term"):
    var asked = 0; var holes = 0
    for (t, q) <- gold.zip(Sample.questions); text <- Vector(q.en, q.ru) do
      // every hole of the gold's reading with a choice in it, silenced in turn
      val (steps, _) = Strategy.greedy(g, Context(s, text, Term.Hole(Ty.Program)), Fill.oracle[Pure](g, t))
      for (hole, _) <- steps if g.constructors(hole).size >= 2 do
        holes += 1
        var answers: Dialogue.Answers = Vector.empty
        var outcome = run(Dialogue.read[Pure](g, Context(s, text, Term.Hole(Ty.Program)), Fill.first[P](Dialogue.decided[P](answers), silenced(hole, t))))
        var questions = 0
        while questions < 3 && (outcome match { case Outcome.Open(_, _, _, _) => true; case _ => false }) do
          val Outcome.Open(ty, at, cs, before) = outcome: @unchecked
          questions += 1; asked += 1
          assertEquals(ty, hole, s"$text: asked a hole the oracle decided")
          // the gold's constructor is among the options, and the question names them
          val want = run(Fill.oracle[Pure](g, t)(ty, g.constructors(ty), Context(s, text, at))).head._1
          // (a number written in the question is not among a column's values: a value hole takes it typed)
          assert(cs.contains(want) || ty.isInstanceOf[Ty.ValueT], s"$text: the options $cs lack the gold's $want")
          val question = Dialogue.question(ty, cs, if text == q.ru then Language.Ru.language else Language.En.language)
          assert(cs.forall(c => question.contains(Dialogue.name(ty, c, Language.En.language)) || c == "none"), question)
          // the answer as a source, first: the same steps up to the hole, then the gold
          answers = answers :+ (ty, want)
          outcome = run(Dialogue.read[Pure](g, Context(s, text, Term.Hole(Ty.Program)), Fill.first[P](Dialogue.decided[P](answers), silenced(hole, t))))
          outcome match
            case Outcome.Closed(_) => ()
            case Outcome.Open(_, _, _, after) => assertEquals(after.take(before.size - 1), before.init, s"$text: the term changed before the hole")
        assertEquals(outcome, Outcome.Closed(t), s"$text with $hole silenced")
    println(s"  the dialogue: $holes holes silenced over ${gold.size * 2} readings, $asked questions, every one closed to the gold")
    assert(asked >= holes)

  test("the acts, in four languages: yes, no, thanks, a correction — and a question is a question"):
    val cases = Vector(
      "yes" -> Act.Yes, "да" -> Act.Yes, "так" -> Act.Yes, "tak" -> Act.Yes, "ok" -> Act.Yes,
      "no" -> Act.No, "нет" -> Act.No, "ні" -> Act.No, "nie" -> Act.No,
      "thanks!" -> Act.Thanks, "спасибо" -> Act.Thanks, "дякую" -> Act.Thanks, "dzięki" -> Act.Thanks,
      "no, by city" -> Act.Correction("by city"), "не так: по городам" -> Act.Correction("по городам"),
      "I mean customers" -> Act.Correction("customers"), "nie tak, według miasta" -> Act.Correction("według miasta"),
      "how many orders are unpaid?" -> Act.Question, "no orders in Lviv?" -> Act.Question, "нет ли заказов без оплаты" -> Act.Question,
      "Сколько строк в таблице?" -> Act.Question)
    for (m, want) <- cases do assertEquals(Act.of(m, languages), want, m)

  test("the turn decided: yes takes the first option, no the second, a correction naming an option answers, else joins the question"):
    val pending = Some((Ty.TableT, Vector("orders", "customers")))
    val st = Turn.State(Some("how many are unpaid?"), pending, Vector.empty)
    assertEquals(Turn.decide(st, "yes", languages), Turn.Action.Answer("how many are unpaid?", Vector((Ty.TableT, "orders"))))
    assertEquals(Turn.decide(st, "нет", languages), Turn.Action.Answer("how many are unpaid?", Vector((Ty.TableT, "customers"))))
    assertEquals(Turn.decide(st, "I mean customers", languages), Turn.Action.Answer("how many are unpaid?", Vector((Ty.TableT, "customers"))))
    assertEquals(Turn.decide(st, "не так: в Киеве", languages), Turn.Action.Correct("how many are unpaid — в Киеве", Vector.empty))
    assertEquals(Turn.decide(st, "thanks", languages), Turn.Action.Acknowledge)
    assertEquals(Turn.decide(st, "total amount per city", languages), Turn.Action.Read("total amount per city"))
    assertEquals(Turn.decide(Turn.State(None, None, Vector.empty), "yes", languages), Turn.Action.Nothing)
    assertEquals(Turn.chosen(st, "orders"), Some(Turn.Action.Answer("how many are unpaid?", Vector((Ty.TableT, "orders")))))
