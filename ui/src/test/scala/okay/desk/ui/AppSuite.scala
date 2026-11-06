package okay.desk.ui

import okay.business.scala3.*
import okay.desk.Sample
import okay.http.{Fetch, Response}

/** THE DESK PLAYED AS A PERSON WOULD, on the JVM: files opened, a table closed, a model loaded, a question answered
 * by a model of the test's (the sample's gold programs), the result on the screen — the same program the page runs */
class AppSuite extends munit.FunSuite:
  type Row[X] = (Ui + Fetch + Model)[X]
  type NoModel[X] = (Ui + Fetch)[X]
  val gold = Sample.questions.flatMap(q => List(q.en -> q.gold, q.ru -> q.gold)).toMap
  def csv(t: okay.desk.Table): String = (t.columns +: t.rows.map(_.map(_.text))).map(_.mkString(",")).mkString("\n")
  def fetched(url: String): Response = url match
    case "sample/orders.csv" => Response(200, Map.empty, csv(Sample.orders))
    case _ => Response(404)
  def play(opens: List[String], acts: Act*): Played[Unit] =
    val p = Model.answeredBy[Row, NoModel, Unit](n => Right(s"$n loaded"), (_, q) => gold.get(q).toRight("no program for that"))(App.main[Row](opens))
    val byTable = handle(p, Handler.Tail[Fetch, Ui, Unit, Unit](a => pure(a), [X] => (op: Fetch[X]) => op match
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
      case _ => Nil
    }
    p.at.fold("")(s => walk(s.parts).mkString("\n"))
  val bundle = Bundle.write(List("data/orders.csv" -> csv(Sample.orders), "data/customers.csv" -> csv(Sample.customers),
    "notes.txt" -> "not a table"))

  test("files opened, a table closed, the model loaded, a question answered by the exact engine"):
    val p = play(Nil, Act.press("Open the files", "Files, or a whole folder (CSV, TSV, JSON)" -> bundle),
      Act.press("Close customers"), Act.press("Qwen3 0.6B cut + readers (244 MB, no generation)"),
      Act.press("Ask", "Ask about the data: how many orders are unpaid? total by customer? …" -> "How many orders are unpaid?"))
    assert(p.problems.isEmpty, p.problems.toString)
    val t = text(p)
    assert(t.contains("orders: 8 rows, 6 columns"), t)
    assert(!t.contains("customers: 5 rows, 3 columns\ntable customers") && t.contains("closed customers"), t)
    assert(t.contains("readers loaded"), t)
    assert(t.contains("n\n-\n4") || t.contains("| 4") || t.contains("4"), t)
    assert(t.contains("not generated"), t)

  test("an address opened at the start, a question before the model: a problem said"):
    val p = play(List("sample/orders.csv"), Act.press("Ask", "Ask about the data: how many orders are unpaid? total by customer? …" -> "How many orders are unpaid?"))
    val t = text(p)
    assert(t.contains("opened orders.csv as table orders"), t)
    assert(t.contains("load the model first"), t)
    val missing = text(play(List("sample/nothing.csv")))
    assert(missing.contains("not read"), missing)
