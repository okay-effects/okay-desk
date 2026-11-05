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
  final case class Prompted(ids: Array[Int], names: Vector[(String, Int)], question: Vector[Int], qText: String, qChars: Vector[Int])

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
    // the question's tokens: their positions and the char offset (in the question) each begins at
    val qBase = full.indexOf(p.question)
    val qToks = ids.indices.filter(i => ends(i) > qBase && (if i == 0 then 0 else ends(i - 1)) < qBase + p.question.length).toVector
    val qChars = qToks.map(i => math.max(0, (if i == 0 then 0 else ends(i - 1)) - qBase))
    Prompted(ids, names, qToks, p.question, qChars)

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

  /** the states after each of `layers` at the given positions: layer → position → vector */
  def states(model: Qwen3, ids: Array[Int], layers: Seq[Int], at: Vector[Int]): Map[Int, Vector[Array[Float]]] =
    val got = scala.collection.mutable.Map.empty[Int, Vector[Array[Float]]]
    model.hook = new Qwen3.Hook:
      def after(i: Int, h: Arr)(using Scope): Arr =
        if layers.contains(i + 1) then
          val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats.map(v => if v.isNaN || v.isInfinite then 0f else v)
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
    // every example: its slots, the schema names' states, and the query state per slot READ WHERE THE MODEL
    // DECIDES: after a forced prefix of the program up to that slot ("…, \"where\": [{\"column\": \"") — the last
    // token of the question alone does not hold which column is meant (the first run: the pointers at the "none" rate)
    final case class Ex(slots: Slots, names: Vector[String], q: Map[(String, Int), Array[Float]], keys: Map[Int, Vector[Array[Float]]])
    def examples(ps: Vector[Pairs.Pair]): Vector[Ex] = ps.flatMap { p =>
      slots(p.program).map { sl =>
        val pr = prompted(chat, p)
        val base = states(model, pr.ids, layers, (pr.ids.length - 1) +: pr.names.map(_._2))
        val q = scala.collection.mutable.Map.empty[(String, Int), Array[Float]]
        for l <- layers do q(("last", l)) = base(l).head
        for (name, prefix) <- prefixes do
          val ids = pr.ids ++ chat.tokenizer.encode(prefix(sl.table))
          val st = states(model, ids, layers, Vector(ids.length - 1))
          for l <- layers do q((name, l)) = st(l).head
        Ex(sl, pr.names.map(_._1), q.toMap, base.map((l, v) => l -> v.tail))
      }
    }
    val t0 = System.nanoTime()
    val trainEx = examples(train); val testEx = examples(test)
    println(f"states read: ${(System.nanoTime() - t0) / 1e9}%.0f s; shapes: ${trainEx.map(_.slots.shape).distinct.size}")
    val d = trainEx.head.q(("last", layers.head)).length
    for l <- layers do
      // the states standardized per dimension (the residual's scale is tens to hundreds): the probes' steps stay sane
      val all = trainEx.map(_.q(("last", l))) ++ trainEx.flatMap(_.keys(l))
      val mean = Array.tabulate(d)(i => all.map(_(i)).sum / all.size)
      val std = Array.tabulate(d)(i => math.max(math.sqrt(all.map(x => (x(i) - mean(i)) * (x(i) - mean(i))).sum / all.size), 1e-3).toFloat)
      // clipped: a dimension of tiny variance would blow a state up (the residual has such dimensions)
      // and scaled to a norm near 8 (as the synthetic data the pointer was checked on): at a norm of ~100 the bilinear
      // scores overflow float32 and the pointer turns NaN
      val scale = 8f / math.sqrt(d).toFloat
      def z(x: Array[Float]) = Array.tabulate(d)(i => math.max(-4f, math.min(4f, (x(i) - mean(i)) / std(i))) * scale)
      val shape = Softmax(trainEx.map(_.slots.shape).distinct, d)
      shape.train(trainEx.map(e => (z(e.q(("shape", l))), e.slots.shape)), steps = 300, lr = 0.05f)
      val shapeAcc = testEx.count(e => shape.predict(z(e.q(("shape", l)))) == e.slots.shape) * 100.0 / testEx.size
      def pointerAcc(at: String, slot: Slots => Option[String]): (Double, Double) =
        val p = Pointer(d)
        def target(e: Ex) = slot(e.slots).map(e.names.indexOf).filter(_ >= 0).getOrElse(e.names.size)
        p.train(trainEx.map(e => (z(e.q((at, l))), e.keys(l).map(z), target(e))), steps = 150, lr = 0.05f)
        val preds = testEx.map(e => p.predict(z(e.q((at, l))), e.keys(l).map(z)))
        val acc = preds.zip(testEx).count((pr, e) => pr == target(e)) * 100.0 / testEx.size
        if preds.distinct.size == 1 then println(s"  (a pointer predicting one index only: ${preds.head})")
        val none = testEx.count(e => target(e) == e.names.size) * 100.0 / testEx.size
        (acc, none)
      val table = pointerAcc("last", s => Some(s.table)); val where = pointerAcc("where", _.where)
      val group = pointerAcc("group", _.group); val agg = pointerAcc("agg", _.agg); val order = pointerAcc("order", _.order)
      println(f"layer $l%2d: shape $shapeAcc%5.1f%%  table ${table._1}%5.1f%%  where ${where._1}%5.1f%% (none ${where._2}%.0f%%)  " +
        f"group ${group._1}%5.1f%% (none ${group._2}%.0f%%)  agg ${agg._1}%5.1f%% (none ${agg._2}%.0f%%)  order ${order._1}%5.1f%% (none ${order._2}%.0f%%)")
    model.close()
