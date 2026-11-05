package okay.desk.skill

import okay.desk.*

/**
 * THE SKILL AS READERS AND AN ASSEMBLER (specs/data.md §7, §9, §10): the model untouched; probes trained on the
 * pairs read the program's slots from its states where it decides them — the table, the shape, the columns, the
 * op, and the START of a condition's value among the question's tokens (the quote mechanism: copied from there,
 * not generated); the program is assembled — exact, well-formed by construction. `program` answers a question the
 * way the page's loop would, with no generation at all.
 */
final class Readers(reading: Reading):
  import Probes.*
  private var mean: Array[Float] = Array.empty; private var std: Array[Float] = Array.empty; private var scale = 1f
  private def z(x: Array[Float]): Array[Float] =
    Array.tabulate(x.length)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))) * scale)
  private var shape: Softmax = null
  private var op: Softmax = null
  private var fnProbe: Softmax = null
  val fns = Vector("count", "sum", "avg", "min", "max")
  private def fnPrefix(t: String) = "```json\n{\"from\": \"" + t + "\", \"aggregate\": [{\"fn\": \""
  private val pointers = scala.collection.mutable.Map.empty[String, Pointer]
  val ops = Vector("=", "!=", "<", "<=", ">", ">=", "contains")
  private def opPrefix(t: String, c: String) = "```json\n{\"from\": \"" + t + "\", \"where\": [{\"column\": \"" + c + "\", \"op\": \""
  private def valuePrefix(t: String, c: String, o: String) = opPrefix(t, c) + o + "\", \"value\": "

  private def query(pr: Prompted, prefix: String): Array[Float] =
    val ids = pr.ids ++ reading.encode(prefix)
    reading.states(ids, Vector(ids.length - 1)).head
  private def keys(pr: Prompted): Vector[Array[Float]] =
    reading.states(pr.ids, pr.names.map(_._2))
  private def questionStates(pr: Prompted): Vector[Array[Float]] =
    reading.states(pr.ids, pr.question)

  /** ASK — the second reader (bridge-4's lesson: two readers fail on different phrasings; their sum read 100%): the
   * model's own next-token log-probabilities at the prefix's end, over the candidates' first tokens */
  private def ask(pr: Prompted, prefix: String, candidates: Vector[String]): Array[Float] =
    val ids = pr.ids ++ reading.encode(prefix)
    val l = reading.logits(ids)
    val m = l.max; val z = math.log(l.map(v => math.exp((v - m).toDouble)).sum)
    candidates.map(c => (l(reading.encode(c).head) - m - z).toFloat).toArray
  private def logSoftmax(xs: Array[Float]): Array[Float] =
    val m = xs.max; val z = math.log(xs.map(v => math.exp((v - m).toDouble)).sum)
    xs.map(v => (v - m - z).toFloat)
  /** both readers summed: the pointer's log-probabilities over the candidates and the model's */
  // `candidates`: (table, column) pairs — the index found within the table
  private def both(pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): String =
    val scored = bothScores(pr, prefix, pointer, q, ks, candidates)
    candidates(scored.zipWithIndex.maxBy(_._1)._2)._2
  /** the two readers' summed log-probabilities per candidate */
  private def bothScores(pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): Array[Float] =
    val names = candidates.map(_._2)
    val cks = candidates.map((t, c) => ks(pr.indexOf(t, c)))
    val ps = logSoftmax(pointer.scores(q, cks).take(cks.size))
    val as = ask(pr, prefix, names)
    // the two readers disagreeing is remembered: the self-play judge takes only answers they agreed on
    if ps.zipWithIndex.maxBy(_._1)._2 != as.zipWithIndex.maxBy(_._1)._2 then agreedAll = false
    ps.zip(as).map((a, b) => a + b)
  private var agreedAll = true
  /** the program, and whether every slot the two readers both read was read alike */
  def programWith(tables: Map[String, Table], question: String): Option[(String, Boolean)] =
    agreedAll = true
    program(tables, question).toOption.map(p => (p, agreedAll))

  /** the gold value's first token among the question's, if the value is written verbatim in the question */
  private def valueStart(pr: Prompted, value: String): Option[Int] =
    val at = pr.qText.toLowerCase.indexOf(value.toLowerCase)
    Option.when(at >= 0)(pr.qChars.lastIndexWhere(_ <= at)).filter(_ >= 0)

  def train(pairs: Vector[Pairs.Pair]): Unit =
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(reading, p))))
    val base = exs.map((_, sl, pr) => (sl, pr, reading.states(pr.ids, (pr.ids.length - 1) +: pr.names.map(_._2))))
    val all = base.flatMap(_._3)
    val d = all.head.length
    mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
    std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
    scale = 8f / math.sqrt(d).toFloat
    val keyStates = base.map(_._3.tail.map(z))
    val table = Pointer(d)
    table.train(base.zip(keyStates).map { case ((sl, pr, st), ks) => (z(st.head), ks, pr.indexOfTable(sl.table)) })
    pointers("table") = table
    for (name, prefix) <- prefixes do
      val qs = exs.map((_, sl, pr) => z(query(pr, prefix(sl.table))))
      if name == "shape" then
        shape = Softmax(exs.map(_._2.shape).distinct, d)
        shape.train(qs.zip(exs).map((q, e) => (q, e._2.shape)), steps = 300, lr = 0.05f)
      else
        val p = Pointer(d)
        val slot: Slots => Option[String] = name match
          case "where" => _.where
          case "group" => _.group
          case "agg" => _.agg
          case _ => _.order
        // trained over the table's own columns (and none): other tables' names would only dilute the choice
        p.train(qs.zip(exs).zip(keyStates).map { case ((q, (p0, sl, pr)), ks) =>
          val cols = p0.tables(sl.table).columns
          (q, cols.map(c => ks(pr.indexOf(sl.table, c))), slot(sl).map(cols.indexOf).filter(_ >= 0).getOrElse(cols.size)) })
        pointers(name) = p
    // the op and the value's start, on the pairs that have a condition
    val withWhere = exs.flatMap { (p, sl, pr) =>
      Query.parse(p.program).toOption.collect { case Query.Relational(_, _, Cond.Cmp(c, o, v) +: _, _, _, _, _, _) => (p, sl, pr, c, o, v.text) }
    }
    // the aggregate's function read at its own point, where it is written
    fnProbe = Softmax(fns :+ "-", d)
    fnProbe.train(exs.map((_, sl, pr) => (z(query(pr, fnPrefix(sl.table))), Readers.part(sl.shape, 1))), steps = 300, lr = 0.05f)
    op = Softmax(ops, d)
    op.train(withWhere.map((_, sl, pr, c, o, _) => (z(query(pr, opPrefix(sl.table, c))), o)), steps = 300, lr = 0.05f)
    val value = Pointer(d)
    val valueEx = withWhere.flatMap { (_, sl, pr, c, o, v) =>
      valueStart(pr, v).map(start => (z(query(pr, valuePrefix(sl.table, c, o))), questionStates(pr).map(z), start))
    }
    value.train(valueEx, steps = 150, lr = 0.05f)
    pointers("value") = value
    println(s"  readers: ${exs.size} pairs, ${withWhere.size} with a condition, ${valueEx.size} with the value in the question")

  private val numberWords = Map("one" -> 1, "two" -> 2, "three" -> 3, "four" -> 4, "five" -> 5, "ten" -> 10,
    "один" -> 1, "одна" -> 1, "два" -> 2, "две" -> 2, "три" -> 3, "четыре" -> 4, "пять" -> 5, "десять" -> 10)

  /** the question answered by the readers: the program's text, or why not */
  def program(tables: Map[String, Table], question: String): Either[String, String] =
    val pr = prompted(reading, Pairs.Pair(tables, question, ""))
    val names = pr.names.map(_._1)
    val ks = keys(pr).map(z)
    val last = z(reading.states(pr.ids, Vector(pr.ids.length - 1)).head)
    val ti = pointers("table").predict(last, ks)
    if ti >= names.size then Left("no table read")
    else
      val table = names(ti)
      val t = tables(table)
      val lower = question.toLowerCase
      // the shape by two readers: the probe, and the model asked which section comes first at `{"from": T, "`
      val shapeQ = z(query(pr, prefixes.head._2(table)))
      val probeScores = logSoftmax(shape.scores(shapeQ))
      def q(s: String) = "\"" + s + "\""
      // EXACT FACTS FROM THE DATA constrain the reads: a value of a column written in the question (a word of 3+ letters,
      // a date) means a condition on that column — and a join, when the column is in another table reachable by one
      def joinTo(o: Table): Option[String] =
        t.columns.find(c => t.rows.forall(r => o.rows.exists(_(0).text == r(t.columns.indexOf(c)).text)))
      val found: Vector[(Table, String, String)] = tables.values.toVector.flatMap { o =>
        o.columns.flatMap { c =>
          val vs = o.rows.map(_(o.columns.indexOf(c)).text).distinct
          vs.filter(v => (v.length >= 3 && !v.forall(_.isDigit) || v.length >= 2 && v.forall(_.isUpper) || v.matches("\\d{4}-\\d\\d-\\d\\d")) &&
            lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(v.toLowerCase)}\\b.*")).map(v => (o, c, v))
        }
      }.filter((o, _, _) => o.name == table || joinTo(o).isDefined).sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length))
      // every fact a condition; the first the "fact" (the join and the where column)
      val facts = found.distinctBy(_._2)
      val fact = found.headOption
      // a join only when the data needs one: a fact in another table, or a column of another table named in the question
      val join = facts.find(_._1.name != table).flatMap((o, _, _) => joinTo(o).map(c => (o, c))).orElse(
        tables.values.filter(_.name != table).flatMap(o => joinTo(o).filter(_ => o.columns.exists(c =>
          c.length >= 3 && lower.matches(s"(?U).*\\b${c.toLowerCase}\\b.*"))).map(c => (o, c))).headOption)
      // THE SHAPE AS SEPARATE DECISIONS, each exact where the question says it, read where it does not: the probe's
      // marginals over its compound classes are the fallback
      val probs = probeScores.map(v => math.exp(v.toDouble))
      def marginal(p: String => Boolean): Double = shape.classes.zipWithIndex.collect { case (c, i) if p(c) => probs(i) }.sum
      // a word (or a stem ending in *) at a word boundary: "count" must not match "country"
      def word(ws: String*): Boolean = ws.exists(w => lower.matches(s"(?U).*\\b${w.stripSuffix("*")}${if w.endsWith("*") then "\\w*" else ""}\\b.*"))
      val closure = marginal(_.startsWith("closure")) > 0.5 || word("reach*", "depend*", "lead*", "достиж*", "завис*", "вед*", "доход*")
      val closureTo = (word("reach", "reaches", "lead*", "ведут", "ведёт", "доход*") && !word("reachable", "достижим*"))
      val fnAsk = ask(pr, fnPrefix(table), fns)
      val fnProbeScores = logSoftmax(fnProbe.scores(z(query(pr, fnPrefix(table)))))
      val fnByReaders = (fns :+ "-")(fnProbeScores.zipWithIndex.map((s, i) => (if i < fns.size then s + fnAsk(i) else s, i)).maxBy(_._1)._2)
      val topN = word("top", "топ", "three", "five", "ten", "три", "пять", "десять")
      val perEach = word("per", "each", "по кажд*", "кажд*", "в разрезе", "по стат*", "по город*", "по клиент*", "по стран*")
      val fn = if word("how many", "number of", "count", "сколько", "число", "посчитай") then "count"
        else if word("average", "mean", "средн*") then "avg"
        else if topN && !perEach then "-"
        else if word("total", "sum of", "сумм*", "итого") then "sum"
        else if word("largest", "highest", "maximum", "самый больш*", "наибольш*", "максим*") && !perEach then "-"
        else fnByReaders
      val hasGroup = perEach || word("by") && fn != "-" || (fn != "-" && fn != "count" && marginal(c => Readers.part(c, 3) == "group") > 0.5)
      val hasOrder = word("largest", "smallest", "highest", "lowest", "descending", "first", "top", "самы*", "наибольш*", "по убыван*", "по возраст*", "топ") || marginal(c => Readers.part(c, 4) == "order") > 0.6
      val hasLimit = topN || (word("largest", "smallest", "highest", "самый", "наибольш*") && fn == "-" && !hasGroup) || marginal(c => Readers.part(c, 5) == "limit") > 0.6
      val hasSelect = marginal(c => Readers.part(c, 7) == "select") > 0.5
      // a number with a comparison word is a condition on a numeric column
      val numberCond = "\\d+(?:\\.\\d+)?".r.findFirstIn(question).filter(_ => word("over", "above", "more than", "greater", "больше", "выше", "свыше", "below", "under", "less than", "меньше", "ниже", "до"))
      val hasWhere = fact.isDefined || numberCond.isDefined || marginal(c => Readers.part(c, 2) != "-") > 0.3
      val sh = if closure then Array(if closureTo then "closure-to" else "closure-from")
        else Array("rel", fn, if hasWhere then "=" else "-", if hasGroup then "group" else "-", if hasOrder then "order" else "-",
          if hasLimit then "limit" else "-", "-", if hasSelect then "select" else "-")
      // the joinable tables' columns are candidates too (a translated column may be theirs: "по странам" → country): a
      // choice among them brings the join
      val joinable = tables.values.toVector.filter(_.name != table).flatMap(o => joinTo(o).map(c => (o, c)))
      var joinNow: Option[(Table, String)] = join
      val cols = t.columns.map(c => (table, c)) ++ joinable.flatMap((o, _) => o.columns.filterNot(c => t.columns.contains(c) || c == "name").map(c => (o.name, c)))
      // EXACT FIRST: a column named in the question (its name, a word of 3+ letters) is the column — the readers decide
      // only among what the question does not name (a translation: "по городам" for city)
      val named = cols.filter((_, c) => c.length >= 3 && lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(c.toLowerCase)}\\b.*"))
      val used = scala.collection.mutable.Set.empty[String]
      def column(slot: String): Option[String] =
        val prefix = prefixes.find(_._1 == slot).get._2(table)
        // a numeric column for agg and order, a text one for group; a named column not yet used
        def numeric(c: String) = cols.find(_._2 == c).exists((tb, _) => tables(tb).column(c).exists(i => tables(tb).rows.exists(_(i).num.isDefined)))
        val exact = named.map(_._2).filterNot(used).filter(c => if slot == "agg" || slot == "order" then numeric(c) else if slot == "group" then !numeric(c) else true)
        // a key column ("id") sorts, aggregates or groups nothing unless the question names it: the readers choose among the rest
        val keyLike = Set("id", "key", "uid")
        val pool0 = if named.exists((_, c) => keyLike(c)) then cols else cols.filterNot((_, c) => keyLike(c) && slot != "where")
        // an aggregate or an order is over a numeric column, a group over a text one: the readers choose among those only
        // a joined table's columns only for a group ("по странам"): a condition, an order or an aggregate stay on the table's
        // own unless a fact of the data brought the join
        val pool1 = if slot == "group" || joinNow.isDefined then pool0 else pool0.filter((tb, _) => tb == table)
        val pool = if slot == "agg" || slot == "order" then pool1.filter((_, c) => numeric(c)) else if slot == "group" then pool1.filterNot((_, c) => numeric(c)) else pool1
        // the table's own columns first; a joined table's column only when the readers prefer it by a clear margin
        val chosen = exact.headOption.getOrElse {
          val cands = if pool.nonEmpty then pool else cols
          val scores = bothScores(pr, prefix, pointers(slot), z(query(pr, prefix)), ks, cands)
          val own = cands.zipWithIndex.filter((tc, _) => tc._1 == table); val other = cands.zipWithIndex.filter((tc, _) => tc._1 != table)
          val ownBest = own.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1); val otherBest = other.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1)
          (ownBest, otherBest) match
            case (Some((so, co)), Some((st, ct))) => if st > so + 3f then ct else co
            case (Some((_, co)), None) => co
            case (None, Some((_, ct))) => ct
            case _ => cands.head._2 }
        used += chosen
        if !t.columns.contains(chosen) then joinNow = joinNow.orElse(joinable.find(_._1.columns.contains(chosen)))
        Some(chosen)
      /** the value: from the read start among the question's tokens, the longest value of the column (or a number or
       * a date) that begins there; else by exact match anywhere in the question */
      def constant(col: String, o: String): Option[String] =
        val owner = if t.columns.contains(col) then t else joinNow.map(_._1).orElse(tables.values.find(_.columns.contains(col))).getOrElse(t)
        val vs = owner.rows.map(_(owner.columns.indexOf(col)).text).distinct.filter(_.nonEmpty).sortBy(-_.length)
        val qs = questionStates(pr).map(z)
        val start = pointers("value").predict(z(query(pr, valuePrefix(table, col, o))), qs)
        val from = if start < pr.qChars.size then pr.qChars(start) else 0
        val rest = question.drop(from)
        vs.find(v => rest.toLowerCase.startsWith(v.toLowerCase))
          .orElse("^\\s*(\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?)".r.findFirstMatchIn(rest).map(_.group(1)))
          .orElse(vs.find(v => lower.contains(v.toLowerCase)))
          .orElse("\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?".r.findAllIn(question).toVector.lastOption)
          // nothing copied (a translated value, "не оплачено"): the model asked over the column's values
          .orElse(Option.when(vs.nonEmpty && vs.size <= 30)(vs(ask(pr, valuePrefix(table, col, o) + "\"", vs).zipWithIndex.maxBy(_._1)._2)))
      def number: Option[Int] = "\\d+".r.findFirstIn(question).map(_.toInt)
        .orElse(numberWords.collectFirst { case (w, n) if lower.matches(s"(?U).*\\b$w\\b.*") => n })
      if sh.head == "closure-from" || sh.head == "closure-to" then
        val e = tables.values.find(_.columns.size == 2).getOrElse(t)
        val nodes = e.rows.flatten.map(_.text).distinct.sortBy(-_.length)
        nodes.find(n => lower.contains(n.toLowerCase)).toRight(s"no node named in the question (table $table, shape ${sh.mkString(" ")})").map { c =>
          val goal = if sh.head == "closure-from" then s"reach(${Logic.constant(c)}, X)" else s"reach(X, ${Logic.constant(c)})"
          s"""{"datalog": "reach(X, Y) :- ${e.name}(X, Y). reach(X, Z) :- ${e.name}(X, Y), reach(Y, Z).", "goal": ${q(goal.replace("\"", "\\\""))}}"""
        }
      else
        val Array(_, fn, whereOp0, group, order, limit, _, select) = sh
        // the fact forces the condition the shape may have missed
        val whereOp = if whereOp0 == "-" && fact.isDefined then "=" else whereOp0
        val parts = Vector.newBuilder[String]
        parts += s""""from": ${q(table)}"""
        // (the join after the columns are chosen: a column of a joinable table brings it)
        val joinSlot = parts.result().size
        // the columns in the order that disambiguates them: the aggregate's (numeric) and the order's first, then the group's
        // (text), then the condition's (the fact's column, or the numeric one of a number condition)
        val aggCol = if fn != "-" && fn != "count" then column("agg") else None
        val orderCol = if hasOrder && !(fn != "-" && fn != "count" && hasGroup) then column("order") else None
        val groupCol = if hasGroup then column("group") else None
        val whereCol = if whereOp == "-" then None else fact.map(_._2).map { c => used += c; c }
          // a number condition: the numeric column whose values' range holds the number (not the key "id" by accident)
          .orElse(numberCond.flatMap { n => val x = n.toDouble
            val numericCols = t.columns.filterNot(used).filter(c => t.column(c).exists(i => t.rows.exists(_(i).num.isDefined) && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
            numericCols.find(c => named.exists(_._2 == c)).orElse(numericCols.find { c => val vs = t.rows.flatMap(_(t.column(c).get).num); vs.nonEmpty && vs.min <= x && x <= vs.max })
              .orElse(numericCols.filterNot(_ == "id").headOption).orElse(numericCols.headOption) })
          .orElse(column("where"))
        // the first condition, then one per further fact
        val conds = Vector.newBuilder[String]
        whereCol.foreach { c =>
          val o = if whereOp == "empty" then "empty" else if fact.exists(_._2 == c) && !lower.matches("(?U).*\\b(not|не|кроме|except)\\b.*") then "="
            else if numberCond.isDefined then (if word("over", "above", "more than", "greater", "больше", "выше", "свыше") then ">" else "<")
            else
              val ps = logSoftmax(op.scores(z(query(pr, opPrefix(table, c))))); val as = ask(pr, opPrefix(table, c), ops)
              ops(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)
          val v = fact.filter(_._2 == c).map(_._3).orElse(numberCond).orElse(constant(c, o)).getOrElse("")
          conds += s"""{"column": ${q(c)}, "op": ${q(o)}, "value": ${if v.matches("-?\\d+(\\.\\d+)?") && o != "=" then v else q(v)}}""" }
        for (_, c, v) <- facts.drop(1) if !whereCol.contains(c) do conds += s"""{"column": ${q(c)}, "op": "=", "value": ${q(v)}}"""
        val cs = conds.result()
        if cs.nonEmpty then parts += s""""where": [${cs.mkString(", ")}]"""
        if group == "group" then groupCol.foreach(c => parts += s""""group": [${q(c)}]""")
        if fn != "-" then
          val c = if fn == "count" then Some("*") else aggCol
          c.foreach(col => parts += s""""aggregate": [{"fn": ${q(fn)}, "column": ${q(col)}, "as": ${q(if col == "*" then "n" else fn)}}]""")
        if order == "order" then
          val c = if fn != "-" && fn != "count" && group == "group" then Some(fn) else orderCol
          c.foreach(col => parts += s""""order": [${q("-" + col)}]""")
        if limit == "limit" then parts += s""""limit": ${number.getOrElse(1)}"""
        if select == "select" then orderCol.foreach(c => parts += s""""select": ["id", ${q(c)}]""")
        val built = parts.result()
        val withJoin = joinNow.fold(built)((o, c) => built.take(joinSlot) ++ Vector(s""""join": {"table": ${q(o.name)}, "on": [[${q(c)}, ${q(o.columns.head)}]]}""") ++ built.drop(joinSlot))
        Right(withJoin.mkString("{", ", ", "}"))

  /** the readers measured on held-out pairs, slot by slot: the pointer alone against the pointer with ask — so a
   * change is judged on 200 pairs, not on the bench's 12 questions */
  def measure(pairs: Vector[Pairs.Pair]): Unit =
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(reading, p))))
    var shapeRight = 0
    val right = scala.collection.mutable.Map.empty[String, (Int, Int, Int, Int)].withDefaultValue((0, 0, 0, 0))
    for (p, sl, pr) <- exs do
      val ks = keys(pr).map(z)
      val t = p.tables(sl.table)
      if shape.predict(z(query(pr, prefixes.head._2(sl.table)))) == sl.shape then shapeRight += 1
      for (name, prefix) <- prefixes.tail do
        val gold = (name match { case "where" => sl.where; case "group" => sl.group; case "agg" => sl.agg; case _ => sl.order })
        gold.foreach { g =>
          val q = z(query(pr, prefix(sl.table)))
          val cands = t.columns.map(c => (sl.table, c))
          val cks = cands.map((tb, c) => ks(pr.indexOf(tb, c)))
          val sc = pointers(name).scores(q, cks).take(cks.size)
          val alone = t.columns(sc.zipWithIndex.maxBy(_._1)._2)
          val asked = ask(pr, prefix(sl.table), t.columns)
          val byAsk = t.columns(asked.zipWithIndex.maxBy(_._1)._2)
          val withAsk = both(pr, prefix(sl.table), pointers(name), q, ks, cands)
          val (n, a, k, b) = right(name)
          right(name) = (n + 1, a + (if alone == g then 1 else 0), k + (if byAsk == g then 1 else 0), b + (if withAsk == g then 1 else 0))
        }
    println(f"  measured on ${exs.size} held-out pairs: shape ${shapeRight * 100.0 / exs.size}%.1f%%; " +
      right.toVector.sortBy(_._1).map((k, v) => f"$k pointer ${v._2 * 100.0 / v._1}%.0f%% ask ${v._3 * 100.0 / v._1}%.0f%% both ${v._4 * 100.0 / v._1}%.0f%% (${v._1})").mkString(", "))

  // ---- the readers as bytes (a skill is a file, read on the JVM and in the browser alike): a header, then named
  // tensors — a text line "name length" and float32 LE; the classes of a probe as a text line
  def bytes: Array[Byte] =
    val out = new java.io.ByteArrayOutputStream
    def line(s: String): Unit = out.write((s + "\n").getBytes("UTF-8"))
    def tensor(name: String, xs: Array[Float]): Unit =
      line(s"$name ${xs.length}")
      val b = java.nio.ByteBuffer.allocate(xs.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      xs.foreach(b.putFloat); out.write(b.array())
    def probe(name: String, p: Softmax): Unit =
      line(s"$name.classes ${p.classes.mkString("\t")}"); tensor(s"$name.w", p.w.flatten); tensor(s"$name.b", p.b)
    line(s"readers ${mean.length} ${pointers.size}")
    tensor("mean", mean); tensor("std", std); tensor("scale", Array(scale))
    probe("shape", shape); probe("op", op); probe("fn", fnProbe)
    for (k, p) <- pointers.toVector.sortBy(_._1) do
      line(s"pointer $k ${p.wq.length}"); tensor(s"$k.wq", p.wq.flatten); tensor(s"$k.wk", p.wk.flatten); tensor(s"$k.none", p.none)
    out.toByteArray

  def load(bytes: Array[Byte]): Unit =
    var at = 0
    def line(): String =
      val start = at
      while bytes(at) != '\n'.toByte do at += 1
      at += 1
      new String(bytes, start, at - 1 - start, "UTF-8")
    def tensor(): Array[Float] =
      val len = line().split(" ").last.toInt
      val b = java.nio.ByteBuffer.wrap(bytes, at, len * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      at += len * 4
      Array.fill(len)(b.getFloat)
    def probe(): Softmax =
      val classes = line().split(" ", 2).last.split("\t").toVector
      val w = tensor(); val b = tensor()
      val p = Softmax(classes, w.length / classes.size)
      w.grouped(w.length / classes.size).zipWithIndex.foreach((row, i) => row.copyToArray(p.w(i))); b.copyToArray(p.b)
      p
    val head = line().split(" ")
    val d = head(1).toInt; val n = head(2).toInt
    mean = tensor(); std = tensor(); scale = tensor()(0)
    shape = probe(); op = probe(); fnProbe = probe()
    for _ <- 1 to n do
      val h = line().split(" "); val k = h(1); val rows = h(2).toInt
      val wq = tensor(); val wk = tensor(); val none = tensor()
      val p = Pointer(d, rows)
      wq.grouped(d).zipWithIndex.foreach((row, i) => row.copyToArray(p.wq(i))); wk.grouped(d).zipWithIndex.foreach((row, i) => row.copyToArray(p.wk(i)))
      none.copyToArray(p.none); pointers(k) = p

object Readers:
  /** a part of a compound shape class; "-" where the class has none (a closure) */
  def part(shape: String, i: Int): String = shape.split(" ").lift(i).getOrElse("-")
