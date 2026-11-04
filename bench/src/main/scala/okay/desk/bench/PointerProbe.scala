package okay.desk.bench

/** the pointer probe alone, on synthetic data: the query is the chosen key plus noise — it must learn that */
object PointerProbe:
  def main(args: Array[String]): Unit =
    val rnd = new scala.util.Random(2); val d = 64
    def vec() = Array.fill(d)(rnd.nextGaussian().toFloat)
    val data = Vector.fill(300) {
      val keys = Vector.fill(4)(vec()); val y = rnd.nextInt(5)
      val q = if y < 4 then keys(y).map(_ + rnd.nextGaussian().toFloat * 0.3f) else vec()
      (q, keys, y)
    }
    val p = Probes.Pointer(d, 16)
    val before = data.count((q, k, y) => p.predict(q, k) == y)
    p.train(data, steps = 150, lr = 0.05f)
    val after = data.count((q, k, y) => p.predict(q, k) == y)
    val s = p.scores(data.head._1, data.head._2)
    println(s"pointer: before $before/300, after $after/300; scores ${s.mkString(", ")}")
