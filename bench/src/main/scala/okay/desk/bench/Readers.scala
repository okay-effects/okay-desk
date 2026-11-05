package okay.desk.bench

import mlx.llm.{Chat, Qwen3}
import okay.desk.*

/**
 * THE SKILL AS READERS AND AN ASSEMBLER (specs/data.md §7, §9): the model untouched; probes trained on the pairs
 * read the program's slots from its states where it decides them; the constant of a condition is copied from the
 * question by exact match with the column's values (or a number in the question); the program is assembled — exact,
 * well-formed by construction. `arm` answers a question the way the page's loop would, with no generation at all.
 */
final class Readers(chat: Chat, model: Qwen3, layer: Int):
  import Probes.*
  private val tok = chat.tokenizer
  private var mean: Array[Float] = Array.empty; private var std: Array[Float] = Array.empty; private var scale = 1f
  private def z(x: Array[Float]): Array[Float] =
    Array.tabulate(x.length)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))) * scale)
  private var shape: Softmax = null
  private val pointers = scala.collection.mutable.Map.empty[String, Pointer]

  private def query(pr: Prompted, prefix: String): Array[Float] =
    val ids = pr.ids ++ tok.encode(prefix)
    states(model, ids, Seq(layer), Vector(ids.length - 1))(layer).head
  private def keys(pr: Prompted): Vector[Array[Float]] =
    states(model, pr.ids, Seq(layer), pr.names.map(_._2))(layer)

  def train(pairs: Vector[Pairs.Pair]): Unit =
    val exs = pairs.flatMap(p => slots(p.program).map(sl => (p, sl, prompted(chat, p))))
    val base = exs.map((_, sl, pr) => (sl, pr.names.map(_._1), states(model, pr.ids, Seq(layer), (pr.ids.length - 1) +: pr.names.map(_._2))(layer)))
    val all = base.flatMap(_._3)
    val d = all.head.length
    mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
    std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
    scale = 8f / math.sqrt(d).toFloat
    val keyStates = base.map(_._3.tail.map(z))
    // the table at the last token
    val table = Pointer(d)
    table.train(base.zip(keyStates).map { case ((sl, names, st), ks) => (z(st.head), ks, names.indexOf(sl.table)) })
    pointers("table") = table
    // the shape and the columns after their prefixes
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
      def column(slot: String): Option[String] =
        val i = pointers(slot).predict(z(query(pr, prefixes.find(_._1 == slot).get._2(table))), ks)
        Option.when(i < names.size && t.columns.contains(names(i)))(names(i))
      val sh = shape.predict(z(query(pr, prefixes.head._2(table)))).split(" ")
      def q(s: String) = "\"" + s + "\""
      // a constant from the question: a value of the column found in it (ignoring case), or a number
      def constant(col: String): Option[String] =
        val lower = question.toLowerCase
        val vs = t.rows.map(_(t.columns.indexOf(col)).text).distinct.filter(_.nonEmpty).sortBy(-_.length)
        vs.find(v => lower.contains(v.toLowerCase)).orElse("\\d+(?:\\.\\d+)?".r.findAllIn(question).toVector.lastOption)
      def number: Option[Int] = "\\d+".r.findAllIn(question).toVector.headOption.map(_.toInt)
      if sh.head == "closure-from" || sh.head == "closure-to" then
        val e = tables.values.find(_.columns.size == 2).getOrElse(t)
        val nodes = e.rows.flatten.map(_.text).distinct.sortBy(-_.length)
        nodes.find(n => question.toLowerCase.contains(n.toLowerCase)).toRight("no node named in the question").map { c =>
          val goal = if sh.head == "closure-from" then s"reach(${Logic.constant(c)}, X)" else s"reach(X, ${Logic.constant(c)})"
          s"""{"datalog": "reach(X, Y) :- ${e.name}(X, Y). reach(X, Z) :- ${e.name}(X, Y), reach(Y, Z).", "goal": ${q(goal.replace("\"", "\\\""))}}"""
        }
      else
        val Array(_, fn, op, group, order, limit, _, select) = sh
        val parts = Vector.newBuilder[String]
        parts += s""""from": ${q(table)}"""
        if op != "-" then column("where").foreach { c =>
          val v = constant(c).getOrElse("")
          parts += s""""where": [{"column": ${q(c)}, "op": ${q(op)}, "value": ${if v.matches("-?\\d+(\\.\\d+)?") && op != "=" then v else q(v)}}]""" }
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
