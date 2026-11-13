package okay.desk.bench

import okay.desk.skill.LogicRead
import okay.desk.statement.Reader

/** THE LAW OF `HeadGrads`: each head's gradient with respect to its input equals the finite difference of the loss
 * computed by the HEAD'S OWN forward (`Masked.score`, `Gaze.scores`) — so the gradient is of the head the page runs */
class HeadGradsSuite extends munit.FunSuite:
  private val rnd = scala.util.Random(7)
  private def vec(d: Int, s: Float = 1f) = Array.fill(d)(rnd.nextGaussian().toFloat * s)
  private def ce(scores: Seq[Float], y: Int): Double =
    val m = scores.max
    val z = scores.map(s => math.exp((s - m).toDouble)).sum
    -(scores(y) - m - math.log(z))

  test("the tag head's gradient is the loss's finite difference") {
    val d = 6
    val h = LogicRead.Masked(Vector("a", "b", "c", "e"), 2 * d)
    for k <- h.letters.indices do { h.weights(k).indices.foreach(j => h.weights(k)(j) = rnd.nextGaussian().toFloat) }
    val x = vec(2 * d)
    val y = 2
    val (loss, g) = HeadGrads.tag(h, x, y)
    def lossAt(v: Array[Float]) = ce(h.letters.indices.map(h.score(_, v)), y)
    assertEqualsDouble(loss.toDouble, lossAt(x), 1e-4)
    for j <- x.indices do
      val eps = 1e-3f
      val up = x.clone(); up(j) += eps
      val down = x.clone(); down(j) -= eps
      assertEqualsDouble(g(j).toDouble, (lossAt(up) - lossAt(down)) / (2 * eps), 2e-3)
  }

  test("the frame head's gradient is the loss's finite difference, its forward the head's own") {
    val (d, n, looks) = (8, 5, 4)
    val gaze = Reader.Gaze(n, d, looks)
    for a <- gaze.arrays; j <- a.indices do a(j) = rnd.nextGaussian().toFloat * 0.05f
    val ts = Vector.fill(5)(vec(d))
    val y = 3
    val (loss, g) = HeadGrads.frame(gaze.arrays, looks, ts, y)
    def lossAt(v: Vector[Array[Float]]) = ce(gaze.scores(v), y)
    assertEqualsDouble(loss.toDouble, lossAt(ts), 1e-4)
    for k <- ts.indices; j <- 0 until d do
      val eps = 1e-3f
      val up = ts.map(_.clone); up(k)(j) += eps
      val down = ts.map(_.clone); down(k)(j) -= eps
      assertEqualsDouble(g(k)(j).toDouble, (lossAt(up) - lossAt(down)) / (2 * eps), 3e-3)
  }
