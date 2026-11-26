package okay.desk.books

import okay.desk.{Frame, LineSpace}
import okay.tax.{Form, Jdg, Pay, PayTheory, Zus}

/**
 * THE QUESTIONS ABOUT PAY AS LINES (SPRINT K6, the tax helper): a question read into what is earned and how — an
 * employment contract at a gross, a JDG's revenue (or a day rate and days), its tax form and ZUS, its costs, or both
 * compared; the engine (`Pay`, `Jdg`) answers with the year's take-home and its monthly average. The numbers are the
 * question's own (copied, never guessed: "20 000", "20k", "1000"); the rest is chosen.
 */
object PayLines extends LineSpace[Unit]:
  val name = "pay"
  val rule: String =
    """You read a question about take-home pay in Poland into lines, one at a time, each line one of the options:
      |contract: uop | b2b | compare; for uop: gross: N; for b2b: revenue: N (a month), or rate: N and days: N (a day
      |rate), form: scale | linear | lump, lump rate: R (for lump), zus: full | preferential | start,
      |sickness: yes | no, costs: N or none; for compare: the b2b lines, then gross: N; end. Where the question does
      |not say: zus: full, sickness: no, costs: none.""".stripMargin
  def describe(w: Unit): String =
    "forms: scale (12% / 32%), linear (19%), lump (ryczałt: 8.5%, 12%, 14%, 15%, 17%)\nZUS: full, preferential, start"

  /** the question's numbers: "20 000", "20k", "1 000,50" — thousands joined, "k" a thousand */
  def numbers(q: String): Vector[String] =
    """(?i)(\d{1,3}(?:[  ]\d{3})+|\d+)(?:[.,](\d+))?\s*(k|тыс\w*|tys\w*)?\b""".r.findAllMatchIn(q).toVector
      .map { m =>
        val whole = BigDecimal(m.group(1).replaceAll("[  ]", "") + Option(m.group(2)).fold("")("." + _))
        (if m.group(3) != null then whole * 1000 else whole).bigDecimal.stripTrailingZeros.toPlainString }
      .distinct
  private def value(taken: Vector[String], key: String): Option[String] =
    taken.find(_.startsWith(key + ": ")).map(_.drop(key.length + 2))
  private val lumpRates = Vector("8.5", "12", "14", "15", "17")
  /** the b2b slots in order, given those taken (a day rate needs its days; a lump form its rate) */
  private def b2bNext(q: String, taken: Vector[String]): Option[Vector[String]] =
    val ns = numbers(q)
    if value(taken, "revenue").isEmpty && value(taken, "rate").isEmpty then
      Some(ns.map("revenue: " + _) ++ ns.map("rate: " + _))
    else if value(taken, "rate").nonEmpty && value(taken, "days").isEmpty then Some(ns.map("days: " + _))
    else if value(taken, "form").isEmpty then Some(Vector("scale", "linear", "lump").map("form: " + _))
    else if value(taken, "form").contains("lump") && value(taken, "lump rate").isEmpty then
      Some(lumpRates.map("lump rate: " + _))
    else if value(taken, "zus").isEmpty then Some(Vector("full", "preferential", "start").map("zus: " + _))
    else if value(taken, "sickness").isEmpty then Some(Vector("yes", "no").map("sickness: " + _))
    else if value(taken, "costs").isEmpty then Some(("none" +: ns).map("costs: " + _))
    else None
  def next(w: Unit, question: String, taken: Vector[String]): Vector[String] =
    if taken.lastOption.contains(Frame.end) then Vector.empty
    else value(taken, "contract") match
      case None => Vector("uop", "b2b", "compare").map("contract: " + _)
      case Some("uop") =>
        if value(taken, "gross").isEmpty then numbers(question).map("gross: " + _) else Vector(Frame.end)
      case Some(contract) =>
        b2bNext(question, taken).getOrElse(
          if contract == "compare" && value(taken, "gross").isEmpty then numbers(question).map("gross: " + _)
          else Vector(Frame.end))

  /** the reading's meaning, run: the year's take-home (and its monthly average), each contract compared */
  def answer(w: Unit, lines: Vector[String]): Either[String, String] =
    val t = PayTheory.y2026
    def num(k: String) = value(lines, k).flatMap(v => scala.util.Try(BigDecimal(v)).toOption)
    def said(label: String, y: Pay.Year) = s"$label: ${y.averageNet} a month, ${y.net} a year"
    def uop = num("gross").map(g => said(s"UoP $g gross", Pay.employment(t, g)))
    def b2b: Option[String] =
      for
        revenue <- num("revenue").orElse(for r <- num("rate"); d <- num("days") yield r * d)
        form <- value(lines, "form").collect {
          case "scale" => Form.Scale
          case "linear" => Form.Linear
          case "lump" if num("lump rate").nonEmpty => Form.Lump(num("lump rate").get / 100) }
        zus <- value(lines, "zus").collect { case "full" => Zus.Full; case "preferential" => Zus.Preferential
          case "start" => Zus.Start }
        sick <- value(lines, "sickness").map(_ == "yes")
        costs = num("costs").getOrElse(BigDecimal(0))
      yield said(s"B2B $revenue a month, ${value(lines, "form").get}, ZUS ${value(lines, "zus").get}",
        Jdg.year(t, revenue, costs, form, zus, sick))
    (value(lines, "contract") match
      case Some("uop") => uop
      case Some("b2b") => b2b
      case Some("compare") => for a <- b2b; b <- uop yield s"$a; $b"
      case _ => None).toRight("not a reading of pay")

  /** a meaning's lines in order (the gold of a drawn question) */
  def lines(contract: String, gross: Option[BigDecimal], revenue: Option[BigDecimal],
      rateDays: Option[(BigDecimal, BigDecimal)], form: String, lumpRate: Option[String], zus: String,
      sickness: Boolean, costs: Option[BigDecimal]): Vector[String] =
    def n(x: BigDecimal) = x.bigDecimal.stripTrailingZeros.toPlainString
    val b2bPart =
      revenue.map(r => Vector(s"revenue: ${n(r)}")).getOrElse(rateDays.toVector.flatMap((r, d) =>
        Vector(s"rate: ${n(r)}", s"days: ${n(d)}"))) ++ Vector(s"form: $form") ++
        lumpRate.map(r => s"lump rate: $r") ++ Vector(s"zus: $zus", s"sickness: ${if sickness then "yes" else "no"}",
          s"costs: ${costs.fold("none")(n)}")
    (contract match
      case "uop" => Vector("contract: uop", s"gross: ${n(gross.get)}")
      case "b2b" => "contract: b2b" +: b2bPart
      case _ => ("contract: compare" +: b2bPart) :+ s"gross: ${n(gross.get)}") :+ Frame.end
