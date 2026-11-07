package okay.desk.calc

import okay.desk.*
import okay.desk.Space.Program
import Term.*

/**
 * THE ANSWER GRAMMAR (specs/calculus.md §10): generation by the same program as reading. A sentence about a
 * result is a term of the rules below: one constructor (the shape, fixed by the program and the result), FACT
 * holes whose only candidate is a cell of the result (the data fills them, no chooser asked), WORD holes whose
 * candidates are the wordings of a slot in a language (a model's tokens, a person, or a uniform weigh choose).
 * `Strategy.greedy` over these rules is one answer; `Strategy.every` is every wording of it; the interpretation
 * (`text`) is the sentence. A figure in the text is a cell by construction — the law of §4 holds for every term
 * of this grammar, not only for the shapes `Answer.of` wrote.
 */
final class Answers(program: Program, result: Table, ru: Boolean) extends Rules:
  private def cell(row: Int, column: String): String = result.column(column).map(i => result.rows(row)(i).text).getOrElse("")

  /** the shape of the answer, fixed by the program and the result */
  val shape: String =
    if result.rows.isEmpty then "Nothing"
    else program match
      case Program.Closure(_, _, _) => "Reach"
      case Program.Absent(_, _, _) => "Missing"
      case Program.Rel(_, _, _, group, agg, order, limit) => (agg, group) match
        case (Some(("count", _)), None) => "Count"
        case (Some((_, Some(_))), None) => "Scalar"
        case (Some(_), Some(_)) => "Groups"
        case _ if limit.contains(1) && order.isDefined => "Top"
        case _ => "Rows"

  /** THE SLOTS of a shape, in order: a fact (a cell) or a word slot */
  private val fn = program match { case Program.Rel(_, _, _, _, Some((f, _)), _, _) => f; case _ => "" }
  private val measure = if fn == "count" then "n" else fn
  private val groupKey = program match { case Program.Rel(_, _, _, Some(g), _, _, _) => g.name; case _ => "" }
  private val shown = result.rows.indices.take(3).toVector
  private val slots: Vector[Ty] = shape match
    case "Count" => Vector(Ty.FactT(0, "n"), Ty.WordT("rows"))
    case "Scalar" => Vector(Ty.WordT("what"), Ty.FactT(0, measure))
    case "Groups" => Ty.WordT("per") +: result.rows.indices.toVector.flatMap(i => Vector(Ty.FactT(i, groupKey), Ty.FactT(i, measure)))
    case "Top" =>
      val (by, _) = program.asInstanceOf[Program.Rel].order.get
      val key = result.columns.filterNot(_ == by).headOption.getOrElse(result.columns.head)
      Vector(Ty.WordT("top"), Ty.FactT(0, key)) ++ result.column(by).map(_ => Ty.FactT(0, by)).toVector
    case "Rows" => Ty.WordT("list") +: shown.flatMap(i => result.columns.take(3).map(c => Ty.FactT(i, c)))
    case "Reach" => Ty.WordT("reach") +: result.rows.indices.toVector.map(i => Ty.FactT(i, result.columns.head))
    case "Missing" => Ty.WordT("missing") +: result.rows.indices.toVector.map(i => Ty.FactT(i, result.columns.head))
    case _ => Vector(Ty.WordT("nothing"))

  /** the wordings of a slot: several in each language — what a model may choose among */
  def wordings(slot: String): Vector[String] = (slot, ru) match
    case ("rows", true) => Vector("строк", "записей", "таких строк")
    case ("rows", false) => Vector("rows", "records", "such rows")
    case ("what", true) => Vector((fn match { case "sum" => "Сумма"; case "avg" => "Средний"; case "min" => "Наименьший"; case _ => "Наибольший" }) + " " + measureName,
      (fn match { case "sum" => "Итого"; case "avg" => "В среднем"; case "min" => "Минимум"; case _ => "Максимум" }) + " " + measureName)
    case ("what", false) => Vector((fn match { case "sum" => "The total"; case "avg" => "The average"; case "min" => "The smallest"; case _ => "The largest" }) + " " + measureName,
      (fn match { case "sum" => "In total"; case "avg" => "On average"; case "min" => "At least"; case _ => "At most" }) + ", " + measureName)
    case ("per", true) => Vector(s"$measure по $groupKey", s"$measure в разрезе $groupKey")
    case ("per", false) => Vector(s"$measure per $groupKey", s"$measure by $groupKey")
    case ("top", true) => Vector(if desc then "Самый большой" else "Самый маленький", if desc then "Больше всех" else "Меньше всех")
    case ("top", false) => Vector(if desc then "The largest" else "The smallest", if desc then "Top" else "Bottom")
    case ("list", true) => Vector(s"${result.rows.size} строк", s"Найдено ${result.rows.size}")
    case ("list", false) => Vector(s"${result.rows.size} rows", s"Found ${result.rows.size}")
    case ("reach", true) => Vector(if forward then s"Из $node достижимо" else s"До $node доходят", if forward then s"$node ведёт к" else s"К $node ведут")
    case ("reach", false) => Vector(if forward then s"From $node" else s"Reaching $node", if forward then s"$node leads to" else s"Leading to $node")
    case ("missing", true) => Vector(s"$target без $other", s"$target, которых нет в $other")
    case ("missing", false) => Vector(s"$target with no $other", s"$target absent from $other")
    case ("nothing", true) => Vector("Ничего не найдено", "Пусто")
    case (_, _) => Vector("Nothing found", "Empty")
  private def measureName = program match { case Program.Rel(_, _, _, _, Some((_, Some(c))), _, _) => c.name; case _ => measure }
  private def desc = program match { case Program.Rel(_, _, _, _, _, Some((_, d)), _) => d; case _ => true }
  private def forward = program match { case Program.Closure(_, _, f) => f; case _ => true }
  private def node = program match { case Program.Closure(_, n, _) => n; case _ => "" }
  private def target = program match { case Program.Absent(t, _, _) => t; case _ => "" }
  private def other = program match { case Program.Absent(_, l, _) => l.from; case _ => "" }

  def constructors(ty: Ty): Vector[String] = ty match
    case Ty.AnswerT => Vector(shape)
    case Ty.FactT(row, column) => Vector(cell(row, column))
    case Ty.WordT(slot) => wordings(slot)
    case _ => Vector.empty
  def opens(ty: Ty, con: String): Boolean = ty == Ty.AnswerT
  def next(ty: Ty, con: String, args: Vector[Term]): Option[Ty] = if ty == Ty.AnswerT then slots.lift(args.size) else None

  /** the facts of a term: the cells it names */
  def facts(term: Term): Vector[Answer.Fact] = term match
    case Con(_, _, args) => args.collect { case Leaf(Ty.FactT(r, c), v) => Answer.Fact(r, c, v) }
    case _ => Vector.empty

  /** THE INTERPRETATION: the sentence of a closed term */
  def text(term: Term): String = term match
    case Con(_, s, args) =>
      val ws = args.collect { case Leaf(Ty.WordT(_), w) => w }
      val fs = args.collect { case Leaf(Ty.FactT(_, c), v) => (c, v) }
      s match
        case "Count" => s"${fs.head._2} ${ws.head}."
        case "Scalar" => s"${ws.head}: ${fs.head._2}."
        case "Groups" => s"${ws.head} — ${fs.grouped(2).map(p => s"${p(0)._2}: ${p(1)._2}").mkString(", ")}."
        case "Top" => s"${ws.head}: ${fs.head._2}${fs.lift(1).map((c, v) => s" ($c $v)").getOrElse("")}."
        case "Rows" =>
          val n = result.columns.take(3).size
          s"${ws.head} (${result.columns.take(3).mkString(", ")}): ${fs.map(_._2).grouped(n).map(_.mkString(", ")).mkString("; ")}${if result.rows.size > shown.size then "; …" else ""}."
        case "Reach" | "Missing" => s"${ws.head}: ${fs.map(_._2).mkString(", ")}."
        case _ => s"${ws.head}."
    case _ => ""

object Answers:
  /** the context of an answer: no question, no schema needed — the term alone */
  def context(s: Space.Schema): Context = Context(s, "", Hole(Ty.AnswerT))
  /** a sentence read back: every fact in it, every number a fact or the count shown */
  def readBack(rules: Answers, term: Term, sentence: String): Boolean =
    val fs = rules.facts(term)
    fs.forall(f => sentence.contains(f.text)) &&
      "-?\\d+(?:\\.\\d+)?".r.findAllIn(sentence).forall(n => fs.exists(f => f.text == n || f.text.contains(n)) || sentence.matches(s"(?s).*\\b$n (rows|строк|records|записей|such rows|таких строк)\\b.*") || sentence.startsWith(s"Found $n") || sentence.startsWith(s"Найдено $n") || sentence.startsWith(s"$n rows") || sentence.startsWith(s"$n строк"))
