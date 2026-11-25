package okay.desk.books

import scala.math.BigDecimal.RoundingMode

/** a value of a theory and where the book states it: the source and the words, verbatim */
final case class Stated(value: BigDecimal, source: String, quote: String)

/**
 * TAKE-HOME PAY IN POLAND, 2026 — THE FIRST BOOK COMPILED (specs/books.md §2; SPRINT K6): the theory is data, each
 * value
 * with its quote from the statute or ZUS's table it comes from (the sources: `pit` the PIT act Dz.U. 2026 poz. 592,
 * `lump` the ryczałt act Dz.U. 2025 poz. 843, `mp` the Minister's notice M.P. 2025 poz. 1274 (the 2026 limit),
 * `sus` the social insurance act Dz.U. 2026 poz. 1474, `health` the health
 * care act Dz.U. 2025 poz. 1461, `zus` ZUS's "Wysokość składek … w 2026 r."). The engine below is exact arithmetic in
 * grosze over it; the book's worked examples (ZUS's tables, the PIT scale) are its laws (`PaySuite`).
 */
object PayTheory:
  private def s(v: String, source: String, quote: String) = Stated(BigDecimal(v), source, quote)
  val y2026: Map[String, Stated] = Map(
    "pit.rate1" -> s("0.12", "pit", "120 000                   12 % minus kwota zmniejszająca podatek 3600 zł"),
    "pit.threshold" -> s("120000", "pit", "120 000                   12 % minus kwota zmniejszająca podatek 3600 zł"),
    "pit.reducing" -> s("3600", "pit", "12 % minus kwota zmniejszająca podatek 3600 zł"),
    "pit.rate2" -> s("0.32", "pit", "10 800 zł + 32 % nadwyżki ponad 120 000 zł"),
    "pit.monthly.reducing.share" -> s("0.0833333333333333333333", "pit",
      "płatnik pomniejsza zaliczki o kwotę stanowiącą niewięcej niż 1/12 kwoty zmniejszającej podatek"),
    "pit.employee.costs" -> s("250", "pit", "wynoszą 250 zł miesięcznie"),
    "zus.pension" -> s("0.1952", "sus", "19,52 % podstawy wymiaru – na ubezpieczenie emerytalne"),
    "zus.disability" -> s("0.08", "sus", "8,00 % podstawy wymiaru – na ubezpieczenia rentowe"),
    "zus.sickness" -> s("0.0245", "sus", "2,45 % podstawy wymiaru – na ubezpieczenie chorobowe"),
    "zus.pension.employee.share" -> s("0.5", "sus", "w równych częściach, ubezpieczeni i płatnicy składek"),
    "zus.disability.employee" -> s("0.015", "sus", "1,5 % podstawy wymiaru ubezpieczeni"),
    "zus.accident" -> s("0.0167", "zus", "Składkę na ubezpieczenie wypadkowe w wyliczeniu ustaliliśmy stosując stopę " +
      "procentową 1,67%"),
    "zus.labour.fund" -> s("0.0245", "zus", "2,45% [ 3 ]"),
    "zus.full.base" -> s("5652", "zus", "5 652 zł [ 1 ]"),
    "zus.preferential.base" -> s("1441.80", "zus", "1441,80 zł [ 4 ]"),
    "zus.cap.year" -> s("282600", "zus",
      "Roczna podstawa wymiaru na ubezpieczenia emerytalne i rentowe w 2026 roku może " +
      "wynosić maksymalnie 282 600 zł"),
    "health.rate" -> s("0.09", "health", "Składka na ubezpieczenie zdrowotne wynosi 9 % podstawy wymiaru składki"),
    "health.lump.1" -> s("498.35", "zus", "Składka na ubezpieczenie zdrowotne wynosi 498,35 zł"),
    "health.lump.2" -> s("830.58", "zus", "Składka na ubezpieczenie zdrowotne wynosi 830,58 zł"),
    "health.lump.3" -> s("1495.04", "zus", "Składka na ubezpieczenie zdrowotne wynosi 1 495,04"),
    "health.lump.limit1" -> s("60000", "zus", "nie przekroczyły kwoty 60 000 zł"),
    "health.lump.limit2" -> s("300000", "zus", "przekroczyły kwotę 60 000 zł i nie przekroczyły kwoty 300 000 zł"),
    "lump.it" -> s("0.12", "lump", "2b) 12 % przychodów ze świadczenia usług"),
    "lump.health.deducted" -> s("0.5", "lump", "mogą być pomniejszone o 50 % składek na ubezpieczenie zdrowotne"),
    "pit.linear" -> s("0.19", "pit", "wynosi 19 % podstawy obliczenia podatku"),
    "health.linear.rate" -> s("0.049", "health", "4,9 % podstawy wymiaru składki"),
    "health.min" -> s("432.54", "zus", "432,54 zł (za miesiące od lutego 2026 r. do stycznia 2027 r."),
    "health.linear.limit" -> s("14100", "mp", "w roku 2026 nie może przekroczyć 14 100 zł"),
    "health.base.income" -> s("1", "health",
      "pomniejszony o kwotę opłaconych w tym roku składek na ubezpieczenia emerytalne, " +
      "rentowe"))

