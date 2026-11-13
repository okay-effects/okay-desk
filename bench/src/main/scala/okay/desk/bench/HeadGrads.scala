package okay.desk.bench

import okay.desk.skill.LogicRead
import okay.desk.statement.{Con, Reader, Span}
import okay.desk.statement.Assemble.Tag

/**
 * THE HEADS' GRADIENT WITH RESPECT TO WHAT THEY READ (specs/core-joint.md §2): each head's cross-entropy at the gold
 * answer and its derivative with respect to its INPUT states — the chain rule the head's own training takes one
 * step further. With it the core can be trained through the heads (`JointTrain`) while the heads stay the one
 * implementation the page runs. Tags and frames: they carry most of a text's decisions.
 */
object HeadGrads:
  private def dot(a: Array[Float], b: Array[Float], from: Int = 0): Float =
    var (s, i) = (0f, 0)
    while i < b.length do { s += a(from + i) * b(i); i += 1 }
    s
  private def softmax(z: Array[Float]): Array[Float] =
    val m = z.max
    val e = z.map(v => math.exp((v - m).toDouble).toFloat)
    val s = e.sum
    e.map(_ / s)

  /** the tag head (a softmax over every tag, linear in x = a token's state ++ the state at its word's end) */
  def tag(h: LogicRead.Masked, x: Array[Float], y: Int): (Float, Array[Float]) =
    val p = softmax(Array.tabulate(h.letters.size)(h.score(_, x)))
    val g = new Array[Float](x.length)
    for k <- p.indices do
      val c = p(k) - (if k == y then 1f else 0f)
      var j = 0
      while j < x.length do { g(j) += c * h.weights(k)(j); j += 1 }
    (-math.log(math.max(p(y), 1e-12f).toDouble).toFloat, g)

  /** a frame head (`Reader.Gaze`: `looks` softmax looks over the sentence's tokens, then linear over the looks and
   * the last token's state), read from its arrays: u (looks), w (classes), b */
  def frame(arrays: Vector[Array[Float]], looks: Int, ts: Vector[Array[Float]], y: Int)
      : (Float, Vector[Array[Float]]) =
    val d = ts.head.length
    val n = arrays.size - looks - 1
    val (u, w, b) = (arrays.take(looks), arrays.slice(looks, looks + n), arrays.last)
    val c = 8f / math.sqrt(d).toFloat
    val wide = (looks + 1) * d
    val x = new Array[Float](wide)
    val a = Array.tabulate(looks) { h =>
      val weights = softmax(ts.map(t => c * dot(u(h), t)).toArray)
      for (t, k) <- ts.zipWithIndex do
        var j = 0
        while j < d do { x(h * d + j) += c * weights(k) * t(j); j += 1 }
      weights }
    for j <- 0 until d do x(looks * d + j) = c * ts.last(j)
    val p = softmax(Array.tabulate(n)(k => dot(w(k), x) + b(k)))
    val dx = new Array[Float](wide)
    for k <- 0 until n do
      val g = p(k) - (if k == y then 1f else 0f)
      var j = 0
      while j < wide do { dx(j) += g * w(k)(j); j += 1 }
    val gt = Vector.fill(ts.size)(new Array[Float](d))
    for h <- 0 until looks do
      val da = ts.map(t => c * dot(dx, t, h * d))
      val mean = da.indices.map(k => a(h)(k) * da(k)).sum
      for k <- ts.indices do
        val dz = a(h)(k) * (da(k) - mean)
        var j = 0
        while j < d do
          gt(k)(j) += c * a(h)(k) * dx(h * d + j) + dz * c * u(h)(j)
          j += 1
    for j <- 0 until d do gt.last(j) += c * dx(looks * d + j)
    (-math.log(math.max(p(y), 1e-12f).toDouble).toFloat, gt)

  /** where each token's word ends (`Seen.reads`: a token is read with the state at its word's last token) */
  def ends(seen: Reader.Seen): Array[Int] =
    val (text, tokens) = (seen.text, seen.tokens)
    def goesOn(j: Int): Boolean =
      val at = tokens(j).from
      at > 0 && at == tokens(j - 1).until && text(at).isLetterOrDigit && text(at - 1).isLetterOrDigit
    val e = new Array[Int](tokens.size)
    for i <- tokens.indices.reverse do e(i) = if i + 1 < tokens.size && goesOn(i + 1) then e(i + 1) else i
    e
  /** the tokens of a sentence (`Seen.each`) */
  def inside(seen: Reader.Seen, s: Span): Vector[Int] =
    seen.tokens.indices.toVector.filter(i => seen.tokens(i).from < s.until && s.from < seen.tokens(i).until)

  /** a text's loss on the gold path and its gradient with respect to each token's (normalized) state: every tag
   * (an Outside token weighed `outside`, as the heads' training samples them) and every frame asked; the mean over
   * decisions of each kind, the two kinds added */
  def text(heads: Reader.Heads, seen: Reader.Seen, tags: Vector[Tag], frames: Vector[(Int, Int, Option[Con])],
      outside: Float = 0.35f): (Float, Vector[Array[Float]]) =
    val d = heads.d
    val g = Vector.fill(seen.states.size)(new Array[Float](d))
    val e = ends(seen)
    var tagLoss = 0f
    val tagWeights = tags.map(t => if t == Tag.Outside then outside else 1f)
    val tagTotal = math.max(tagWeights.sum, 1e-6f)
    for (t, i) <- tags.zipWithIndex do
      val (l, gx) = tag(heads.tags, seen.reads(i), Reader.tagNames.indexOf(t.toString))
      val wgt = tagWeights(i) / tagTotal
      tagLoss += wgt * l
      for j <- 0 until d do { g(i)(j) += wgt * gx(j); g(e(i))(j) += wgt * gx(d + j) }
    var frameLoss = 0f
    val asked = frames.filter(_._2 < heads.frames.size)
    for (sentence, k, con) <- asked do
      val at = inside(seen, seen.sentences(sentence))
      if at.nonEmpty then
        val (l, gt) = frame(heads.frames(k).arrays, 4, at.map(seen.states), heads.frameClass(con))
        frameLoss += l / asked.size
        for (i, gi) <- at.zip(gt); j <- 0 until d do g(i)(j) += gi(j) / asked.size
    (tagLoss + frameLoss, g)

