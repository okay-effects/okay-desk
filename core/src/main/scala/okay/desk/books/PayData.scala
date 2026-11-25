package okay.desk.books

import okay.effects.json.Json

/**
 * QUESTIONS ABOUT PAY, DRAWN AND SAID (SPRINT K6): a meaning drawn (UoP at a gross, a JDG's revenue or day rate, its
 * form, ZUS, sickness, costs, or both compared) and said in English, Russian, Ukrainian or Polish by templates that
 * name its parts in several ways — and leave out, as people do, what the defaults cover (full ZUS, no sickness, no
 * costs). The rows `DomainData` writes.
 */
object PayData:
  private final case class Words(scale: Vector[String], linear: Vector[String], lump: Vector[String],
      zus: Map[String, Vector[String]], sickYes: Vector[String], sickNo: Vector[String], costs: Vector[String],
      month: Vector[String], day: Vector[String], uop: Vector[String], b2bAsk: Vector[String],
      compare: Vector[String])
  private val words = Map(
    "en" -> Words(Vector("on the tax scale", "on the general rules (12/32%)"), Vector("on the flat 19% tax",
      "on the linear tax"), Vector("on ryczałt {r}%", "on a lump-sum tax of {r}%"),
      Map("full" -> Vector("full ZUS", "paying full ZUS"), "preferential" -> Vector("preferential ZUS",
        "the small preferential ZUS"), "start" -> Vector("on ulga na start", "with no social ZUS (ulga na start)")),
      Vector("with sickness insurance", "with voluntary sickness insurance"), Vector("without sickness insurance"),
      Vector("costs {c} zł a month", "with {c} zł of expenses a month"), Vector("B2B at {R} zł a month",
        "invoicing {R} zł a month"), Vector("B2B at {r} zł a day for {d} days", "a day rate of {r} zł, {d} days"),
      Vector("How much will I take home on UoP at {g} zł gross?", "Net pay on an employment contract of {g} gross?",
        "UoP {g} brutto: what's my net?"),
      Vector("how much do I keep?", "what is my take-home?", "what's left for me?"),
      Vector("Compare UoP {g} gross with {b2b}.", "Which is better: UoP at {g} gross or {b2b}?")),
    "ru" -> Words(Vector("на шкале (12/32%)", "на общих принципах"), Vector("на линейном налоге 19%",
      "на линейке 19%"), Vector("на ryczałt {r}%", "на ричалте {r}%"),
      Map("full" -> Vector("полный ZUS", "с полным ZUS"), "preferential" -> Vector("льготный ZUS",
        "с преференционным ZUS"), "start" -> Vector("на ulga na start", "без социального ZUS (ulga na start)")),
      Vector("с больничным", "с добровольным больничным"), Vector("без больничного"),
      Vector("расходы {c} zł в месяц", "с расходами {c} zł в месяц"), Vector("B2B {R} zł в месяц",
        "выставляю {R} zł в месяц"), Vector("B2B {r} zł в день, {d} дней", "ставка {r} zł в день, {d} дней"),
      Vector("Сколько я получу на руки на UoP при {g} брутто?", "Сколько на руки по трудовому договору {g} zł брутто?",
        "UoP {g} брутто — сколько нетто?"),
      Vector("сколько я получу на руки?", "сколько останется?", "сколько чистыми?"),
      Vector("Сравни UoP {g} брутто и {b2b}.", "Что выгоднее: UoP {g} брутто или {b2b}?")),
    "uk" -> Words(Vector("на загальних засадах (12/32%)", "на шкалі"), Vector("на лінійному податку 19%",
      "на лінійці"), Vector("на ричалті {r}%", "на ryczałt {r}%"),
      Map("full" -> Vector("повний ZUS", "з повним ZUS"), "preferential" -> Vector("пільговий ZUS",
        "з пільговим ZUS"), "start" -> Vector("на ulga na start", "без соціального ZUS (ulga na start)")),
      Vector("з лікарняним", "з добровільним лікарняним"), Vector("без лікарняного"),
      Vector("витрати {c} zł на місяць", "з витратами {c} zł на місяць"), Vector("B2B {R} zł на місяць",
        "виставляю {R} zł на місяць"), Vector("B2B {r} zł на день, {d} днів", "ставка {r} zł на день, {d} днів"),
      Vector("Скільки я отримаю на руки на UoP при {g} брутто?", "Скільки на руки за трудовим договором {g} zł брутто?",
        "UoP {g} брутто — скільки нетто?"),
      Vector("скільки отримаю на руки?", "скільки залишиться?", "скільки чистими?"),
      Vector("Порівняй UoP {g} брутто і {b2b}.", "Що вигідніше: UoP {g} брутто чи {b2b}?")),
    "pl" -> Words(Vector("na skali podatkowej", "na zasadach ogólnych (12/32%)"), Vector("na podatku liniowym 19%",
      "na liniowym"), Vector("na ryczałcie {r}%", "na ryczałcie {r} procent"),
      Map("full" -> Vector("pełny ZUS", "z pełnym ZUS-em"), "preferential" -> Vector("preferencyjny ZUS",
        "mały ZUS preferencyjny"), "start" -> Vector("na uldze na start", "bez ZUS społecznego (ulga na start)")),
      Vector("z chorobowym", "z dobrowolnym chorobowym"), Vector("bez chorobowego"),
      Vector("koszty {c} zł miesięcznie", "z kosztami {c} zł miesięcznie"), Vector("B2B {R} zł miesięcznie",
        "fakturuję {R} zł miesięcznie"), Vector("B2B {r} zł dziennie przez {d} dni", "stawka {r} zł za dzień, {d} dni"),
      Vector("Ile dostanę na rękę na UoP przy {g} brutto?", "Ile netto na umowie o pracę {g} zł brutto?",
        "UoP {g} brutto — ile na rękę?"),
      Vector("ile mi zostanie?", "ile na rękę?", "ile dostanę netto?"),
      Vector("Porównaj UoP {g} brutto i {b2b}.", "Co się bardziej opłaca: UoP {g} brutto czy {b2b}?")))

  def draw(rnd: scala.util.Random): (String, String, Vector[String]) =
    def pick[A](xs: Vector[A]): A = xs(rnd.nextInt(xs.size))
    val lang = pick(Vector("en", "ru", "uk", "pl"))
    val w = words(lang)
    def money(x: Int): String = rnd.nextInt(3) match
      case 0 => x.toString
      case 1 => if x >= 1000 then f"${x / 1000} ${x % 1000}%03d" else x.toString
      case _ => if x % 1000 == 0 && x >= 1000 then s"${x / 1000}k" else x.toString
    val contract = pick(Vector("uop", "b2b", "b2b", "compare"))
    val gross = 4000 + 500 * rnd.nextInt(63)
    val byDay = rnd.nextBoolean()
    val revenue = 6000 + 500 * rnd.nextInt(90)
    val (rate, days) = (300 + 50 * rnd.nextInt(35), 15 + rnd.nextInt(8))
    val form = pick(Vector("scale", "linear", "lump", "lump"))
    val lumpRate = pick(Vector("12", "12", "8.5", "15", "14", "17"))
    val zus = pick(Vector("full", "full", "preferential", "start"))
    val sick = rnd.nextInt(4) == 0
    val costs = Option.when(rnd.nextInt(3) == 0)(100 * (2 + rnd.nextInt(40)))
    // the parts said; the defaults (full ZUS, no sickness, no costs) said or left out
    val formSaid = form match
      case "scale" => pick(w.scale)
      case "linear" => pick(w.linear)
      case _ => pick(w.lump).replace("{r}", if lang == "pl" then lumpRate.replace('.', ',') else lumpRate)
    val zusSaid = if zus == "full" && rnd.nextBoolean() then "" else pick(w.zus(zus))
    val sickSaid = if sick then pick(w.sickYes) else if rnd.nextInt(3) == 0 then pick(w.sickNo) else ""
    val costsSaid = costs.fold("")(c => pick(w.costs).replace("{c}", money(c)))
    val earned = if byDay then pick(w.day).replace("{r}", money(rate)).replace("{d}", days.toString)
      else pick(w.month).replace("{R}", money(revenue))
    val b2b = Vector(earned, formSaid, zusSaid, sickSaid, costsSaid).filter(_.nonEmpty).mkString(", ")
    val q = contract match
      case "uop" => pick(w.uop).replace("{g}", money(gross))
      case "b2b" => s"$b2b — ${pick(w.b2bAsk)}".capitalize
      case _ => pick(w.compare).replace("{g}", money(gross)).replace("{b2b}", b2b)
    val lines = PayLines.lines(contract, Option.when(contract != "b2b")(BigDecimal(gross)),
      Option.when(!byDay)(BigDecimal(revenue)), Option.when(byDay)((BigDecimal(rate), BigDecimal(days))), form,
      Option.when(form == "lump")(lumpRate), zus, sick, costs.map(BigDecimal(_)))
    (lang, q, lines)

  def rows(rnd: scala.util.Random, n: Int): Vector[String] = Vector.fill(n) {
    val (lang, q, lines) = draw(rnd)
    val (system, user) = PayLines.prompt((), q)
    Json.obj("domain" -> Json.str("pay"), "lang" -> Json.str(lang), "question" -> Json.str(q),
      "system" -> Json.str(system), "user" -> Json.str(user), "lines" -> Json.Arr(lines.map(Json.str))).render }
