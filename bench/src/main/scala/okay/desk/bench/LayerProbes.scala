package okay.desk.bench

import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Model, Models, Qwen3}
import okay.desk.*
import okay.desk.skill.*

/**
 * THE SLOTS READ FROM THE MODEL'S STATE (specs/data.md §7, step 1): over the page's prompt for a pair, the residual
 * after a layer is read at the last token (the question understood) and at the schema's names in the prompt. A
 * logistic probe reads the SHAPE of the program; a bilinear pointer reads the TABLE and each COLUMN slot as the
 * schema position the question points at (score (qW)·(kV), a "none" key when the slot is empty). Trained on the
 * generated pairs (the gold slots known), measured per slot on held-out pairs, per layer — the first question of
 * §7: do the slots read linearly from a 0.6B?
 */
object LayerProbes:
  import okay.desk.skill.Probes.*

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
    final case class Ex(slots: Slots, names: Vector[String], q: Map[(String, Int), Array[Float]], keys: Map[Int, Vector[Array[Float]]], pr: Prompted)
    def examples(ps: Vector[Pairs.Pair]): Vector[Ex] = ps.flatMap { p =>
      slots(p.program).map { sl =>
        val pr = prompted(MlxReading(chat, model, layers.head), p)
        val base = states(model, pr.ids, layers, (pr.ids.length - 1) +: pr.names.map(_._2))
        val q = scala.collection.mutable.Map.empty[(String, Int), Array[Float]]
        for l <- layers do q(("last", l)) = base(l).head
        for (name, prefix) <- prefixes do
          val ids = pr.ids ++ chat.tokenizer.encode(prefix(sl.table))
          val st = states(model, ids, layers, Vector(ids.length - 1))
          for l <- layers do q((name, l)) = st(l).head
        Ex(sl, pr.names.map(_._1), q.toMap, base.map((l, v) => l -> v.tail), pr)
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
        def target(e: Ex) = slot(e.slots).map(c => e.pr.indexOf(e.slots.table, c)).filter(_ >= 0).getOrElse(e.names.size)
        p.train(trainEx.map(e => (z(e.q((at, l))), e.keys(l).map(z), target(e))), steps = 150, lr = 0.05f)
        // counted where the gold HAS the slot: the empty slots (60–85%) had inflated the first numbers
        val has = testEx.filter(e => slot(e.slots).isDefined)
        val preds = has.map(e => p.predict(z(e.q((at, l))), e.keys(l).map(z)))
        val acc = preds.zip(has).count((pr, e) => pr == target(e)) * 100.0 / math.max(1, has.size)
        if preds.distinct.size == 1 then println(s"  (a pointer predicting one index only: ${preds.head})")
        val none = testEx.count(e => target(e) == e.names.size) * 100.0 / testEx.size
        (acc, none)
      val table = pointerAcc("last", s => Some(s.table)); val where = pointerAcc("where", _.where)
      val group = pointerAcc("group", _.group); val agg = pointerAcc("agg", _.agg); val order = pointerAcc("order", _.order)
      println(f"layer $l%2d: shape $shapeAcc%5.1f%%  table ${table._1}%5.1f%%  where ${where._1}%5.1f%% (none ${where._2}%.0f%%)  " +
        f"group ${group._1}%5.1f%% (none ${group._2}%.0f%%)  agg ${agg._1}%5.1f%% (none ${agg._2}%.0f%%)  order ${order._1}%5.1f%% (none ${order._2}%.0f%%)")
    model.close()
