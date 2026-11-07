package okay.desk.bench

import mlx.llm.{Chat, Model, Models}
import okay.desk.*
import okay.desk.skill.*
import scala.collection.mutable.ArrayBuffer

/**
 * CAN A COMPATIBILITY LAYER BE A MATRIX? (okay-inlay specs/bus.md §6, BACKLOG 14; specs/data.md §31): the reference
 * skill file — trained on cut19's layer 19 (bus v1) — read UNCHANGED on another model through a linear map of its
 * states. The reading points recorded by running the readers on the reference over training questions; the other
 * model's states at the same token positions (the same tokenizer: Qwen3-1.7B); ridge regression per layer; the best
 * layer by held-out R²; then the sample and the held-out pairs read through the map.
 *
 * `sbt "bench/runMain okay.desk.bench.BusAdapter [source repo] [layers]"` — DESK_ADAPTER_PAIRS (questions, 400),
 * DESK_RIDGE (λ, relative to the mean diagonal, 0.1).
 */
object BusAdapter:
  /** a reading that keeps every state it was asked for, with the answer */
  final class Recording(r: Reading) extends Reading:
    val asked = ArrayBuffer.empty[(Array[Int], Vector[Int], Vector[Array[Float]])]
    def answer[X](op: Read[X]): X = op match
      case s @ Read.States(ids, at) =>
        val got = r.answer(s); asked += ((ids, at, got)); got.asInstanceOf[X]
      case o => r.answer(o)

  /** a reading whose states go through the map: x ↦ (x − μx)·W + μy */
  final class Mapped(r: Reading, map: Ridge) extends Reading:
    def answer[X](op: Read[X]): X = op match
      case s @ Read.States(_, _) => r.answer(s).map(map(_)).asInstanceOf[X]
      case o => r.answer(o)

  /** a linear map fitted by ridge regression on centred data: W = (XᵀX + λI)⁻¹ XᵀY */
  final class Ridge(val mx: Array[Double], val my: Array[Double], val w: Array[Array[Double]]):
    def apply(x: Array[Float]): Array[Float] =
      val dOut = my.length; val out = Array.tabulate(dOut)(my(_))
      var i = 0
      while i < x.length do
        val xi = x(i) - mx(i)
        if xi != 0 then
          val row = w(i); var j = 0
          while j < dOut do { out(j) += xi * row(j); j += 1 }
        i += 1
      out.map(_.toFloat)

  object Ridge:
    def fit(xs: IndexedSeq[Array[Float]], ys: IndexedSeq[Array[Float]], lambda: Double): Ridge =
      val n = xs.size; val dIn = xs.head.length; val dOut = ys.head.length
      val mx = Array.tabulate(dIn)(i => xs.map(_(i).toDouble).sum / n)
      val my = Array.tabulate(dOut)(j => ys.map(_(j).toDouble).sum / n)
      // XᵀX and XᵀY, accumulated row by row (centred)
      val xtx = Array.ofDim[Double](dIn, dIn); val xty = Array.ofDim[Double](dIn, dOut)
      val xc = new Array[Double](dIn); val yc = new Array[Double](dOut)
      for k <- 0 until n do
        val x = xs(k); val y = ys(k)
        var i = 0
        while i < dIn do { xc(i) = x(i) - mx(i); i += 1 }
        i = 0
        while i < dOut do { yc(i) = y(i) - my(i); i += 1 }
        i = 0
        while i < dIn do
          val a = xc(i)
          if a != 0 then
            val r = xtx(i); var j = i
            while j < dIn do { r(j) += a * xc(j); j += 1 }
            val t = xty(i); j = 0
            while j < dOut do { t(j) += a * yc(j); j += 1 }
          i += 1
      for i <- 0 until dIn; j <- 0 until i do xtx(i)(j) = xtx(j)(i)
      val ridge = lambda * (0 until dIn).map(i => xtx(i)(i)).sum / dIn
      for i <- 0 until dIn do xtx(i)(i) += ridge
      // Cholesky: XᵀX + λI = L Lᵀ, then W = L⁻ᵀ L⁻¹ XᵀY
      val l = xtx
      for j <- 0 until dIn do
        var s = l(j)(j)
        var k = 0
        while k < j do { s -= l(j)(k) * l(j)(k); k += 1 }
        val d = math.sqrt(math.max(s, 1e-12)); l(j)(j) = d
        var i = j + 1
        while i < dIn do
          var t = l(i)(j); k = 0
          val li = l(i); val lj = l(j)
          while k < j do { t -= li(k) * lj(k); k += 1 }
          l(i)(j) = t / d
          i += 1
      // forward: L Z = XᵀY; backward: Lᵀ W = Z (in place in xty)
      for i <- 0 until dIn do
        val zi = xty(i); var k = 0
        while k < i do
          val c = l(i)(k)
          if c != 0 then { val zk = xty(k); var j = 0; while j < dOut do { zi(j) -= c * zk(j); j += 1 } }
          k += 1
        val d = l(i)(i); var j = 0
        while j < dOut do { zi(j) /= d; j += 1 }
      for i <- (dIn - 1) to 0 by -1 do
        val wi = xty(i); var k = i + 1
        while k < dIn do
          val c = l(k)(i)
          if c != 0 then { val wk = xty(k); var j = 0; while j < dOut do { wi(j) -= c * wk(j); j += 1 } }
          k += 1
        val d = l(i)(i); var j = 0
        while j < dOut do { wi(j) /= d; j += 1 }
      Ridge(mx, my, xty)

  /** the share of the target's variance the map explains, on held-out vectors */
  def r2(map: Ridge, xs: IndexedSeq[Array[Float]], ys: IndexedSeq[Array[Float]]): Double =
    var res = 0.0; var tot = 0.0
    for (x, y) <- xs.zip(ys) do
      val p = map(x)
      for j <- y.indices do
        res += (y(j) - p(j)) * (y(j) - p(j)); tot += (y(j) - map.my(j)) * (y(j) - map.my(j))
    1 - res / tot

  def main(args: Array[String]): Unit =
    val teacherRepo = "local/Qwen3-0.6B-4bit-cut19"
    val sourceRepo = args.headOption.getOrElse("mlx-community/Qwen3-1.7B-4bit")
    val layers = args.lift(1).getOrElse("16,19,22").split(",").map(_.toInt).toVector
    val n = sys.env.getOrElse("DESK_ADAPTER_PAIRS", "400").toInt
    val lambda = sys.env.getOrElse("DESK_RIDGE", "0.1").toDouble
    def load(repo: String) =
      val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
      (Chat.load(dir), Model.load(dir, mapped = true))
    // 1. the reading points: the readers run on the reference, every state asked recorded
    val (tChat, tModel) = load(teacherRepo)
    val skill = MlxReading.ready(tChat, tModel, 19, teacherRepo, sys.error("no skill file for the reference"))
    // THE CONTROL (DESK_ADAPTER_CONTROL=1): the states replaced by one constant vector — what the exact rules and the
    // model's own logits (ask) read with no state at all; the map's gain is the difference
    if sys.env.contains("DESK_ADAPTER_CONTROL") then
      val (sChat, sModel) = load(sourceRepo)
      val zero = Ridge(Array.fill(2048)(0.0), Array.fill(1024)(0.0), Array.fill(2048, 1024)(0.0))
      evaluate(skill, Mapped(MlxReading(sChat, sModel, layers.head), zero), s"$sourceRepo, the states replaced by a constant")
      return
    val rec = Recording(MlxReading(tChat, tModel, 19))
    val reader = Readers.On(skill.readers, rec)
    val train = Pairs.many(46, n)
    val t0 = System.nanoTime()
    train.foreach(p => reader.program(p.tables, p.question))
    val points = rec.asked.flatMap((ids, at, got) => at.zip(got).map((p, y) => (ids, p, y)))
    println(f"reading points: ${points.size} from ${rec.asked.size} reads of $n questions (${(System.nanoTime() - t0) / 1e9}%.0f s)")
    // 2. the source's states at the same positions, every layer asked in one forward a read
    val (sChat, sModel) = load(sourceRepo)
    val src = MlxReading(sChat, sModel, layers.head)
    val xs = layers.map(_ -> ArrayBuffer.empty[Array[Float]]).toMap
    for (ids, at, _) <- rec.asked do
      val got = src.statesAt(ids, layers, at)
      layers.foreach(l => xs(l) ++= got(l))
    val ys = points.map(_._3)
    // 3. a map per layer; held-out R² (every 10th point held out)
    val (trainIdx, testIdx) = ys.indices.partition(_ % 10 != 0)
    val fits = layers.map { l =>
      val t1 = System.nanoTime()
      val map = Ridge.fit(trainIdx.map(xs(l)), trainIdx.map(ys), lambda)
      val score = r2(map, testIdx.map(xs(l)), testIdx.map(ys))
      println(f"  layer $l: held-out R² ${score}%.3f (${(System.nanoTime() - t1) / 1e9}%.0f s)")
      (l, map, score)
    }
    val (best, map, score) = fits.maxBy(_._3)
    println(f"the map: $sourceRepo layer $best → bus v1 (cut19 layer 19), R² $score%.3f")
    // 4. the skill unchanged, read through the map
    evaluate(skill, Mapped(MlxReading(sChat, sModel, best), map), s"$sourceRepo through the map")

  /** the sample and the held-out pairs read by the skill unchanged on a reading */
  def evaluate(skill: Readers.On, reading: Reading, what: String): Unit =
    val mapped = Readers.On(skill.readers, reading)
    var right = Map("en" -> 0, "ru" -> 0)
    for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
      val gold = Sample.run(Query.parse(q.gold).toOption.get).toOption.get
      val got = mapped.program(Sample.tables, text)
      if got.flatMap(Query.parse).flatMap(Sample.run).exists(Sample.same(_, gold)) then right = right.updated(lang, right(lang) + 1)
      else println(s"  [$lang ${i + 1}] ${got.fold(e => s"($e)", identity)}")
    println(s"$what, the skill unchanged | en: ${right("en")}/${Sample.questions.size} | " +
      s"ru: ${right("ru")}/${Sample.questions.size}")
    mapped.measureByConstructor(Pairs.many(44, sys.env.getOrElse("DESK_CONSTRUCTORS", "300").toInt))
