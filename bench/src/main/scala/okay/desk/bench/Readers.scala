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
  private var fnProbe: Softmax = null
  val fns = Vector("count", "sum", "avg", "min", "max")
  private def fnPrefix(t: String) = "```json\n{\"from\": \"" + t + "\", \"aggregate\": [{\"fn\": \""
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
  // `candidates`: (table, column) pairs — the index found within the table
  private def both(pr: Prompted, prefix: String, pointer: Pointer, q: Array[Float], ks: Vector[Array[Float]],
      candidates: Vector[(String, String)]): String =
    val names = candidates.map(_._2)
    val cks = candidates.map((t, c) => ks(pr.indexOf(t, c)))
    val ps = logSoftmax(pointer.scores(q, cks).take(cks.size))
    val as = ask(pr, prefix, names)
    // the two readers disagreeing is remembered: the self-play judge takes only answers they agreed on
    if ps.zipWithIndex.maxBy(_._1)._2 != as.zipWithIndex.maxBy(_._1)._2 then agreedAll = false
    names(ps.zip(as).map((a, b) => a + b).zipWithIndex.maxBy(_._1)._2)
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
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(chat, p))))
    val base = exs.map((_, sl, pr) => (sl, pr, states(model, pr.ids, Seq(layer), (pr.ids.length - 1) +: pr.names.map(_._2))(layer)))
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
    fnProbe.train(exs.map((_, sl, pr) => (z(query(pr, fnPrefix(sl.table))), sl.shape.split(" ")(1))), steps = 300, lr = 0.05f)
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
          vs.filter(v => (v.length >= 3 && !v.forall(_.isDigit) || v.matches("\\d{4}-\\d\\d-\\d\\d")) &&
            lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(v.toLowerCase)}\\b.*")).map(v => (o, c, v))
        }
      }.filter((o, _, _) => o.name == table || joinTo(o).isDefined).sortBy((o, _, v) => (if o.name == table then 0 else 1, -v.length))
      val fact = found.headOption
      // a join only when the data needs one: a fact in another table, or a column of another table named in the question
      val join = fact.filter(_._1.name != table).flatMap((o, _, _) => joinTo(o).map(c => (o, c))).orElse(
        tables.values.filter(_.name != table).flatMap(o => joinTo(o).filter(_ => o.columns.exists(c =>
          c.length >= 3 && lower.matches(s"(?U).*\\b${c.toLowerCase}\\b.*"))).map(c => (o, c))).headOption)
      // THE SHAPE AS SEPARATE DECISIONS, each exact where the question says it, read where it does not: the probe's
      // marginals over its compound classes are the fallback
      val probs = probeScores.map(v => math.exp(v.toDouble))
      def marginal(p: String => Boolean): Double = shape.classes.zipWithIndex.collect { case (c, i) if p(c) => probs(i) }.sum
      def word(ws: String*): Boolean = ws.exists(w => lower.matches(s"(?U).*\\b$w.*"))
      val closure = marginal(_.startsWith("closure")) > 0.5 || word("reach", "depend", "lead", "достиж", "завис", "вед", "доход")
      val closureTo = word("reach ", "lead", "ведут", "ведёт", "доход") && !word("reachable from", "достижимо из")
      val fnAsk = ask(pr, fnPrefix(table), fns)
      val fnProbeScores = logSoftmax(fnProbe.scores(z(query(pr, fnPrefix(table)))))
      val fnByReaders = (fns :+ "-")(fnProbeScores.zipWithIndex.map((s, i) => (if i < fns.size then s + fnAsk(i) else s, i)).maxBy(_._1)._2)
      val fn = if word("how many", "number of", "count", "сколько", "число", "посчитай") then "count"
        else if word("average", "mean", "средн") then "avg" else if word("total", "sum of", "сумм", "итого") then "sum"
        else if word("largest", "highest", "maximum", "самый больш", "наибольш", "максим") && !word("top", "топ", "три", "three", "five", "пять") && !word("per", "by", "по кажд", "в разрезе") then "-"
        else fnByReaders
      val hasGroup = word("per ", "by ", "each", "по кажд", "в разрезе", "по стат", "по город", "по клиент", "по стран") || (fn != "-" && fn != "count" && marginal(_.split(" ")(3) == "group") > 0.5)
      val hasOrder = word("largest", "smallest", "highest", "lowest", "descending", "first", "top", "самы", "наибольш", "по убыван", "по возраст", "топ") || marginal(_.split(" ")(4) == "order") > 0.6
      val hasLimit = word("top", "топ", "three", "five", "ten", "три", "пять", "десять") || (word("largest", "smallest", "highest", "самый", "наибольш") && fn == "-" && !hasGroup) || marginal(_.split(" ")(5) == "limit") > 0.6
      val hasSelect = marginal(_.split(" ")(7) == "select") > 0.5
      val hasWhere = fact.isDefined || marginal(_.split(" ")(2) != "-") > 0.5
      val sh = if closure then Array(if closureTo then "closure-to" else "closure-from")
        else Array("rel", fn, if hasWhere then "=" else "-", if hasGroup then "group" else "-", if hasOrder then "order" else "-",
          if hasLimit then "limit" else "-", "-", if hasSelect then "select" else "-")
      val cols = t.columns.map(c => (table, c)) ++ join.map(j => j._1.columns.map(c => (j._1.name, c))).getOrElse(Vector.empty)
      // EXACT FIRST: a column named in the question (its name, a word of 3+ letters) is the column — the readers decide
      // only among what the question does not name (a translation: "по городам" for city)
      val named = cols.filter((_, c) => c.length >= 3 && lower.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(c.toLowerCase)}\\b.*"))
      val used = scala.collection.mutable.Set.empty[String]
      def column(slot: String): Option[String] =
        val prefix = prefixes.find(_._1 == slot).get._2(table)
        val exact = named.map(_._2).filterNot(used).filter(c => slot != "agg" && slot != "order" || t.column(c).exists(i => t.rows.exists(_(i).num.isDefined)))
        val chosen = exact.headOption.getOrElse(both(pr, prefix, pointers(slot), z(query(pr, prefix)), ks, cols))
        used += chosen
        Some(chosen)
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
        if whereOp != "-" then fact.map(_._2).map { c => used += c; c }.orElse(column("where")).foreach { c =>
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

  /** the readers measured on held-out pairs, slot by slot: the pointer alone against the pointer with ask — so a
   * change is judged on 200 pairs, not on the bench's 12 questions */
  def measure(pairs: Vector[Pairs.Pair]): Unit =
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(chat, p))))
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

  // ---- the readers kept: trained once, loaded in seconds (Java serialization of the arrays: a skill is a file)
  def save(path: java.nio.file.Path): Unit =
    java.nio.file.Files.createDirectories(path.getParent)
    val out = new java.io.ObjectOutputStream(java.nio.file.Files.newOutputStream(path))
    try
      out.writeObject((mean, std, scale))
      out.writeObject((shape.classes, shape.w, shape.b))
      out.writeObject((op.classes, op.w, op.b))
      out.writeObject((fnProbe.classes, fnProbe.w, fnProbe.b))
      out.writeObject(pointers.toMap.map((k, p) => k -> (p.wq, p.wk, p.none)))
    finally out.close()
    println(s"  readers saved: $path (${java.nio.file.Files.size(path) / 1024} KB)")

  def load(path: java.nio.file.Path): Unit =
    val in = new java.io.ObjectInputStream(java.nio.file.Files.newInputStream(path))
    try
      val (m, s, sc) = in.readObject().asInstanceOf[(Array[Float], Array[Float], Float)]
      mean = m; std = s; scale = sc
      val (sc1, sw, sb) = in.readObject().asInstanceOf[(Vector[String], Array[Array[Float]], Array[Float])]
      shape = Softmax(sc1, m.length); sw.copyToArray(shape.w); sb.copyToArray(shape.b)
      val (oc, ow, ob) = in.readObject().asInstanceOf[(Vector[String], Array[Array[Float]], Array[Float])]
      op = Softmax(oc, m.length); ow.copyToArray(op.w); ob.copyToArray(op.b)
      val (fc, fw, fb) = in.readObject().asInstanceOf[(Vector[String], Array[Array[Float]], Array[Float])]
      fnProbe = Softmax(fc, m.length); fw.copyToArray(fnProbe.w); fb.copyToArray(fnProbe.b)
      val ps = in.readObject().asInstanceOf[Map[String, (Array[Array[Float]], Array[Array[Float]], Array[Float])]]
      for (k, (wq, wk, none)) <- ps do
        val p = Pointer(m.length); wq.copyToArray(p.wq); wk.copyToArray(p.wk); none.copyToArray(p.none); pointers(k) = p
    finally in.close()
    println(s"  readers loaded: $path")

object Readers:
  /** the readers for a model and a layer: loaded from the cache when there (DESK_RETRAIN=1 trains again) */
  def ready(chat: Chat, model: Qwen3, layer: Int, repo: String, pairs: => Vector[Pairs.Pair]): Readers =
    val r = Readers(chat, model, layer)
    val path = java.nio.file.Path.of(sys.props("user.home"), ".cache", "okay-desk", s"readers-${repo.split('/').last}-$layer.bin")
    if java.nio.file.Files.exists(path) && !scala.sys.env.contains("DESK_RETRAIN") then r.load(path)
    else { r.train(pairs); r.save(path) }
    r
