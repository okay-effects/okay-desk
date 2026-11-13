package okay.desk.skill

import okay.llm.compute.{Compute, QLinear}
import okay.llm.qwen.Tensors
import scala.concurrent.Future

/** Low-rank adapters stay separate from the 4-bit backbone. Both factors use two 4-bit residual planes.
  * All projections execute on the selected device, including WebGL2; there is no CPU readback per projection.
  */
final class Adapted(val base: Compute, weights: Tensors, adapter: Tensors) extends Compute:
  type T = base.T
  final case class Delta(a: base.W, ar: base.W, b: base.W, br: base.W)
  type W = (base.W, Option[Delta])
  private var sources: Option[(Tensors, Tensors)] = Some(weights -> adapter)
  private val pending = scala.collection.mutable.Map.empty[Array[Int], Delta]
  val tensors: Tensors = new Tensors:
    private def original = sources.getOrElse(throw IllegalStateException("model sources released"))._1
    def has(name: String): Boolean = original.has(name)
    def floats(name: String): Array[Float] = original.floats(name)
    def words(name: String): Array[Int] = original.words(name)
    def shape(name: String): Vector[Int] = original.shape(name)
    override def linear(prefix: String): QLinear =
      val q = original.linear(prefix)
      val extra = sources.get._2
      if extra.has(prefix + ".a.weight") then
        pending(q.words) = Delta(base.quantized(extra.linear(prefix + ".a")),
          base.quantized(extra.linear(prefix + ".ar")), base.quantized(extra.linear(prefix + ".b")),
          base.quantized(extra.linear(prefix + ".br")))
      q
  /** Called immediately after Qwen3Compute consumes the tensors: the large file buffers are not retained. */
  /** ON OR OFF: an ability's adapter (specs/core-joint.md §6) is on while its texts are read and off for the
   * other readers of the base, which then read the base exactly */
  var active: Boolean = true
  def releaseSources(): Unit =
    sources = None
    require(pending.isEmpty, "unconsumed adapter projections")
  def api: String = base.api
  override def close(): Unit = base.close()
  override def cache(x: T): T = base.cache(x)
  def tensor(rows: Int, cols: Int, data: Array[Float]): T = base.tensor(rows, cols, data)
  def quantized(q: QLinear): W = base.quantized(q) -> pending.remove(q.words)
  def linear(x: T, w: W): T =
    val y = base.linear(x, w._1)
    w._2 match
      case _ if !active => y
      case None => y
      case Some(d) =>
        def both(input: T, a: base.W, b: base.W): T =
          val first = base.linear(input, a)
          val second = base.linear(input, b)
          val sum = base.add(first, second)
          base.release(first); base.release(second)
          sum
        val low = both(x, d.a, d.ar)
        val delta = both(low, d.b, d.br)
        base.release(low)
        val sum = base.add(y, delta)
        base.release(y); base.release(delta)
        sum
  def rmsNorm(x: T, w: T, eps: Float): T = base.rmsNorm(x, w, eps)
  def headNormRope(x: T, w: T, heads: Int, headDim: Int, eps: Float, theta: Float, pos0: Int): T =
    base.headNormRope(x, w, heads, headDim, eps, theta, pos0)
  def attention(q: T, k: T, v: T, heads: Int, kvHeads: Int, headDim: Int, pos0: Int): T =
    base.attention(q, k, v, heads, kvHeads, headDim, pos0)
  def concat(a: T, b: T): T = base.concat(a, b)
  def add(a: T, b: T): T = base.add(a, b)
  def swiglu(gate: T, up: T): T = base.swiglu(gate, up)
  def slice(x: T, from: Int, until: Int): T = base.slice(x, from, until)
  def rows(x: T, at: Seq[Int]): Future[Vector[Array[Float]]] = base.rows(x, at)
  def release(x: T): Unit = base.release(x)
