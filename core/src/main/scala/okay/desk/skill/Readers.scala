package okay.desk.skill

import okay.desk.*
import okay.scala3.*

/**
 * THE SKILL AS READERS AND AN ASSEMBLER (specs/data.md §7, §9, §10): the model untouched; probes trained on the
 * pairs read the program's slots from its states where it decides them — the table, the shape, the columns, the
 * op, and the START of a condition's value among the question's tokens (the quote mechanism: copied from there,
 * not generated); the program is assembled — exact, well-formed by construction. `program` answers a question the
 * way the page's loop would, with no generation at all. Every method is a program over `Read` (§15 step 4): the
 * JVM runs it in place (`Read.run`), the browser by futures (`Read.future`) — one skill, both platforms.
 */
final class Readers:
  import Probes.*
  import Read.each
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

  /** the state at the end of the prompt followed by `prefix` — where the model decides the slot */
  private def query[R[_]: Has[Read]](pr: Prompted, prefix: String): Array[Float] ! R =
    for
      more <- Read.encode(prefix)
      ids = pr.ids ++ more
      st <- Read.states(ids, Vector(ids.length - 1))
    yield st.head
  private def keys[R[_]: Has[Read]](pr: Prompted): Vector[Array[Float]] ! R = Read.states(pr.ids, pr.names.map(_._2))
  private def questionStates[R[_]: Has[Read]](pr: Prompted): Vector[Array[Float]] ! R = Read.states(pr.ids, pr.question)

  /** ASK — the second reader (bridge-4's lesson: two readers fail on different phrasings; their sum read 100%): the
   * model's own next-token log-probabilities at the prefix's end, over the candidates' first tokens */
  private def ask[R[_]: Has[Read]](pr: Prompted, prefix: String, candidates: Vector[String]): Array[Float] ! R =
    for
      more <- Read.encode(prefix)
      l <- Read.logits(pr.ids ++ more)
      firsts <- each(candidates)(c => Read.encode(c).map(_.head))
    yield
      val m = l.max; val z = math.log(l.map(v => math.exp((v - m).toDouble)).sum)
      firsts.map(f => (l(f) - m - z).toFloat).toArray
  private def logSoftmax(xs: Array[Float]): Array[Float] =
    val m = xs.max; val z = math.log(xs.map(v => math.exp((v - m).toDouble)).sum)
    xs.map(v => (v - m - z).toFloat)
  /** both readers summed: the pointer's log-probabilities over the candidates and the model's */
  // `candidates`: (table, column) pairs — the index found within the table
  private def both[R[_]: Has[Read]](pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): String ! R =
    bothScores(pr, prefix, pointer, q, ks, candidates).map(scored => candidates(scored.zipWithIndex.maxBy(_._1)._2)._2)
  /** the two readers' summed log-probabilities per candidate */
  private def bothScores[R[_]: Has[Read]](pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): Array[Float] ! R =
    val names = candidates.map(_._2)
    val cks = candidates.map((t, c) => ks(pr.indexOf(t, c)))
    val ps = logSoftmax(pointer.scores(q, cks).take(cks.size))
    ask(pr, prefix, names).map { as =>
      // the two readers disagreeing is remembered: the self-play judge takes only answers they agreed on
      if ps.zipWithIndex.maxBy(_._1)._2 != as.zipWithIndex.maxBy(_._1)._2 then agreedAll = false
      ps.zip(as).map((a, b) => a + b)
    }
  private var agreedAll = true
  /** the program, and whether every slot the two readers both read was read alike */
  def programWith[R[_]: Has[Read]](tables: Map[String, Table], question: String): Option[(String, Boolean)] ! R =
    agreedAll = true
    program(tables, question).map(_.toOption.map(p => (p, agreedAll)))

  /** the gold value's first token among the question's, if the value is written verbatim in the question */
  private def valueStart(pr: Prompted, value: String): Option[Int] =
    val at = pr.qText.toLowerCase.indexOf(value.toLowerCase)
    Option.when(at >= 0)(pr.qChars.lastIndexWhere(_ <= at)).filter(_ >= 0)

  def train[R[_]: Has[Read]](pairs: Vector[Pairs.Pair]): Unit ! R =
    for
      exs <- each(pairs.flatMap(p => slots(p.program).map(sl => (p, sl))))((p, sl) => prompted(p).map(pr => (p, sl, pr)))
      base <- each(exs)((_, sl, pr) => Read.states(pr.ids, (pr.ids.length - 1) +: pr.names.map(_._2)).map(st => (sl, pr, st)))
      keyStates =
        val all = base.flatMap(_._3)
        val d = all.head.length
        mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
        std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
        scale = 8f / math.sqrt(d).toFloat
        val keyStates = base.map(_._3.tail.map(z))
        val table = Pointer(d)
        table.train(base.zip(keyStates).map { case ((sl, pr, st), ks) => (z(st.head), ks, pr.indexOfTable(sl.table)) })
        pointers("table") = table
        keyStates
      d = mean.length
      _ <- each(prefixes) { (name, prefix) =>
        each(exs)((_, sl, pr) => query(pr, prefix(sl.table)).map(z)).map { qs =>
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
        }
      }
      // the op and the value's start, on the pairs that have a condition
      withWhere = exs.flatMap { (p, sl, pr) =>
        Query.parse(p.program).toOption.collect { case Query.Relational(_, _, Cond.Cmp(c, o, v) +: _, _, _, _, _, _) => (p, sl, pr, c, o, v.text) }
      }
      // the aggregate's function read at its own point, where it is written
      fnQs <- each(exs)((_, sl, pr) => query(pr, fnPrefix(sl.table)).map(z))
      _ =
        fnProbe = Softmax(fns :+ "-", d)
        fnProbe.train(fnQs.zip(exs).map((q, e) => (q, Readers.part(e._2.shape, 1))), steps = 300, lr = 0.05f)
      opQs <- each(withWhere)((_, sl, pr, c, _, _) => query(pr, opPrefix(sl.table, c)).map(z))
      _ =
        op = Softmax(ops, d)
        op.train(opQs.zip(withWhere).map((q, e) => (q, e._5)), steps = 300, lr = 0.05f)
      valueEx <- each(withWhere) { (_, sl, pr, c, o, v) =>
        valueStart(pr, v).fold(pure[Option[(Array[Float], Vector[Array[Float]], Int)], R](None)) { start =>
          for
            q <- query(pr, valuePrefix(sl.table, c, o))
            qs <- questionStates(pr)
          yield Some((z(q), qs.map(z), start))
        }
      }
    yield
      val value = Pointer(d)
      value.train(valueEx.flatten, steps = 150, lr = 0.05f)
      pointers("value") = value
      println(s"  readers: ${exs.size} pairs, ${withWhere.size} with a condition, ${valueEx.flatten.size} with the value in the question")

  private val numberWords = Map("one" -> 1, "two" -> 2, "three" -> 3, "four" -> 4, "five" -> 5, "ten" -> 10,
    "один" -> 1, "одна" -> 1, "два" -> 2, "две" -> 2, "три" -> 3, "четыре" -> 4, "пять" -> 5, "десять" -> 10)

  /** the question answered by the readers: the program's text, or why not */
  def program[R[_]: Has[Read]](tables: Map[String, Table], question: String): Either[String, String] ! R =
    for
      pr <- prompted(Pairs.Pair(tables, question, ""))
      names = pr.names.map(_._1)
      ks <- keys(pr).map(_.map(z))
      last <- Read.states(pr.ids, Vector(pr.ids.length - 1)).map(st => z(st.head))
      ti = pointers("table").predict(last, ks)
      // EXACT FIRST, for the table too: a table named in the question is the table (the pointer's choice among several
      // named); else the one table a named column belongs to (real data: "GICS Sector" is a column of one table only);
      // else the pointer's
      chosen =
        val lowerQ = question.toLowerCase
        def mentioned(n: String) = n.length >= 3 && lowerQ.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(n.toLowerCase)}\\b.*")
        val byName = tables.keys.toVector.sorted.filter(mentioned)
        val byColumn = tables.values.toVector.sortBy(_.name).filter(t => t.columns.exists(mentioned)).map(_.name)
        val pointed = Option.when(ti < names.size)(names(ti)).filter(tables.contains)
        pointed.filter(byName.contains).orElse(byName.headOption)
          .orElse(if byColumn.size == 1 then byColumn.headOption else pointed.filter(byColumn.contains).orElse(pointed).orElse(byColumn.headOption))
      result <- chosen.fold(pure[Either[String, String], R](Left("no table read")))(t => assemble(tables, question, pr, ks, t))
    yield result

  /** the program for the table read: the shape as decisions, the columns, the condition's op and value */
  private def assemble[R[_]: Has[Read]](tables: Map[String, Table], question: String, pr: Prompted,
      ks: Vector[Array[Float]], table: String): Either[String, String] ! R =
    val t = tables(table)
    val lower = question.toLowerCase
    def q(s: String) = "\"" + s + "\""
    // EXACT FACTS FROM THE DATA constrain the reads: a value of a column written in the question (a word of 3+ letters,
    // a date) means a condition on that column — and a join, when the column is in another table reachable by one
    // a join to another table: the table's column whose every value names a row of the other (its first column) —
    // found once per table, by a set (on 3376 × 503 rows the row-by-row search took 90 s a question)
    val joinOf: Map[String, Option[String]] = tables.values.map { o =>
      val keys = o.rows.map(_(0).text).toSet
      o.name -> (if o.name == table || t.rows.isEmpty then None
        else t.columns.zipWithIndex.collectFirst { case (c, i) if t.rows.forall(r => keys(r(i).text)) => c })
    }.toMap
    def joinTo(o: Table): Option[String] = joinOf(o.name)
    // the question's words and their runs of up to four (a value of several words): a value is named when it is one
    // of them — but never a word of the question itself ("how MANY" is not the city Many, "IN" not the state), and a
    // short code (AK, IN, UA) only as written, in capitals
    val qWords = "(?U)[\\p{L}\\p{N}][\\p{L}\\p{N}.'-]*".r.findAllIn(question).map(_.replaceAll("[.'-]+$", "")).toVector
    val grams: Set[String] = (1 to 4).flatMap(k => if qWords.size >= k then qWords.sliding(k).map(_.mkString(" ")) else Nil).toSet
    val gramsLower = grams.map(_.toLowerCase)
    def inQuestion(v: String): Boolean =
      !Readers.stop(v.toLowerCase) && (if v.length <= 3 && v.forall(_.isUpper) then grams(v) else gramsLower(v.toLowerCase))
    val found: Vector[(Table, String, String)] = tables.values.toVector.flatMap { o =>
      o.columns.zipWithIndex.flatMap { (c, i) =>
        o.rows.map(_(i).text).distinct.filter(v => (v.length >= 3 && !v.forall(_.isDigit) || v.length >= 2 && v.forall(_.isUpper) ||
          v.matches("\\d{4}-\\d\\d-\\d\\d")) && inQuestion(v)).map(v => (o, c, v))
      }
    }.filter((o, _, _) => o.name == table || joinTo(o).isDefined).sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length))
    // every fact a condition; the first the "fact" (the join and the where column)
    val facts = found.distinctBy(_._2)
    val fact = found.headOption
    // a join only when the data needs one: a fact in another table, or a column of another table named in the question
    val join = facts.find(_._1.name != table).flatMap((o, _, _) => joinTo(o).map(c => (o, c))).orElse(
      tables.values.filter(_.name != table).flatMap(o => joinTo(o).filter(_ => o.columns.exists(c =>
        c.length >= 3 && lower.matches(s"(?U).*\\b${c.toLowerCase}\\b.*"))).map(c => (o, c))).headOption)
    // a word (or a stem ending in *) at a word boundary: "count" must not match "country"
    def word(ws: String*): Boolean = ws.exists(w => lower.matches(s"(?U).*\\b${w.stripSuffix("*")}${if w.endsWith("*") then "\\w*" else ""}\\b.*"))
    // the joinable tables' columns are candidates too (a translated column may be theirs: "по странам" → country): a
    // choice among them brings the join
    val joinable = tables.values.toVector.filter(_.name != table).flatMap(o => joinTo(o).map(c => (o, c)))
    var joinNow: Option[(Table, String)] = join
    val cols = t.columns.map(c => (table, c)) ++ joinable.flatMap((o, _) => o.columns.filterNot(c => t.columns.contains(c) || c == "name").map(c => (o.name, c)))
    // EXACT FIRST: a column named in the question (its name, a word of 3+ letters) is the column — the readers decide
    // only among what the question does not name (a translation: "по городам" for city)
    val named = cols.filter((_, c) => c.length >= 3 && lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(c.toLowerCase)}\\b.*"))
    val used = scala.collection.mutable.Set.empty[String]
    def column(slot: String): Option[String] ! R =
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
      val chosenP: String ! R = exact.headOption.fold {
        val cands = if pool.nonEmpty then pool else cols
        for
          qv <- query(pr, prefix).map(z)
          scores <- bothScores(pr, prefix, pointers(slot), qv, ks, cands)
        yield
          val own = cands.zipWithIndex.filter((tc, _) => tc._1 == table); val other = cands.zipWithIndex.filter((tc, _) => tc._1 != table)
          val ownBest = own.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1); val otherBest = other.map((tc, i) => (scores(i), tc._2)).maxByOption(_._1)
          (ownBest, otherBest) match
            case (Some((so, co)), Some((st, ct))) => if st > so + 3f then ct else co
            case (Some((_, co)), None) => co
            case (None, Some((_, ct))) => ct
            case _ => cands.head._2
      }(c => pure(c))
      chosenP.map { chosen =>
        used += chosen
        if !t.columns.contains(chosen) then joinNow = joinNow.orElse(joinable.find(_._1.columns.contains(chosen)))
        Some(chosen)
      }
    /** the value: from the read start among the question's tokens, the longest value of the column (or a number or
     * a date) that begins there; else by exact match anywhere in the question */
    def constant(col: String, o: String): Option[String] ! R =
      val owner = if t.columns.contains(col) then t else joinNow.map(_._1).orElse(tables.values.find(_.columns.contains(col))).getOrElse(t)
      val vs = owner.rows.map(_(owner.columns.indexOf(col)).text).distinct.filter(_.nonEmpty).sortBy(-_.length)
      for
        qs <- questionStates(pr).map(_.map(z))
        vq <- query(pr, valuePrefix(table, col, o)).map(z)
        start = pointers("value").predict(vq, qs)
        from = if start < pr.qChars.size then pr.qChars(start) else 0
        rest = question.drop(from)
        copied = vs.find(v => rest.toLowerCase.startsWith(v.toLowerCase))
          .orElse("^\\s*(\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?)".r.findFirstMatchIn(rest).map(_.group(1)))
          .orElse(vs.find(inQuestion))
          .orElse("\\d{4}-\\d\\d-\\d\\d|\\d+(?:\\.\\d+)?".r.findAllIn(question).toVector.lastOption)
        // nothing copied (a translated value, "не оплачено"): the model asked over the column's values
        v <- copied.fold(
          if vs.nonEmpty && vs.size <= 30 then ask(pr, valuePrefix(table, col, o) + "\"", vs).map(a => Some(vs(a.zipWithIndex.maxBy(_._1)._2)))
          else pure[Option[String], R](None))(c => pure(Some(c)))
      yield v
    // A TIME WRITTEN IN THE QUESTION ("in 2020", "за 2023"): a number that is a value of the table's time column is a
    // condition on it — and not the limit's number
    val timeColumn = t.columns.find(c => c.toLowerCase.matches("(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*"))
    val timeValues: Vector[String] = timeColumn.toVector.flatMap { c =>
      val vs = t.rows.map(_(t.columns.indexOf(c)).text).toSet
      "\\d{4}(?:-\\d\\d(?:-\\d\\d)?)?".r.findAllIn(question).toVector.distinct.filter(vs)
    }
    def number: Option[Int] = "\\d+".r.findAllIn(question).toVector.filterNot(timeValues.contains).headOption.map(_.toInt)
      .orElse(numberWords.collectFirst { case (w, n) if lower.matches(s"(?U).*\\b$w\\b.*") => n })
    for
      // the shape by two readers: the probe, and the model asked which section comes first at `{"from": T, "`
      shapeQ <- query(pr, prefixes.head._2(table)).map(z)
      probeScores = logSoftmax(shape.scores(shapeQ))
      fnAsk <- ask(pr, fnPrefix(table), fns)
      fnQ <- query(pr, fnPrefix(table)).map(z)
      decided =
        // THE SHAPE AS SEPARATE DECISIONS, each exact where the question says it, read where it does not: the probe's
        // marginals over its compound classes are the fallback
        val probs = probeScores.map(v => math.exp(v.toDouble))
        def marginal(p: String => Boolean): Double = shape.classes.zipWithIndex.collect { case (c, i) if p(c) => probs(i) }.sum
        val closure = marginal(_.startsWith("closure")) > 0.5 || word("reach*", "depend*", "lead*", "достиж*", "завис*", "вед*", "доход*")
        val closureTo = (word("reach", "reaches", "lead*", "ведут", "ведёт", "доход*") && !word("reachable", "достижим*"))
        val fnProbeScores = logSoftmax(fnProbe.scores(fnQ))
        val fnByReaders = (fns :+ "-")(fnProbeScores.zipWithIndex.map((s, i) => (if i < fns.size then s + fnAsk(i) else s, i)).maxBy(_._1)._2)
        val topN = word("top", "топ", "three", "five", "ten", "три", "пять", "десять")
        val perEach = word("per", "each", "по кажд*", "кажд*", "в разрезе", "по стат*", "по город*", "по клиент*", "по стран*")
        // GROWTH: a change in time within an entity — needs a time column (year, date, day, month) in the table
        val timeCol = t.columns.find(c => c.toLowerCase.matches("(?U).*\\b(year|date|day|month|quarter|week|time|год|дата|день|месяц)\\b.*"))
        val growth = timeCol.isDefined && word("grow*", "growth", "increase*", "rose", "rise", "fastest", "decline*", "fell", "рост*", "раст*", "растёт", "растут", "вырос*", "быстрее", "упал*", "снижа*")
        val fn = if growth then "growth"
          else if word("how many", "number of", "count", "сколько", "число", "посчитай") then "count"
          else if word("average", "mean", "средн*") then "avg"
          else if topN && !perEach then "-"
          else if word("total", "sum of", "сумм*", "итого") then "sum"
          else if word("largest", "highest", "maximum", "самый больш*", "наибольш*", "максим*") && !perEach then "-"
          else fnByReaders
        val hasGroup = fn == "growth" || perEach || word("by") && fn != "-" || (fn != "-" && fn != "count" && marginal(c => Readers.part(c, 3) == "group") > 0.5)
        val hasOrder = fn == "growth" || word("largest", "smallest", "highest", "lowest", "descending", "first", "top", "самы*", "наибольш*", "по убыван*", "по возраст*", "топ") || marginal(c => Readers.part(c, 4) == "order") > 0.6
        val hasLimit = fn == "growth" || topN || (word("largest", "smallest", "highest", "самый", "наибольш*") && fn == "-" && !hasGroup) || marginal(c => Readers.part(c, 5) == "limit") > 0.6
        val hasSelect = marginal(c => Readers.part(c, 7) == "select") > 0.5
        // a number with a comparison word is a condition on a numeric column
        val numberCond = "\\d+(?:\\.\\d+)?".r.findFirstIn(question).filter(_ => word("over", "above", "more than", "greater", "больше", "выше", "свыше", "below", "under", "less than", "меньше", "ниже", "до"))
        val hasWhere = fact.isDefined || numberCond.isDefined || marginal(c => Readers.part(c, 2) != "-") > 0.3
        val sh = if closure then Vector(if closureTo then "closure-to" else "closure-from")
          else Vector("rel", fn, if hasWhere then "=" else "-", if hasGroup then "group" else "-", if hasOrder then "order" else "-",
            if hasLimit then "limit" else "-", "-", if hasSelect then "select" else "-")
        (sh, fn, hasGroup, hasOrder, numberCond, timeCol)
      (sh, fn, hasGroup, hasOrder, numberCond, timeCol) = decided
      result <-
        if sh.head == "closure-from" || sh.head == "closure-to" then
          val e = tables.values.find(_.columns.size == 2).getOrElse(t)
          val nodes = e.rows.flatten.map(_.text).distinct.sortBy(-_.length)
          pure[Either[String, String], R](nodes.find(n => lower.contains(n.toLowerCase)).toRight(s"no node named in the question (table $table, shape ${sh.mkString(" ")})").map { c =>
            val goal = if sh.head == "closure-from" then s"reach(${Logic.constant(c)}, X)" else s"reach(X, ${Logic.constant(c)})"
            s"""{"datalog": "reach(X, Y) :- ${e.name}(X, Y). reach(X, Z) :- ${e.name}(X, Y), reach(Y, Z).", "goal": ${q(goal.replace("\"", "\\\""))}}"""
          })
        else
          val Vector(_, _, whereOp0, group, order, limit, _, select) = sh: @unchecked
          // the fact forces the condition the shape may have missed
          val whereOp = if whereOp0 == "-" && fact.isDefined then "=" else whereOp0
          // the columns in the order that disambiguates them: the aggregate's (numeric) and the order's first, then the group's
          // (text), then the condition's (the fact's column, or the numeric one of a number condition)
          for
            aggCol <-
              if fn == "growth" then
                // the measure: a numeric column named in the question, else the first that is not the time
                val nums = t.columns.filter(c => !timeCol.contains(c) && t.column(c).exists(i => t.rows.exists(_(i).num.isDefined) && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                pure[Option[String], R](nums.find(c => named.exists(_._2 == c)).orElse(nums.find(_.toLowerCase != "id")))
              else if fn != "-" && fn != "count" then column("agg") else pure[Option[String], R](None)
            orderCol <- if hasOrder && !(fn != "-" && fn != "count" && hasGroup) then column("order") else pure[Option[String], R](None)
            groupCol <-
              if fn == "growth" then
                // the entity: a text column named in the question, else the first that is not a code or a key
                val texts = t.columns.filter(c => !timeCol.contains(c) && !t.column(c).exists(i => t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                pure[Option[String], R](texts.find(c => named.exists(_._2 == c)).orElse(texts.find(c => !c.toLowerCase.matches(".*(code|id|key)$"))).orElse(texts.headOption))
              else if hasGroup then column("group") else pure[Option[String], R](None)
            whereCol <- if whereOp == "-" then pure[Option[String], R](None) else
              fact.map(_._2).map { c => used += c; c }
                // a number condition: the numeric column whose values' range holds the number (not the key "id" by accident)
                .orElse(numberCond.flatMap { n => val x = n.toDouble
                  val numericCols = t.columns.filterNot(used).filter(c => t.column(c).exists(i => t.rows.exists(_(i).num.isDefined) && t.rows.forall(r => r(i) == Value.Empty || r(i).num.isDefined)))
                  numericCols.find(c => named.exists(_._2 == c)).orElse(numericCols.find { c => val vs = t.rows.flatMap(_(t.column(c).get).num); vs.nonEmpty && vs.min <= x && x <= vs.max })
                    .orElse(numericCols.filterNot(_ == "id").headOption).orElse(numericCols.headOption) })
                .fold(column("where"))(c => pure(Some(c)))
            // the first condition's op and value
            first <- whereCol.fold(pure[Option[String], R](None)) { c =>
              val opP: String ! R =
                if whereOp == "empty" then pure("empty")
                else if fact.exists(_._2 == c) && !lower.matches("(?U).*\\b(not|не|кроме|except)\\b.*") then pure("=")
                else if numberCond.isDefined then pure(if word("over", "above", "more than", "greater", "больше", "выше", "свыше") then ">" else "<")
                else
                  for
                    oq <- query(pr, opPrefix(table, c)).map(z)
                    as <- ask(pr, opPrefix(table, c), ops)
                  yield
                    val ps = logSoftmax(op.scores(oq))
                    ops(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)
              for
                o <- opP
                v <- fact.filter(_._2 == c).map(_._3).orElse(numberCond).fold(constant(c, o))(v => pure(Some(v)))
              yield v.filter(_.nonEmpty).map(value =>
                // (a condition with no value is no condition: an empty one answered nothing and said nothing)
                s"""{"column": ${q(c)}, "op": ${q(o)}, "value": ${if value.matches("-?\\d+(\\.\\d+)?") && o != "=" then value else q(value)}}""")
            }
          yield
            val parts = Vector.newBuilder[String]
            parts += s""""from": ${q(table)}"""
            // (the join after the columns are chosen: a column of a joinable table brings it)
            val joinSlot = parts.result().size
            val conds = Vector.newBuilder[String]
            first.foreach(conds += _)
            for (_, c, v) <- facts.drop(1) if !whereCol.contains(c) do conds += s"""{"column": ${q(c)}, "op": "=", "value": ${q(v)}}"""
            for c <- timeColumn if !whereCol.contains(c); v <- timeValues do
              conds += s"""{"column": ${q(c)}, "op": "=", "value": ${if v.forall(_.isDigit) then v else q(v)}}"""
            val cs = conds.result()
            if cs.nonEmpty then parts += s""""where": [${cs.mkString(", ")}]"""
            if group == "group" then groupCol.foreach(c => parts += s""""group": [${q(c)}]""")
            if fn != "-" then
              val c = if fn == "count" then Some("*") else aggCol
              val by = if fn == "growth" then timeCol.map(b => s""", "by": ${q(b)}""").getOrElse("") else ""
              c.foreach(col => parts += s""""aggregate": [{"fn": ${q(fn)}, "column": ${q(col)}, "as": ${q(if col == "*" then "n" else fn)}$by}]""")
            if order == "order" then
              val c = if fn != "-" && fn != "count" && group == "group" then Some(fn) else orderCol
              c.foreach(col => parts += s""""order": [${q("-" + col)}]""")
            if limit == "limit" then parts += s""""limit": ${number.getOrElse(if fn == "growth" then 10 else 1)}"""
            if select == "select" then orderCol.foreach(c => parts += s""""select": ["id", ${q(c)}]""")
            val built = parts.result()
            val withJoin = joinNow.fold(built)((o, c) => built.take(joinSlot) ++ Vector(s""""join": {"table": ${q(o.name)}, "on": [[${q(c)}, ${q(o.columns.head)}]]}""") ++ built.drop(joinSlot))
            Right(withJoin.mkString("{", ", ", "}"))
    yield result

  /** the readers measured on held-out pairs, slot by slot: the pointer alone against the pointer with ask — so a
   * change is judged on 200 pairs, not on the bench's 12 questions */
  def measure[R[_]: Has[Read]](pairs: Vector[Pairs.Pair]): Unit ! R =
    var shapeRight = 0
    val right = scala.collection.mutable.Map.empty[String, (Int, Int, Int, Int)].withDefaultValue((0, 0, 0, 0))
    for
      exs <- each(pairs.flatMap(p => slots(p.program).map(sl => (p, sl))))((p, sl) => prompted(p).map(pr => (p, sl, pr)))
      _ <- each(exs) { (p, sl, pr) =>
        val t = p.tables(sl.table)
        for
          ks <- keys(pr).map(_.map(z))
          sq <- query(pr, prefixes.head._2(sl.table)).map(z)
          _ = if shape.predict(sq) == sl.shape then shapeRight += 1
          _ <- each(prefixes.tail) { (name, prefix) =>
            val gold = (name match { case "where" => sl.where; case "group" => sl.group; case "agg" => sl.agg; case _ => sl.order })
            gold.fold(unit[R]) { g =>
              val cands = t.columns.map(c => (sl.table, c))
              val cks = cands.map((tb, c) => ks(pr.indexOf(tb, c)))
              for
                qv <- query(pr, prefix(sl.table)).map(z)
                sc = pointers(name).scores(qv, cks).take(cks.size)
                alone = t.columns(sc.zipWithIndex.maxBy(_._1)._2)
                asked <- ask(pr, prefix(sl.table), t.columns)
                byAsk = t.columns(asked.zipWithIndex.maxBy(_._1)._2)
                withAsk <- both(pr, prefix(sl.table), pointers(name), qv, ks, cands)
              yield
                val (n, a, k, b) = right(name)
                right(name) = (n + 1, a + (if alone == g then 1 else 0), k + (if byAsk == g then 1 else 0), b + (if withAsk == g then 1 else 0))
            }
          }
        yield ()
      }
    yield println(f"  measured on ${exs.size} held-out pairs: shape ${shapeRight * 100.0 / exs.size}%.1f%%; " +
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
  /** the words of a question that are never a value of the data */
  val stop: Set[String] = Set("how", "many", "much", "which", "what", "where", "who", "when", "in", "of", "per", "by", "the",
    "a", "an", "is", "are", "was", "were", "with", "for", "from", "to", "on", "at", "and", "or", "not", "all", "each", "every",
    "there", "that", "this", "than", "сколько", "какие", "какой", "какая", "какое", "что", "кто", "где", "когда", "в", "по",
    "из", "на", "и", "или", "не", "все", "всех", "каждый", "каждом", "каждого", "за", "с", "у", "от", "до", "для", "как", "это",
    "есть", "чем")
  /** a part of a compound shape class; "-" where the class has none (a closure) */
  def part(shape: String, i: Int): String = shape.split(" ").lift(i).getOrElse("-")

  /** the readers run in place on a `Reading` — the JVM's face, the bench's */
  final class On(val readers: Readers, reading: Reading):
    def train(pairs: Vector[Pairs.Pair]): Unit = Read.run(readers.train[Read](pairs), reading)
    def program(tables: Map[String, Table], question: String): Either[String, String] =
      Read.run(readers.program[Read](tables, question), reading)
    def programWith(tables: Map[String, Table], question: String): Option[(String, Boolean)] =
      Read.run(readers.programWith[Read](tables, question), reading)
    def measure(pairs: Vector[Pairs.Pair]): Unit = Read.run(readers.measure[Read](pairs), reading)
    def bytes: Array[Byte] = readers.bytes
    def load(bytes: Array[Byte]): Unit = readers.load(bytes)
