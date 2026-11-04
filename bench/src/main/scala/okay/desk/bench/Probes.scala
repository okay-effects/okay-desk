package okay.desk.bench

import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*

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
        if order.nonEmpty then "order" else "-", if limit.isDefined then "limit" else "-", if join.isDefined then "join" else "-",
        if select.nonEmpty then "select" else "-").mkString(" ")
      Slots(shape, from, where.headOption.map {
        case Cond.Cmp(c, _, _) => c
        case Cond.Has(c, _) => c
        case Cond.Empty(c, _) => c }, group.headOption, aggs.headOption.map(_.column).filter(_ != "*"),
        order.headOption.map(_.column))
  }

  /** the prompt's tokens, and the token position of each schema name (a table, a column) in it */
  final case class Prompted(ids: Array[Int], names: Vector[(String, Int)])

  def prompted(chat: Chat, p: Pairs.Pair): Prompted =
    val tok = chat.tokenizer
    val system = Prompt.system(p.tables)
    val ids = chat.prompt(Vector(("system", system), ("user", p.question)).map((r, c) => okay.effects.json.Json.obj(
      "role" -> okay.effects.json.Json.Str(r), "content" -> okay.effects.json.Json.Str(c))), Nil, Map("enable_thinking" -> false))
    // char offsets → token positions, by the decoded prefixes
    val ends = ids.scanLeft(0)((n, t) => n + tok.decode(Seq(t)).length).drop(1)
    val full = tok.decode(ids.toIndexedSeq)
    val base = full.indexOf(system.take(40))
    def at(c: Int): Int = ends.indexWhere(_ > c)
    val names = p.tables.values.toVector.flatMap { t =>
      val head = system.indexOf(s"table ${t.name} (")
      val tablePos = at(base + head + 6 + t.name.length - 1)
      (t.name -> tablePos) +: t.columns.map { c =>
        val i = system.indexOf(s"\n  $c:", head)
        c -> at(base + i + 3 + c.length - 1)
      }
    }
    Prompted(ids, names)

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
    def train(xs: Vector[(Array[Float], Vector[Array[Float]], Int)], steps: Int = 200, lr: Float = 0.05f): Unit =
      for _ <- 1 to steps do
        for (q, keys, y) <- xs do
          val pq = proj(wq, q); val pks = keys.map(proj(wk, _)) :+ none
          val s = pks.map(dot(pq, _)).toArray; val m = s.max; val e = s.map(v => math.exp((v - m).toDouble).toFloat); val z = e.sum
          val g = e.map(_ / z); g(y) -= 1f
          // d/dpq = Σ g_j pk_j; d/dpk_j = g_j pq; back through the linear maps
          val dpq = Array.fill(k)(0f)
          for (pk, j) <- pks.zipWithIndex; i <- 0 until k do dpq(i) += g(j) * pk(i)
          for i <- 0 until k do
            var t = 0
            while t < wq(i).length do { wq(i)(t) -= lr * dpq(i) * q(t); t += 1 }
          for (kk, j) <- keys.zipWithIndex; i <- 0 until k do
            val gi = g(j) * pq(i); var t = 0
            while t < wk(i).length do { wk(i)(t) -= lr * gi * kk(t); t += 1 }
          for i <- 0 until k do none(i) -= lr * g(keys.size) * pq(i)

  private def dot(a: Array[Float], b: Array[Float]): Float =
    var s = 0f; var i = 0
    while i < a.length do { s += a(i) * b(i); i += 1 }
    s

  /** the states after each of `layers` at the given positions: layer → position → vector */
  def states(model: Qwen3, ids: Array[Int], layers: Seq[Int], at: Vector[Int]): Map[Int, Vector[Array[Float]]] =
    val got = scala.collection.mutable.Map.empty[Int, Vector[Array[Float]]]
    model.hook = new Qwen3.Hook:
      def after(i: Int, h: Arr)(using Scope): Arr =
        if layers.contains(i + 1) then
          val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
          got(i + 1) = at.map(p => all.slice(p * d, (p + 1) * d))
        h
    val st = model.newState(); val s = new Scope
    try { model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval(); got.toMap }
    finally { s.close(); st.close(); model.hook = null }

  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("mlx-community/Qwen3-0.6B-4bit")
    val layers = args.lift(1).map(_.split(",").map(_.toInt).toSeq).getOrElse(Seq(8, 12, 16, 20, 24))
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true).asInstanceOf[Qwen3]
    val train = Pairs.many(42, 600); val test = Pairs.many(43, 200)
    println(s"$repo: ${train.size} train, ${test.size} test pairs; layers ${layers.mkString(",")}")
    // every example: its slots, the last token's state and the schema names' states, per layer
    final case class Ex(slots: Slots, names: Vector[String], q: Map[Int, Array[Float]], keys: Map[Int, Vector[Array[Float]]])
    def examples(ps: Vector[Pairs.Pair]): Vector[Ex] = ps.flatMap { p =>
      slots(p.program).map { sl =>
        val pr = prompted(chat, p)
        val at = (pr.ids.length - 1) +: pr.names.map(_._2)
        val st = states(model, pr.ids, layers, at)
        Ex(sl, pr.names.map(_._1), st.map((l, v) => l -> v.head), st.map((l, v) => l -> v.tail))
      }
    }
    val t0 = System.nanoTime()
    val trainEx = examples(train); val testEx = examples(test)
    println(f"states read: ${(System.nanoTime() - t0) / 1e9}%.0f s; shapes: ${trainEx.map(_.slots.shape).distinct.size}")
    val d = trainEx.head.q(layers.head).length
    for l <- layers do
      val shape = Softmax(trainEx.map(_.slots.shape).distinct, d)
      shape.train(trainEx.map(e => (e.q(l), e.slots.shape)))
      val shapeAcc = testEx.count(e => shape.predict(e.q(l)) == e.slots.shape) * 100.0 / testEx.size
      def pointerAcc(slot: Slots => Option[String]): Double =
        val p = Pointer(d)
        def target(e: Ex) = slot(e.slots).map(e.names.indexOf).filter(_ >= 0).getOrElse(e.names.size)
        p.train(trainEx.map(e => (e.q(l), e.keys(l), target(e))))
        testEx.count(e => p.predict(e.q(l), e.keys(l)) == target(e)) * 100.0 / testEx.size
      val table = pointerAcc(s => Some(s.table)); val where = pointerAcc(_.where)
      val group = pointerAcc(_.group); val agg = pointerAcc(_.agg); val order = pointerAcc(_.order)
      println(f"layer $l%2d: shape $shapeAcc%5.1f%%  table $table%5.1f%%  where-column $where%5.1f%%  group-column $group%5.1f%%  " +
        f"agg-column $agg%5.1f%%  order-column $order%5.1f%%")
    model.close()
