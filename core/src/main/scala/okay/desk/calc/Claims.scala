package okay.desk.calc

import okay.desk.*

/**
 * THE ANSWER AS A CONCLUSION (docs/calculus.md §5; the operator: "not a retelling of the data — an inference, one
 * sentence that answers the question"). A claim is what the result LETS ONE CONCLUDE: not the rows but the
 * figure that answers — a count against the whole and its share, the leader with its margin over the next and
 * over the average, the one row that is missing, the leader and the laggard of groups and the gap between them.
 * Every figure in a claim is DERIVED EXACTLY here, from the result and the table it came from; the model's part
 * is which claim answers the question (the question's words, or a weigh) and the words. The law (`ClaimsSuite`):
 * every number in the sentence is a cell or a derivation that recomputes.
 */
object Claims:
  /** a derived figure: its name, its text, and how it was made (recomputable) */
  final case class Derived(name: String, text: String)

  enum Claim:
    /** n of all rows meet the condition, a share */
    case Share(what: String, n: Int, of: Int, percent: Derived)
    /** the leader: its name, its value, how far ahead of the next, and against the average of all */
    case Leader(name: String, column: String, value: String, next: Option[(String, String)], margin: Option[Derived], vsAverage: Option[Derived], desc: Boolean)
    /** one figure over n rows (a sum, an average, a minimum, a maximum) */
    case Figure(fn: String, column: String, value: String, over: Int, extreme: Option[(String, String)])
    /** the first and the last of groups by the measure, the gap */
    case Spread(key: String, measure: String, top: (String, String), bottom: (String, String), gap: Derived, groups: Int)
    /** who is missing: one or a few by name, or how many of all */
    case Missing(target: String, other: String, names: Vector[String], of: Int)
    /** what a node reaches: how many and which */
    case Reach(node: String, forward: Boolean, names: Vector[String])
    /** a plain list when nothing more can be said */
    case Listing(count: Int, of: Int, columns: Vector[String])
    case Nothing

  private def fmt(d: Double): String = if d == d.toLong.toDouble && math.abs(d) < 1e15 then d.toLong.toString else f"$d%.2f".replaceAll("\\.?0+$", "")
  /** a cell's text with a figure rounded as a sentence wants it (463.3333333333333 → 463.33) */
  private def shown(text: String): String = text.toDoubleOption.map(fmt).getOrElse(text)
  private def pct(a: Double, b: Double): String = if b == 0 then "—" else f"${a * 100.0 / b}%.0f%%"

  /** the claim of a query's result; `tables` to count the whole and the average against */
  def of(q: Query, r: Table, tables: Map[String, Table]): Claim =
    if r.rows.isEmpty then Claim.Nothing
    else q match
      case Query.Logical(rules, goal) =>
        if rules.contains(" not ") then
          val target = "^\\w+\\(.*?\\)\\s*:-\\s*(\\w+)\\(".r.findFirstMatchIn(rules).map(_.group(1)).getOrElse("rows")
          val other = "has\\(\\w+\\)\\s*:-\\s*(\\w+)\\(".r.findFirstMatchIn(rules).map(_.group(1)).getOrElse("others")
          Claim.Missing(target, other, r.rows.map(_.head.text), tables.get(target).map(_.size).getOrElse(r.size))
        else
          val args = goal.trim.dropWhile(_ != '(').drop(1).reverse.dropWhile(_ != ')').drop(1).reverse.split(",").map(_.trim.stripPrefix("\"").stripSuffix("\"")).toVector
          val variable = (a: String) => a.matches("[A-Z]\\w*")
          Claim.Reach(args.find(a => !variable(a)).getOrElse(""), args.headOption.exists(a => !variable(a)), r.rows.map(_.head.text))
      case Query.Relational(from, join, where, group, aggs, _, order, limit) =>
        val base = tables.get(from)
        val whole = base.map(_.size).getOrElse(r.size)
        (aggs.headOption, group.headOption) match
          case (Some(a), None) if a.fn == "count" =>
            val n = r.rows.head(r.column(a.as).get).num.map(_.toInt).getOrElse(0)
            Claim.Share(from, n, whole, Derived("share", pct(n, whole)))
          case (Some(a), None) =>
            val value = r.rows.head(r.column(a.as).get)
            // the rows the figure is over: the base filtered as the query filters (a count of them by the same engine)
            val over = Exact.run(tables, Query.Relational(from, join, where, Vector.empty, Vector(Agg("count", "*", "n")), Vector.empty, Vector.empty, None))
              .toOption.flatMap(_.rows.headOption).flatMap(_.head.num).map(_.toInt).getOrElse(r.size)
            // for a min or a max: which row has it
            val extreme = if a.fn == "min" || a.fn == "max" then
              Exact.run(tables, Query.Relational(from, join, where, Vector.empty, Vector.empty, Vector.empty, Vector(Order(a.column, a.fn == "max")), Some(1)))
                .toOption.flatMap(t => t.rows.headOption.map(row => (keyOf(t, row, a.column), value.text))) else None
            Claim.Figure(a.fn, a.column, shown(value.text), over, extreme.map((k, v) => (k, shown(v))))
          case (Some(a), Some(g)) =>
            val gi = r.column(g).get; val mi = r.column(a.as).get
            val sorted = r.rows.sortBy(row => -row(mi).num.getOrElse(0.0))
            val top = sorted.head; val bottom = sorted.last
            val gap = Derived("gap", fmt(top(mi).num.getOrElse(0.0) - bottom(mi).num.getOrElse(0.0)))
            Claim.Spread(g, a.as, (top(gi).text, shown(top(mi).text)), (bottom(gi).text, shown(bottom(mi).text)), gap, r.size)
          case _ if order.nonEmpty =>
            val by = order.head.column.stripPrefix("-"); val desc = order.head.desc
            val first = r.rows.head
            val value = first(r.column(by).get)
            // the next row and the average of all rows the order ran over
            val all = Exact.run(tables, Query.Relational(from, join, where, Vector.empty, Vector.empty, Vector.empty, Vector(Order(by, desc)), None)).toOption.getOrElse(r)
            val next = all.rows.lift(1).map(row => (keyOf(all, row, by), row(all.column(by).get).text))
            val vs = all.rows.flatMap(_(all.column(by).get).num)
            val margin = for n <- all.rows.lift(1); a <- value.num; b <- n(all.column(by).get).num if b != 0 yield Derived("margin", pct(a - b, b))
            val avg = Option.when(vs.size > 1)(Derived("vs average", f"${value.num.getOrElse(0.0) / (vs.sum / vs.size)}%.1f×"))
            Claim.Leader(keyOf(r, first, by), by, shown(value.text), next.map((k, v) => (k, shown(v))), margin, avg, desc)
          case _ =>
            Claim.Listing(r.size, whole, r.columns)

  /** the row's name: the first text column that is not the measure */
  private def keyOf(t: Table, row: Vector[Value], measure: String): String =
    t.columns.zipWithIndex.collectFirst { case (c, i) if c != measure && row(i).num.isEmpty && row(i) != Value.Empty => row(i).text }
      .orElse(t.columns.zipWithIndex.collectFirst { case (c, i) if c != measure => s"$c ${row(i).text}" }).getOrElse(row.head.text)

  /** the sentence: one, answering — in English or Russian */
  def text(c: Claim, ru: Boolean): String = c match
    case Claim.Share(what, n, of, p) => if ru then s"$n из $of $what, то есть ${p.text}." else s"$n of $of $what, that is ${p.text}."
    case Claim.Leader(name, column, value, next, margin, avg, desc) =>
      val lead = if ru then (if desc then "больше всех" else "меньше всех") else (if desc then "the largest" else "the smallest")
      val vsNext = next.map((nn, nv) => margin.fold(if ru then s", следом $nn с $nv" else s", then $nn with $nv")(m =>
        if ru then s", на ${m.text} ${if desc then "больше" else "меньше"}, чем у $nn ($nv)" else s", ${m.text} ${if desc then "more" else "less"} than $nn ($nv)")).getOrElse("")
      val vsAvg = avg.map(a => if ru then s"; ${a.text} от среднего" else s"; ${a.text} the average").getOrElse("")
      if ru then s"У $name $column $lead: $value$vsNext$vsAvg." else s"$name has $lead $column: $value$vsNext$vsAvg."
    case Claim.Figure(fn, column, value, over, extreme) =>
      val what = (fn, ru) match
        case ("sum", true) => s"Сумма $column"; case ("sum", false) => s"The total $column"
        case ("avg", true) => s"Средний $column"; case ("avg", false) => s"The average $column"
        case ("min", true) => s"Наименьший $column"; case ("min", false) => s"The smallest $column"
        case (_, true) => s"Наибольший $column"; case (_, false) => s"The largest $column"
      val rows = if ru then s"по $over строкам" else s"over $over rows"
      val who = extreme.map((k, _) => if ru then s" (у $k)" else s" ($k)").getOrElse("")
      s"$what $rows: $value$who."
    case Claim.Spread(key, measure, (tk, tv), (bk, bv), gap, groups) =>
      if tv == bv then (if ru then s"$measure одинаков во всех $groups группах по $key: $tv." else s"$measure is the same across all $groups $key groups: $tv.")
      else if ru then s"По $measure впереди $tk ($tv), позади $bk ($bv): разрыв ${gap.text} при $groups группах по $key."
      else s"By $measure, $tk leads ($tv) and $bk trails ($bv): a gap of ${gap.text} across $groups $key groups."
    case Claim.Missing(target, other, names, of) =>
      if names.size == 1 then (if ru then s"Только ${names.head} из $of $target без $other." else s"Only ${names.head} of $of $target has no $other.")
      else if ru then s"${names.size} из $of $target без $other: ${names.mkString(", ")}." else s"${names.size} of $of $target have no $other: ${names.mkString(", ")}."
    case Claim.Reach(node, forward, names) =>
      if ru then (if forward then s"Из $node достижимо ${names.size}: ${names.mkString(", ")}." else s"До $node доходят ${names.size}: ${names.mkString(", ")}.")
      else if forward then s"$node reaches ${names.size}: ${names.mkString(", ")}." else s"${names.size} reach $node: ${names.mkString(", ")}."
    case Claim.Listing(count, of, columns) =>
      if ru then s"$count из $of строк (${columns.mkString(", ")})." else s"$count of $of rows (${columns.mkString(", ")})."
    case Claim.Nothing => if ru then "Ничего не найдено." else "Nothing found."

  /** the numbers a claim may state: its cells and its derivations — the law's allowed set */
  def figures(c: Claim): Set[String] = (c match
    case Claim.Share(_, n, of, p) => Vector(n.toString, of.toString, p.text)
    case Claim.Leader(_, _, v, next, m, a, _) => Vector(v) ++ next.map(_._2) ++ m.map(_.text) ++ a.map(_.text)
    case Claim.Figure(_, _, v, over, e) => Vector(v, over.toString) ++ e.map(_._2) ++ e.map(_._1)
    case Claim.Spread(_, _, (_, tv), (_, bv), g, n) => Vector(tv, bv, g.text, n.toString)
    case Claim.Missing(_, _, names, of) => Vector(names.size.toString, of.toString)
    case Claim.Reach(_, _, names) => Vector(names.size.toString)
    case Claim.Listing(n, of, _) => Vector(n.toString, of.toString)
    case Claim.Nothing => Vector.empty).toSet

  /** THE LAW of a sentence: every number in it is one of the claim's figures */
  def readBack(sentence: String, c: Claim): Boolean =
    val allowed = figures(c)
    "-?\\d+(?:[.,]\\d+)?%?".r.findAllIn(sentence.replace("×", " ")).forall(n => allowed.exists(f => f == n || f.contains(n) || n.contains(f.stripSuffix("%"))))
