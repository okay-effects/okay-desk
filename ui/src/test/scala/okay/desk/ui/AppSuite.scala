package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Sample
import okay.http.{Fetch, Response}

/** THE DESK PLAYED AS A PERSON WOULD, on the JVM: the data screen — files opened, an example opened, a table closed —
 * then the chat: a model loaded, a question answered by a model of the test's (the sample's gold programs), the
 * result on the screen; the tables kept on the disk are there at the next visit. The same program the page runs. */
class AppSuite extends munit.FunSuite:
  type Row[X] = (Ui + Fetch + Disk + Model)[X]
  type NoModel[X] = (Ui + Fetch + Disk)[X]
  type NoDisk[X] = (Ui + Fetch)[X]
  val gold = Sample.questions.flatMap(q => List(q.en -> q.gold, q.ru -> q.gold)).toMap
  def csv(t: okay.desk.Table): String = (t.columns +: t.rows.map(_.map(_.text))).map(_.mkString(",")).mkString("\n")
  def fetched(url: String): Response = url match
    case "sample/orders.csv" => Response(200, Map.empty, csv(Sample.orders))
    case "sample/customers.csv" => Response(200, Map.empty, csv(Sample.customers))
    case "sample/edges.csv" => Response(200, Map.empty, csv(Sample.edges))
    case _ => Response(404)
  val disk = scala.collection.mutable.Map.empty[String, String]
  def play(opens: List[String], acts: Act*): Played[Unit] =
    val p = Model.answeredBy[Row, NoModel, Unit](_ => Right("readers loaded"), (_, q) => gold.get(q).toRight("no program for that"))(App.main[Row](opens))
    val kept = Disk.inMemory[NoModel, NoDisk, Unit](disk)(p)
    val byTable = handle(kept, Handler.Tail[Fetch, Ui, Unit, Unit](a => pure(a), [X] => (op: Fetch[X]) => op match
      case Fetch.Call(_, url, _, _, answer) => answer(fetched(url))
      case Fetch.Send(base, r, answer) => answer(fetched(Fetch.target(base, r)))))
    Play(steps(byTable), acts*)
  def text(p: Played[Unit]): String =
    def walk(ps: List[Part]): List[String] = ps.flatMap {
      case Part.Text(t) => List(t)
      case Part.Notice(_, t) => List(t)
      case Part.Section(title, inner) => title :: walk(inner)
      case Part.Block(_, title, inner) => title.toList ++ walk(inner)
      case Part.Code(lines, _, title) => title.toList ++ lines
      case Part.Tree(_, roots) => roots.map(_.label)
      case _ => Nil
    }
    p.at.fold("")(s => walk(s.parts).mkString("\n"))
  val bundle = Bundle.write(List("data/orders.csv" -> csv(Sample.orders), "notes.txt" -> "not a table"))
  val ask = "Ask about the data: how many orders are unpaid? total by customer? …"

  test("the data screen: files opened, an example opened, a table closed; the chat: loaded, asked, answered"):
    disk.clear()
    val p = play(Nil, Act.press("Data: open files"),
      Act.press("Open the files", "Files, or a whole folder (CSV, TSV, JSON)" -> bundle),
      Act.press("Open"),   // the first example: the sample's three files (orders again, customers, edges)
      Act.press("Close edges"), Act.press("← Back to the chat"), Act.press("Load the model"),
      Act.press("Ask", ask -> "How many orders are unpaid?"))
    assert(p.problems.isEmpty, p.problems.toString)
    val t = text(p)
    assert(t.contains("the data: customers, orders"), t)
    assert(t.contains("readers loaded"), t)
    assert(t.contains("4 rows.") || t.contains("4 записей"), s"the answer in words: $t")
    assert(t.contains("n\n-\n4") && t.contains("not generated"), t)
    assertEquals(disk.keys.toList.sorted, List("customers.csv", "orders.csv"))

  test("the next visit: the tables kept on the disk are open; a question before the model loads it, then answers"):
    disk.clear(); disk("orders.csv") = csv(Sample.orders)
    val p = play(Nil, Act.press("Ask", ask -> "How many orders are unpaid?"))
    val t = text(p)
    assert(t.contains("the data: orders"), t)
    assert(!t.contains("load the model first"), t)
    assert(t.contains("readers loaded") || t.contains("unpaid"), t)
    val missing = text(play(List("sample/nothing.csv")))
    assert(missing.contains("not read"), missing)

/** A QUESTION THOUGHT ABOUT, ITS STEPS SHOWN: the steps on the screen (the decided marked done, the last going on, no
 * question form meanwhile), and a model that thinks for two moments before its program: two working screens, then
 * the answer */
class ThinkingSuite extends munit.FunSuite:
  type Row[X] = (Ui + Model)[X]
  def words(s: Screen[?]): List[String] =
    def walk(ps: List[Part]): List[String] = ps.flatMap {
      case Part.Text(t) => List(t)
      case Part.Notice(_, t) => List(t)
      case Part.Block(_, title, inner) => title.toList ++ walk(inner)
      case _ => Nil
    }
    walk(s.parts)

  test("the steps on the screen while a question is thought about"):
    val s = App.State(tables = okay.desk.Sample.tables, model = Some("readers"))
    val w = words(App.render(s, thinking = Some(Vector("the table: orders (named in the question)",
      "the form of the answer: a count, with a condition", "choosing the condition's column among 4…"))))
    assert(w.contains("✓ the table: orders (named in the question)"), w.toString)
    assert(w.contains("✓ the form of the answer: a count, with a condition"), w.toString)
    assert(w.contains("⋯ choosing the condition's column among 4…"), w.toString)
    assert(!App.render(s, thinking = Some(Vector("x"))).parts.contains(App.questionForm),
      "the question form shown while thinking")

  test("a model that thinks two moments: two working screens, then the answer"):
    var moments = 0
    val gold = okay.desk.Sample.questions.head
    val s = App.State(tables = okay.desk.Sample.tables, model = Some("readers"))
    val p = App.think[Row](s, gold.en)
    val answered = handle(p, Handler.Tail[Model, Ui, Either[String, String], Either[String, String]](a => pure(a),
      [X] => (op: Model[X]) => op match
        case Model.Ask(_, _, _) => ()
        case Model.Thought() =>
          moments += 1
          if moments <= 2 then Left(Vector(s"step $moments")) else Right(Right(gold.gold))
        case _ => sys.error(s"not asked here: $op")))
    val played = Play(steps(answered))
    assertEquals(played.result, Some(Right(gold.gold)))
    assertEquals(played.seen, List("okay-desk", "okay-desk"))
