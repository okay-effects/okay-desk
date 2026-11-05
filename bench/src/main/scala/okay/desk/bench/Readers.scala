package okay.desk.bench

import mlx.llm.{Chat, Qwen3}
import okay.desk.*

/**
 * THE SKILL AS READERS AND AN ASSEMBLER (specs/data.md §7, §9, §10): the model untouched; probes trained on the
 * pairs read the program's slots from its states where it decides them — the table, the shape, the columns, the
 * op, and the START of a condition's value among the question's tokens (the quote mechanism: copied from there,
 * not generated); the program is assembled — exact, well-formed by construction. `program` answers a question the
 * way the page's loop would, with no generation at all.
 */
final class Readers(chat: Chat, model: Qwen3, layer: Int):
  import Probes.*
  private val tok = chat.tokenizer
  private var mean: Array[Float] = Array.empty; private var std: Array[Float] = Array.empty; private var scale = 1f
  private def z(x: Array[Float]): Array[Float] =
    Array.tabulate(x.length)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))) * scale)
  private var shape: Softmax = null
  private var op: Softmax = null
  private val pointers = scala.collection.mutable.Map.empty[String, Pointer]
  val ops = Vector("=", "!=", "<", "<=", ">", ">=", "contains")
  private def opPrefix(t: String, c: String) = "```json\n{\"from\": \"" + t + "\", \"where\": [{\"column\": \"" + c + "\", \"op\": \""
  private def valuePrefix(t: String, c: String, o: String) = opPrefix(t, c) + o + "\", \"value\": "

  private def query(pr: Prompted, prefix: String): Array[Float] =
    val ids = pr.ids ++ tok.encode(prefix)
    states(model, ids, Seq(layer), Vector(ids.length - 1))(layer).head
  private def keys(pr: Prompted): Vector[Array[Float]] =
    states(model, pr.ids, Seq(layer), pr.names.map(_._2))(layer)
  private def questionStates(pr: Prompted): Vector[Array[Float]] =
    states(model, pr.ids, Seq(layer), pr.question)(layer)

  /** ASK — the second reader (bridge-4's lesson: two readers fail on different phrasings; their sum read 100%): the
   * model's own next-token log-probabilities at the prefix's end, over the candidates' first tokens */
  private def ask(pr: Prompted, prefix: String, candidates: Vector[String]): Array[Float] =
    val ids = pr.ids ++ tok.encode(prefix)
    val st = model.newState(); val s = new mlx.Scope
    try
      val l = mlx.Mlx.astype(model.logits(mlx.Mlx.ints(ids, 1, ids.length)(using s), st)(using s), mlx.Dtype.Float32)(using s).toFloats
      val m = l.max; val z = math.log(l.map(v => math.exp((v - m).toDouble)).sum)
      candidates.map(c => (l(tok.encode(c).head) - m - z).toFloat).toArray
    finally { s.close(); st.close() }
  private def logSoftmax(xs: Array[Float]): Array[Float] =
    val m = xs.max; val z = math.log(xs.map(v => math.exp((v - m).toDouble)).sum)
    xs.map(v => (v - m - z).toFloat)
  /** both readers summed: the pointer's log-probabilities over the candidates and the model's */
  private def both(pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      names: Vector[String], candidates: Vector[String]): String =
    val idx = candidates.map(names.indexOf)
    val ps = logSoftmax(idx.map(pointer.scores(q, ks)).toArray)
    val as = ask(pr, prefix, candidates)
    candidates(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)

  /** the gold value's first token among the question's, if the value is written verbatim in the question */
  private def valueStart(pr: Prompted, value: String): Option[Int] =
    val at = pr.qText.toLowerCase.indexOf(value.toLowerCase)
    Option.when(at >= 0)(pr.qChars.lastIndexWhere(_ <= at)).filter(_ >= 0)

  def train(pairs: Vector[Pairs.Pair]): Unit =
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(chat, p))))
    val base = exs.map((_, sl, pr) => (sl, pr.names.map(_._1), states(model, pr.ids, Seq(layer), (pr.ids.length - 1) +: pr.names.map(_._2))(layer)))
    val all = base.flatMap(_._3)
    val d = all.head.length
    mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
    std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
    scale = 8f / math.sqrt(d).toFloat
    val keyStates = base.map(_._3.tail.map(z))
    val table = Pointer(d)
    table.train(base.zip(keyStates).map { case ((sl, names, st), ks) => (z(st.head), ks, names.indexOf(sl.table)) })
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
        p.train(qs.zip(exs).zip(keyStates).map { case ((q, (_, sl, pr)), ks) =>
          (q, ks, slot(sl).map(pr.names.map(_._1).indexOf).filter(_ >= 0).getOrElse(ks.size)) })
        pointers(name) = p
    // the op and the value's start, on the pairs that have a condition
    val withWhere = exs.flatMap { (p, sl, pr) =>
      Query.parse(p.program).toOption.collect { case Query.Relational(_, _, Cond.Cmp(c, o, v) +: _, _, _, _, _, _) => (p, sl, pr, c, o, v.text) }
    }
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
    val pr = prompted(chat, Pairs.Pair(tables, question, ""))
    val names = pr.names.map(_._1)
    val ks = keys(pr).map(z)
    val last = z(states(model, pr.ids, Seq(layer), Vector(pr.ids.length - 1))(layer).head)
    val ti = pointers("table").predict(last, ks)
    if ti >= names.size then Left("no table read")
    else
      val table = names(ti)
      val t = tables(table)
      // the shape by two readers: the probe, and the model asked which section comes first at `{"from": T, "`
      val shapeKeys = Vector("where", "group", "aggregate", "order", "limit", "}")
      def firstKey(s: String): String =
        val a = s.split(" ")
        if a.length < 8 then "}" else if a(2) != "-" then "where" else if a(3) == "group" then "group" else if a(1) != "-" then "aggregate"
        else if a(4) == "order" then "order" else if a(5) == "limit" then "limit" else "}"
      val shapeQ = z(query(pr, prefixes.head._2(table)))
      val asked = ask(pr, prefixes.head._2(table), shapeKeys)
      val probeScores = logSoftmax(shape.scores(shapeQ))
      val sh = shape.classes.zipWithIndex.map((c, i) => (c, probeScores(i) + asked(shapeKeys.indexOf(firstKey(c)))))
        .maxBy(_._2)._1.split(" ")
      def q(s: String) = "\"" + s + "\""
      val lower = question.toLowerCase
      // EXACT FACTS FROM THE DATA constrain the reads: a value of a column written in the question (a word of 3+ letters,
      // a date) means a condition on that column — and a join, when the column is in another table reachable by one
      def joinTo(o: Table): Option[String] =
        t.columns.find(c => t.rows.forall(r => o.rows.exists(_(0).text == r(t.columns.indexOf(c)).text)))
      val found: Vector[(Table, String, String)] = tables.values.toVector.flatMap { o =>
        o.columns.flatMap { c =>
          val vs = o.rows.map(_(o.columns.indexOf(c)).text).distinct
          vs.filter(v => (v.length >= 3 && !v.forall(_.isDigit) || v.matches("\\d{4}-\\d\\d-\\d\\d")) &&
            lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(v.toLowerCase)}\\b.*")).map(v => (o, c, v))
        }
      }.filter((o, _, _) => o.name == table || joinTo(o).isDefined).sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length))
      val fact = found.headOption
      // a join only when the data needs one: a fact in another table, or a column of another table named in the question
      val join = fact.filter(_._1.name != table).flatMap((o, _, _) => joinTo(o).map(c => (o, c))).orElse(
        tables.values.filter(_.name != table).flatMap(o => joinTo(o).filter(_ => o.columns.exists(c =>
          c.length >= 3 && lower.matches(s"(?U).*\\b${c.toLowerCase}\\b.*"))).map(c => (o, c))).headOption)
      val cols = t.columns ++ join.map(_._1.columns).getOrElse(Vector.empty)
      // a column slot the shape says is there: the two readers over this table's (and the joined one's) columns
      def column(slot: String): Option[String] =
        val prefix = prefixes.find(_._1 == slot).get._2(table)
        Some(both(pr, prefix, pointers(slot), z(query(pr, prefix)), ks, names, cols.filter(names.contains)))
      /** the value: from the read start among the question's tokens, the longest value of the column (or a number or
       * a date) that begins there; else by exact match anywhere in the question */
      def constant(col: String, o: String): Option[String] =
        val owner = if t.columns.contains(col) then t else join.map(_._1).getOrElse(t)
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
        join.foreach((o, c) => parts += s""""join": {"table": ${q(o.name)}, "on": [[${q(c)}, ${q(o.columns.head)}]]}""")
        if whereOp != "-" then fact.map(_._2).orElse(column("where")).foreach { c =>
          val o = if whereOp == "empty" then "empty" else if fact.exists(_._2 == c) && !lower.matches("(?U).*\\b(not|не|кроме|except)\\b.*") then "="
            else
              val ps = logSoftmax(op.scores(z(query(pr, opPrefix(table, c))))); val as = ask(pr, opPrefix(table, c), ops)
              ops(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)
          val v = fact.filter(_._2 == c).map(_._3).orElse(constant(c, o)).getOrElse("")
          parts += s""""where": [{"column": ${q(c)}, "op": ${q(o)}, "value": ${if v.matches("-?\\d+(\\.\\d+)?") && o != "=" then v else q(v)}}]""" }
        if group == "group" then column("group").foreach(c => parts += s""""group": [${q(c)}]""")
        if fn != "-" then
          val c = if fn == "count" then Some("*") else column("agg")
          c.foreach(col => parts += s""""aggregate": [{"fn": ${q(fn)}, "column": ${q(col)}, "as": ${q(if col == "*" then "n" else fn)}}]""")
        if order == "order" then
          val c = if fn != "-" && fn != "count" && group == "group" then Some(fn) else column("order")
          c.foreach(col => parts += s""""order": [${q("-" + col)}]""")
        if limit == "limit" then parts += s""""limit": ${number.getOrElse(1)}"""
        if select == "select" then column("order").foreach(c => parts += s""""select": ["id", ${q(c)}]""")
        Right(parts.result().mkString("{", ", ", "}"))