/** the engine: a year of an employment contract (UoP) or of a JDG on ryczałt, month by month, in grosze */
object Pay:
  type Theory = Map[String, Stated]
  private def grosz(x: BigDecimal): BigDecimal = x.setScale(2, RoundingMode.HALF_UP)
  private def zloty(x: BigDecimal): BigDecimal = x.setScale(0, RoundingMode.HALF_UP)

  /** what a month comes to: its parts, said in a reading */
  final case class Month(gross: BigDecimal, social: BigDecimal, health: BigDecimal, tax: BigDecimal,
      other: BigDecimal = 0):
    def net: BigDecimal = gross - social - health - tax - other
  final case class Year(months: Vector[Month]):
    def net: BigDecimal = months.map(_.net).sum
    def averageNet: BigDecimal = grosz(net / 12)

  /** UoP: the employee's contributions (pension and disability capped by the year's base), health on what is left,
   * the monthly advance at 12% then 32% once the year's income passed the threshold, less 1/12 of the reducing amount
   * (PIT-2 filed), on the base less the employee's costs */
  def employment(t: Theory, gross: BigDecimal): Year =
    def v(k: String) = t(k).value
    val pensionRate = v("zus.pension") * v("zus.pension.employee.share")
    val cap = v("zus.cap.year")
    var paidBase = BigDecimal(0); var income = BigDecimal(0)
    Year((1 to 12).toVector.map { _ =>
      val capped = (cap - paidBase).max(0).min(gross); paidBase += capped
      val social = grosz(capped * pensionRate) + grosz(capped * v("zus.disability.employee")) +
        grosz(gross * v("zus.sickness"))
      val health = grosz((gross - social) * v("health.rate"))
      val base = zloty(gross - social - v("pit.employee.costs"))
      val before = income; income += base
      val low = (v("pit.threshold") - before).max(0).min(base)
      val advance = zloty(low * v("pit.rate1") + (base - low) * v("pit.rate2") -
        v("pit.reducing") * v("pit.monthly.reducing.share")).max(0)
      Month(gross, social, health, advance) })

  /** the social contributions of a JDG on a base: pension, disability, accident, sickness if chosen; and the labour
   * fund (paid, not deducted) where the base is the full one */
  def jdgSocial(t: Theory, base: BigDecimal, sickness: Boolean, labourFund: Boolean): (BigDecimal, BigDecimal) =
    def v(k: String) = t(k).value
    val social = grosz(base * v("zus.pension")) + grosz(base * v("zus.disability")) +
      grosz(base * v("zus.accident")) + (if sickness then grosz(base * v("zus.sickness")) else 0)
    (social, if labourFund then grosz(base * v("zus.labour.fund")) else 0)

  /** a JDG on ryczałt: the revenue less its social contributions and half its health contribution, taxed at `rate`;
   * the health contribution by the year's revenue */
  def lump(t: Theory, revenue: BigDecimal, rate: BigDecimal, preferential: Boolean, sickness: Boolean): Year =
    def v(k: String) = t(k).value
    val base = if preferential then v("zus.preferential.base") else v("zus.full.base")
    val (social, fund) = jdgSocial(t, base, sickness, labourFund = !preferential)
    val yearly = revenue * 12
    val health = if yearly <= v("health.lump.limit1") then v("health.lump.1")
      else if yearly <= v("health.lump.limit2") then v("health.lump.2") else v("health.lump.3")
    Year((1 to 12).toVector.map { _ =>
      val taxed = zloty(revenue - social - grosz(health * v("lump.health.deducted"))).max(0)
      Month(revenue, social, health, zloty(taxed * rate), fund) })

