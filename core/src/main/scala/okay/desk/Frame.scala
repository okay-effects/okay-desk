package okay.desk

/**
 * THE FORMAL MODEL OF A DATA QUESTION, BUILT BY CHOICES (specs/frames.md): a few lines, each chosen among the
 * options the table and the question allow — the model never writes a name or a value, it picks one:
 *
 * {{{
 * table: expenses
 * answer: share sum amount          (rows | count | sum/avg/min/max C | share F C | change F C | cannot)
 * part: category = transport        (share: the part's conditions)
 * from: date month 2026-01          (change: the first period … )
 * to: date month 2026-02            (… and the second)
 * where: payment = card             (conditions on the rows: a value of the column, a number or a date the
 *                                    question writes, a month it names, another column)
 * group: category                   (a column, or month(C), week(C), year(C) of a date)
 * keep: answer > 20                 (a condition on the answer, after grouping)
 * order: answer desc                (the answer or a column, desc or asc)
 * limit: 3
 * end
 * }}}
 *
 * The lines come in that order; each section but `table` and `answer` may be absent, `where`, `part`, `group`
 * repeat. `Frame.query` compiles the lines to a `Query` (exact); `Frame.of` reads a program back into lines where
 * it is one a frame says; `Frame.next` gives the options of the next line.
 */
