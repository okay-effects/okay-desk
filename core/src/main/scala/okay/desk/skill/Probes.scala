package okay.desk.skill

import okay.desk.*
import okay.scala3.*

/**
 * THE SLOTS READ FROM THE MODEL'S STATE (specs/data.md §7, step 1): over the page's prompt for a pair, the residual
 * after a layer is read at the last token (the question understood) and at the schema's names in the prompt. A
 * logistic probe reads the SHAPE of the program; a bilinear pointer reads the TABLE and each COLUMN slot as the
 * schema position the question points at (score (qW)·(kV), a "none" key when the slot is empty). Trained on the
 * generated pairs (the gold slots known), measured per slot on held-out pairs, per layer — the first question of
 * §7: do the slots read linearly from a 0.6B?
 */
object Probes:
  final case class Slots(shape: String, table: String, where: Option[String], group: Option[String],
    agg: Option[String], order: Option[String])

  /** the gold slots of a program */
  def slots(program: String): Option[Slots] = Query.parse(program).toOption.map {
    case Query.Logical(_, goal) => Slots(if goal.matches("^\\w+\\(\\s*[A-Z].*") then "closure-to" else "closure-from",
      "edges", None, None, None, None)
    case Query.Relational(from, join, where, group, aggs, select, order, limit) =>
      val shape = Vector("rel", aggs.headOption.map(_.fn).getOrElse("-"), where.headOption.map {
        case Cond.Cmp(_, op, _) => op
        case Cond.Has(_, _) => "contains"
        case Cond.Empty(_, _) => "empty" }.getOrElse("-"), if group.nonEmpty then "group" else "-",
        if order.nonEmpty then "order" else "-", if limit.isDefined then "limit" else "-", "-", // the join is not read from the state: the data decides it (a value or a column of another table named)
        if select.nonEmpty then "select" else "-").mkString(" ")
      Slots(shape, from, where.headOption.map {
        case Cond.Cmp(c, _, _) => c
        case Cond.Has(c, _) => c
        case Cond.Empty(c, _) => c }, group.headOption, aggs.headOption.map(_.column).filter(_ != "*"),
        order.headOption.map(_.column))
  }

  /** the prompt's tokens, and the token position of each schema name (a table, a column) in it */
  /** `names`: each schema name with its token position; `owners`: the table each belongs to (a table owns itself) — a
   * column's index is looked up WITHIN its table, since tables share column names (the first run trained the pointers
   * on another table's column of the same name and read them at 60%) */
  final case class Prompted(ids: Array[Int], names: Vector[(String, Int)], question: Vector[Int], qText: String, qChars: Vector[Int],
      owners: Vector[String]):
    def indexOf(table: String, column: String): Int = names.indices.find(i => owners(i) == table && names(i)._1 == column).getOrElse(-1)
    def indexOfTable(table: String): Int = names.indices.find(i => owners(i) == table && names(i)._1 == table).getOrElse(-1)

  def prompted[R[_]: Has[Read]](p: Pairs.Pair): Prompted ! R =
    val system = Prompt.system(p.tables)
    for
      ids <- Read.prompt(system, p.question)
      pieces <- Read.each(ids.toVector)(t => Read.decode(Seq(t)))
      full <- Read.decode(ids.toIndexedSeq)
    yield prompted(p, system, ids, pieces, full)

  /** the positions found in the prompt: `pieces` each token decoded, `full` all of them */
  def prompted(p: Pairs.Pair, system: String, ids: Array[Int], pieces: Vector[String], full: String): Prompted =
    // char offsets → token positions, by the decoded prefixes
    val ends = pieces.scanLeft(0)((n, s) => n + s.length).drop(1)
    val base = full.indexOf(system.take(40))
    def at(c: Int): Int = ends.indexWhere(_ > c)
    val owned = p.tables.values.toVector.flatMap { t =>
      val head = system.indexOf(s"table ${t.name} (")
      val tablePos = at(base + head + 6 + t.name.length - 1)
      ((t.name -> tablePos) -> t.name) +: t.columns.map { c =>
        val i = system.indexOf(s"\n  $c:", head)
        (c -> at(base + i + 3 + c.length - 1)) -> t.name
      }
    }
    val names = owned.map(_._1)
    // the question's tokens: their positions and the char offset (in the question) each begins at
    val qBase = full.indexOf(p.question)
    val qToks = ids.indices.filter(i => ends(i) > qBase && (if i == 0 then 0 else ends(i - 1)) < qBase + p.question.length).toVector
    val qChars = qToks.map(i => math.max(0, (if i == 0 then 0 else ends(i - 1)) - qBase))
    Prompted(ids, names, qToks, p.question, qChars, owned.map(_._2))

  // ---- the probes
  /** softmax regression: classes over features, by gradient descent (a few hundred examples: plain Scala) */
  final class Softmax(val classes: Vector[String], d: Int):
    val w: Array[Array[Float]] = Array.fill(classes.size)(Array.fill(d)(0f)); val b: Array[Float] = Array.fill(classes.size)(0f)
    def scores(x: Array[Float]): Array[Float] = Array.tabulate(classes.size)(c => b(c) + dot(w(c), x))
    def predict(x: Array[Float]): String = classes(scores(x).zipWithIndex.maxBy(_._1)._2)
    def train(xs: Vector[(Array[Float], String)], steps: Int = 300, lr: Float = 0.5f): Unit =
      val n = xs.size
      for _ <- 1 to steps do
        val gw = Array.fill(classes.size)(Array.fill(d)(0f)); val gb = Array.fill(classes.size)(0f)
        for (x, y) <- xs do
          val s = scores(x); val m = s.max; val e = s.map(v => math.exp((v - m).toDouble).toFloat); val z = e.sum
          for c <- classes.indices do
            val g = (e(c) / z - (if classes(c) == y then 1f else 0f)) / n
            gb(c) += g; var i = 0
            while i < d do { gw(c)(i) += g * x(i); i += 1 }
        for c <- classes.indices do
          b(c) -= lr * gb(c); var i = 0
          while i < d do { w(c)(i) -= lr * (gw(c)(i) + 1e-3f * w(c)(i)); i += 1 }

  /** a pointer: the query's state against each candidate key's state through learned maps, and a "none" key */
  final class Pointer(d: Int, k: Int = 64):
    val rnd = new scala.util.Random(1)
    val wq: Array[Array[Float]] = Array.fill(k)(Array.fill(d)(rnd.nextGaussian().toFloat / math.sqrt(d).toFloat))
    val wk: Array[Array[Float]] = Array.fill(k)(Array.fill(d)(rnd.nextGaussian().toFloat / math.sqrt(d).toFloat))
    val none: Array[Float] = Array.fill(k)(0f)
    private def proj(w: Array[Array[Float]], x: Array[Float]): Array[Float] = w.map(dot(_, x))
    def scores(q: Array[Float], keys: Vector[Array[Float]]): Array[Float] =
      val pq = proj(wq, q)
      (keys.map(kk => dot(pq, proj(wk, kk))) :+ dot(pq, none)).toArray
    /** the index chosen, keys.size meaning none */
    def predict(q: Array[Float], keys: Vector[Array[Float]]): Int = scores(q, keys).zipWithIndex.maxBy(_._1)._2
    /** batch gradient descent: the gradients summed over the examples and averaged, one step per pass — a per-example
     * step at the same rate diverged to NaN (and NaN scores pick index 0: the first name, every time) */
    def train(xs: Vector[(Array[Float], Vector[Array[Float]], Int)], steps: Int = 150, lr: Float = 0.05f): Unit =
      val n = xs.size
      for _ <- 1 to steps do
        val gq = Array.fill(k)(Array.fill(wq(0).length)(0f)); val gk = Array.fill(k)(Array.fill(wk(0).length)(0f))
        val gn = Array.fill(k)(0f)
        for (q, keys, y) <- xs do
          val pq = proj(wq, q); val pks = keys.map(proj(wk, _)) :+ none
          val s = pks.map(dot(pq, _)).toArray; val m = s.max; val e = s.map(v => math.exp((v - m).toDouble).toFloat); val z = e.sum
          val g = e.map(_ / z / n); g(y) -= 1f / n
          // d/dpq = Σ g_j pk_j; d/dpk_j = g_j pq; back through the linear maps
          val dpq = Array.fill(k)(0f)
          for (pk, j) <- pks.zipWithIndex; i <- 0 until k do dpq(i) += g(j) * pk(i)
          for i <- 0 until k do
            var t = 0
            while t < q.length do { gq(i)(t) += dpq(i) * q(t); t += 1 }
          for (kk, j) <- keys.zipWithIndex; i <- 0 until k do
            val gi = g(j) * pq(i); var t = 0
            while t < kk.length do { gk(i)(t) += gi * kk(t); t += 1 }
          for i <- 0 until k do gn(i) += g(keys.size) * pq(i)
        for i <- 0 until k do
          var t = 0
          while t < wq(i).length do { wq(i)(t) -= lr * gq(i)(t); wk(i)(t) -= lr * gk(i)(t); t += 1 }
          none(i) -= lr * gn(i)

  private def dot(a: Array[Float], b: Array[Float]): Float =
    var s = 0f; var i = 0
    while i < a.length do { s += a(i) * b(i); i += 1 }
    s

  /** the forced prefixes of the program up to each slot: where the model decides it */
  val prefixes: Vector[(String, String => String)] = Vector(
    "shape" -> (t => "```json\n{\"from\": \"" + t + "\", \""),
    "where" -> (t => "```json\n{\"from\": \"" + t + "\", \"where\": [{\"column\": \""),
    "group" -> (t => "```json\n{\"from\": \"" + t + "\", \"group\": [\""),
    "agg" -> (t => "```json\n{\"from\": \"" + t + "\", \"aggregate\": [{\"fn\": \"sum\", \"column\": \""),
    "order" -> (t => "```json\n{\"from\": \"" + t + "\", \"order\": [\""))