/** a JDG's choices: the form of the tax and the ZUS it pays */
enum Form:
  case Scale, Linear
  case Lump(rate: BigDecimal)
enum Zus:
  /** the first six months: no social contributions (the health one paid) */
  case Start
  case Preferential, Full

/**
 * A YEAR OF A JDG (SPRINT K6, the tax helper's first step): a steady month — `revenue`, `costs` — twelve times; the
 * social contributions by `zus`; on the scale and the linear tax the labour fund a cost, the income less the social
 * contributions taxed for the year (the scale's 12% / 32% less the reducing amount; 19% less the health contribution
 * up to the year's limit), the health contribution a share of that income (9%, 4.9%) and no less than the minimum;
 * on ryczałt, `Pay.lump`. Each part said, the year's take-home and its monthly average
 */
object Jdg:
  import Pay.{Month, Year, Theory}
  private def grosz(x: BigDecimal) = x.setScale(2, scala.math.BigDecimal.RoundingMode.HALF_UP)
  private def zloty(x: BigDecimal) = x.setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP)

  def year(t: Theory, revenue: BigDecimal, costs: BigDecimal, form: Form, zus: Zus, sickness: Boolean): Year =
    def v(k: String) = t(k).value
    val (social, fund) = zus match
      case Zus.Start => (BigDecimal(0), BigDecimal(0))
      case Zus.Preferential => Pay.jdgSocial(t, v("zus.preferential.base"), sickness, labourFund = false)
      case Zus.Full => Pay.jdgSocial(t, v("zus.full.base"), sickness, labourFund = true)
    form match
      case Form.Lump(rate) =>
        // (the costs not deducted on ryczałt, but spent all the same: out of the take-home)
        val y = Pay.lump(t, revenue, rate, zus == Zus.Preferential, sickness)
        Year((if zus == Zus.Start then y.months.map(m =>
          m.copy(social = 0, other = 0, tax = zloty((zloty(revenue - grosz(m.health * v("lump.health.deducted")))
            .max(0)) * rate))) else y.months).map(m => m.copy(other = m.other + costs)))
      case _ =>
        // the income: the revenue less the costs and the labour fund (a cost on these forms)
        val income = revenue - costs - fund
        val healthRate = if form == Form.Scale then v("health.rate") else v("health.linear.rate")
        val health = grosz((income - social).max(0) * healthRate).max(v("health.min"))
        val taxable = ((income - social) * 12).max(0)
        val yearTax = form match
          case Form.Scale =>
            val low = taxable.min(v("pit.threshold"))
            zloty(low * v("pit.rate1") + (taxable - low) * v("pit.rate2") - v("pit.reducing")).max(0)
          case _ =>
            val deducted = (health * 12).min(v("health.linear.limit"))
            zloty((taxable - deducted).max(0) * v("pit.linear"))
        // the year's tax spread over its months (the advances add up to it)
        val monthly = Vector.fill(12)(grosz(yearTax / 12))
        Year(monthly.zipWithIndex.map((tax, i) =>
          Month(revenue, social, health, if i == 11 then yearTax - monthly.take(11).sum else tax, fund + costs)))

  /** every form and ZUS a JDG may take, against one another and against UoP at `gross`: the yearly take-home */
  def compare(t: Theory, revenue: BigDecimal, costs: BigDecimal, lumpRate: BigDecimal, sickness: Boolean,
      gross: Option[BigDecimal]): Vector[(String, BigDecimal)] =
    val forms = Vector("skala" -> Form.Scale, "liniowy" -> Form.Linear, s"ryczałt ${lumpRate * 100}%" ->
      Form.Lump(lumpRate))
    val zuses = Vector("pełny ZUS" -> Zus.Full, "preferencyjny" -> Zus.Preferential)
    (for (fn, f) <- forms; (zn, z) <- zuses yield s"B2B, $fn, $zn" -> year(t, revenue, costs, f, z, sickness).net) ++
      gross.map(g => s"UoP ${g} brutto" -> Pay.employment(t, g).net)