object Frame:
  val end = "end"

  // ---- the options

  private val textOps = Vector("=", "!=")
  private val numOps = Vector("=", "!=", "<", "<=", ">", ">=")
  private def kind(t: Table, c: String): String =
    val i = t.columns.indexOf(c)
    val vs = t.rows.map(_(i)).filter(_ != Value.Empty)
    if vs.nonEmpty && vs.forall(_.num.isDefined) then "number"
    else if vs.nonEmpty && vs.forall(_.text.matches("\\d{4}-\\d{2}-\\d{2}.*")) then "date"
    else "text"
  private def numbers(question: String): Vector[String] =
    "(?<![\\d.])-?\\d+(?:[.,]\\d+)?(?![\\d])".r.findAllIn(question).map(_.replace(',', '.')).toVector
      .filterNot(_.matches("(19|20)\\d\\d")).distinct
  private def dates(question: String): Vector[String] = "\\d{4}-\\d{2}-\\d{2}".r.findAllIn(question).toVector.distinct
  /** the months a question names, as `yyyy-mm` of the column's own dates */
  private def months(t: Table, c: String, question: String): Vector[String] =
    val ds = t.rows.map(_(t.columns.indexOf(c)).text)
    Months.in(question).flatMap(m => Months.value(m, question, ds)).distinct

  /** the conditions a line may say over a table: `C op V` */
  def conditions(t: Table, question: String): Vector[String] = t.columns.flatMap { c =>
    kind(t, c) match
      case "text" =>
        val vs = t.rows.map(_(t.columns.indexOf(c)).text).filter(_.nonEmpty).distinct
        if vs.size > 40 then Vector.empty else for op <- textOps; v <- vs yield s"$c $op $v"
      case "number" =>
        val others = t.columns.filter(o => o != c && kind(t, o) == "number")
        // the numbers the question writes, the column's own values where they are few (a mark, a term), 0 and 1
        val own = t.rows.map(_(t.columns.indexOf(c)).text).filter(_.nonEmpty).distinct
        val vs = (numbers(question) ++ (if own.size <= 12 then own else Vector.empty) ++ Vector("0", "1")).distinct
        (for op <- numOps; v <- vs yield s"$c $op $v") ++
          (for op <- Vector("<", ">", "<=", ">="); o <- others yield s"$c $op column $o")
      case _ =>
        months(t, c, question).map(m => s"$c month $m") ++
          (for op <- Vector("=", "<", ">=", ">"); d <- dates(question) yield s"$c $op $d")
  }

  def answers(t: Table): Vector[String] =
    val nums = t.columns.filter(kind(t, _) == "number")
    Vector("rows", "count") ++ (for f <- Vector("sum", "avg", "min", "max"); c <- nums yield s"$f $c") ++
      Vector("share count", "change count") ++
      (for k <- Vector("share", "change"); f <- Vector("sum", "avg"); c <- nums yield s"$k $f $c") ++
      Vector("cannot")

  def groups(t: Table): Vector[String] = t.columns.flatMap(c =>
    if kind(t, c) == "date" then Vector(s"month($c)", s"week($c)", s"year($c)", c) else Vector(c))

  private val sections = Vector("table", "answer", "part", "from", "to", "where", "group", "keep", "order", "limit")
  private def head(line: String): String = line.takeWhile(_ != ':')

  /** the options of the next line, whole: the lines taken so far, the tables, the question */
  def next(taken: Vector[String], tables: Map[String, Table], question: String): Vector[String] =
    if taken.isEmpty then tables.keys.toVector.sorted.map(n => s"table: $n")
    else if taken.lastOption.contains(end) then Vector.empty
    else
      val t = tables(taken.head.stripPrefix("table: ").trim)
      val answer = taken.find(_.startsWith("answer: ")).map(_.stripPrefix("answer: "))
      if answer.isEmpty then answers(t).map("answer: " + _)
      else if answer.contains("cannot") then Vector(end)
      else
        val at = sections.indexOf(head(taken.last))
        val share = answer.exists(_.startsWith("share"))
        val change = answer.exists(_.startsWith("change"))
        val conds = conditions(t, question)
        val nums = numbers(question)
        // the sections that may come next: the same one again where it repeats, or any later one
        def lines(s: String): Vector[String] = s match
          case "part" if share => conds.map("part: " + _)
          case "from" if change && !taken.exists(_.startsWith("from: ")) => conds.map("from: " + _)
          case "to" if change && taken.exists(_.startsWith("from: ")) && !taken.exists(_.startsWith("to: ")) =>
            conds.map("to: " + _)
          case "where" => conds.map("where: " + _)
          case "group" if answer.exists(_ != "rows") =>
            groups(t).filterNot(g => taken.contains(s"group: $g")).map("group: " + _)
          case "keep" if !taken.exists(_.startsWith("keep: ")) && answer.exists(_ != "rows") =>
            for op <- Vector(">", "<", ">=", "<=", "="); n <- (nums :+ "0").distinct yield s"keep: answer $op $n"
          case "order" if !taken.exists(_.startsWith("order: ")) =>
            (Option.when(answer.exists(_ != "rows"))("answer").toVector ++ t.columns)
              .flatMap(c => Vector(s"order: $c desc", s"order: $c asc"))
          case "limit" if !taken.exists(_.startsWith("limit: ")) =>
            (nums.filter(_.matches("\\d+")) :+ "1").distinct.map("limit: " + _)
          case _ => Vector.empty
        val repeat = Set("part", "where", "group")
        val from = if repeat(sections(at)) then at else at + 1
        // a change needs both its periods before anything else
        val owed = change && !taken.exists(_.startsWith("to: "))
        val later = sections.drop(from).filter(_ != "table").filter(_ != "answer")
        val open = if !owed then later
          else if taken.exists(_.startsWith("from: ")) then Vector("to") else Vector("from")
        open.flatMap(lines) ++ (if owed then Vector.empty else Vector(end))

  // ---- the frame as a program

  /** a condition's line as the program's conditions: a month is two (on or after its first day, before the next's) */
  private def cond(t: Table, s: String): Either[String, Vector[Cond]] =
    val parts = s.trim.split(" ", 3).toVector
    if parts.size < 3 then Left(s"a condition is a column, an op and a value: $s")
    else
      val (c, op, v) = (parts(0), parts(1), parts(2))
      if t.column(c).isEmpty then Left(s"no column $c")
      else if op == "month" && v.matches("\\d{4}-\\d{2}") then
        val (y, m) = (v.take(4).toInt, v.drop(5).toInt)
        val next = if m == 12 then f"${y + 1}-01-01" else f"$y-${m + 1}%02d-01"
        Right(Vector(Cond.Cmp(c, ">=", Value.Str(v + "-01")), Cond.Cmp(c, "<", Value.Str(next))))
      else if v.startsWith("column ") then Right(Vector(Cond.Col(c, op, v.stripPrefix("column "))))
      else Right(Vector(Cond.Cmp(c, op, Value.of(v))))
  private def conds(t: Table, ss: Vector[String]): Either[String, Vector[Cond]] =
    ss.foldLeft[Either[String, Vector[Cond]]](Right(Vector.empty))((acc, s) =>
      acc.flatMap(got => cond(t, s).map(got ++ _)))

  /** the lines compiled to a program; `answer: cannot` is no program (the question is outside the language) */
  def query(lines: Vector[String], tables: Map[String, Table]): Either[String, Query.Relational] =
    def all(h: String) = lines.filter(_.startsWith(h + ": ")).map(_.stripPrefix(h + ": ").trim)
    for
      name <- all("table").headOption.toRight("no table")
      t <- tables.get(name).toRight(s"no table $name")
      answer <- all("answer").headOption.toRight("no answer")
      _ <- if answer == "cannot" then Left("cannot: outside what the data computes") else Right(())
      where <- conds(t, all("where"))
      part <- conds(t, all("part"))
      from <- conds(t, all("from"))
      to <- conds(t, all("to"))
      keep <- all("keep").headOption.fold[Either[String, Option[(String, Value)]]](Right(None)) { k =>
        k.split(" ").toVector match
          case Vector("answer", op, n) => Right(Some(op -> Value.of(n)))
          case _ => Left(s"keep: $k")
      }
    yield
      val words = answer.split(" ").toVector
      val group = all("group")
      def agg(f: String, c: String, as: String, w: Vector[Cond] = Vector.empty) =
        Agg(f, if f == "count" then "*" else c, as, None, w)
      val (aggs, compute, named) = words match
        case Vector("rows") => (Vector.empty, Vector.empty, "")
        case Vector("count") => (Vector(agg("count", "*", "answer")), Vector.empty, "answer")
        case Vector(f, c) if Set("sum", "avg", "min", "max")(f) => (Vector(agg(f, c, "answer")), Vector.empty, "answer")
        case Vector("share", "count") =>
          (Vector(agg("count", "*", "part", part), agg("count", "*", "whole")),
            Vector(Compute("answer", "part / whole")), "answer")
        case Vector("share", f, c) =>
          (Vector(agg(f, c, "part", part), agg(f, c, "whole")), Vector(Compute("answer", "part / whole")), "answer")
        case Vector("change", "count") =>
          (Vector(agg("count", "*", "before", from), agg("count", "*", "after", to)),
            Vector(Compute("answer", "after - before")), "answer")
        case Vector("change", f, c) =>
          (Vector(agg(f, c, "before", from), agg(f, c, "after", to)), Vector(Compute("answer", "after - before")),
            "answer")
        case _ => (Vector.empty, Vector.empty, "")
      val order = all("order").headOption.map(_.split(" ").toVector).collect {
        case Vector(c, dir) => Order(c, dir == "desc") }.toVector
      val select = if named.isEmpty then Vector.empty
        else group.map(g => "(month|week|year)\\((.+)\\)".r.findFirstMatchIn(g).fold(g)(_.group(1))) :+ named
      Query.Relational(t.name, None, where, group, aggs, select, order,
        all("limit").headOption.flatMap(_.toIntOption), compute,
        keep.toVector.map((op, v) => Cond.Cmp("answer", op, v)))

  /** a program read back into a frame's lines, where it is one a frame says (one table, one aggregate, the
   * conditions a frame has) — the training texts made from the teacher's and the grammar's programs */
  def of(q: Query, tables: Map[String, Table]): Option[Vector[String]] = q match
    case r: Query.Relational if r.join.isEmpty && r.compute.isEmpty && r.having.isEmpty && r.aggs.size <= 1 &&
        r.aggs.forall(a => a.where.isEmpty && Set("count", "sum", "avg", "min", "max")(a.fn)) =>
      tables.get(r.from).flatMap { t =>
        val answer = r.aggs.headOption.fold("rows")(a => if a.fn == "count" then "count" else s"${a.fn} ${a.column}")
        val alias = r.aggs.headOption.map(_.as)
        def said(cs: List[Cond]): Option[Vector[String]] = cs match
          case Cond.Cmp(c1, ">=", Value.Str(a)) :: Cond.Cmp(c2, "<", Value.Str(b)) :: rest
              if c1 == c2 && a.matches("\\d{4}-\\d{2}-01") && b.matches("\\d{4}-\\d{2}-01") =>
            said(rest).map(s"$c1 month ${a.take(7)}" +: _)
          case Cond.Cmp(c, op, v) :: rest => said(rest).map(s"$c $op ${v.text}" +: _)
          case Cond.Col(c, op, o) :: rest => said(rest).map(s"$c $op column $o" +: _)
          case Nil => Some(Vector.empty)
          case _ => None
        val orders = r.order.map(o => (if alias.contains(o.column) then "answer" else o.column) +
          (if o.desc then " desc" else " asc"))
        Option.when(r.order.size <= 1 && r.group.forall(g => t.column(g).isDefined || g.contains("(")))(()).flatMap(_ =>
          said(r.where.toList).map { ws =>
            Vector(s"table: ${t.name}", s"answer: $answer") ++ ws.map("where: " + _) ++ r.group.map("group: " + _) ++
              orders.map("order: " + _) ++ r.limit.map(n => s"limit: $n") :+ end
          })
      }
    case _ => None

  /** the frame's language in words, for the model's prompt */
  val rule: String =
    """You build the formal model of a question about a table, a line at a time, each line one of the options:
      |table: the table; answer: rows | count | sum/avg/min/max COLUMN | share F COLUMN (a part of the whole: the
      |part's conditions in "part:" lines) | change F COLUMN (between two periods or groups: "from:" then "to:") |
      |cannot (a cause, a forecast, anything the data does not compute); where: a condition on the rows (a value of
      |the column; a month: "date month 2026-03"; another column: "quantity < column reorder_level"); group: a column
      |or month(date), week(date), year(date); keep: a condition on the answer after grouping; order: the answer or
      |a column, desc or asc; limit: a number of rows; end.""".stripMargin

  /** the prompt's text: the rule, the table with its values, the question */
  def prompt(tables: Map[String, Table], question: String): (String, String) =
    (rule, tables.values.toVector.sortBy(_.name).map(_.describe).mkString("\n") + s"\n\nQuestion: $question")
