package okay.desk.books

import okay.desk.{Frame, LineSpace}
import okay.tax.{Rule, Rules}

/**
 * THE QUESTIONS ABOUT TAX RULES AS ONE LINE (okay-tax specs/tax.md §6): a question in any of four languages read into
 * the rule it asks about — one of okay-tax's rules, chosen by the model (`rule: <its English title>`), then the end.
 * The answer is the rule's id; okay-tax says it and quotes the statute. `withSays`: the prompt lists what each rule
 * says too, not its title alone. `among` > 0: the model chooses among the word search's first `among` only
 * (`Rules.find`), not all of them — the search narrows, the model reads.
 */
final class RuleLines(withSays: Boolean, among: Int = 0) extends LineSpace[Unit]:
  val name = if withSays then "rules-says" else "rules"
  val rule: String =
    """You read a question about Polish tax law into the one rule it asks about, a line: rule: TITLE, one of the
      |rules listed; then end.""".stripMargin
  private def option(r: Rule) = s"rule: ${r.title("en")}"
  /** the rules offered for a question: all, or the search's first `among` (all when it finds none) */
  def offered(question: String): Vector[Rule] =
    // (in the rules' own order, not the search's: a model drawn to the first listed would else echo the search)
    if among <= 0 then Rules.all
    else Some(Rules.find(question, among).toSet).filter(_.nonEmpty).fold(Rules.all)(s => Rules.all.filter(s))
  private def listed(rs: Vector[Rule]) =
    rs.map(r => if withSays then s"${option(r)} — ${r.said("en")}" else option(r)).mkString("\n")
  def describe(w: Unit): String = listed(Rules.all)
  override def prompt(w: Unit, question: String): (String, String) =
    (rule, s"${listed(offered(question))}\n\nQuestion: $question")
  def next(w: Unit, question: String, taken: Vector[String]): Vector[String] =
    if taken.isEmpty then offered(question).map(option) else if taken.last == Frame.end then Vector.empty
    else Vector(Frame.end)
  def answer(w: Unit, lines: Vector[String]): Either[String, String] =
    lines.headOption.flatMap(l => Rules.all.find(option(_) == l)).map(_.id).toRight("not a rule")
  /** the gold lines of a rule */
  def lines(id: String): Vector[String] = Rules.all.find(_.id == id).map(option).toVector :+ Frame.end
