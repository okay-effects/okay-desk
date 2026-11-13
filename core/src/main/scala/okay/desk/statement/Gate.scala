package okay.desk.statement

/**
 * THE LEARNT GATE (specs/core-joint.md §9): whether a reading's answer is shown or asked about, by a logistic model of
 * "this answer is wrong" over the reading's signs — each head's least margin relative to its bar, for the reader
 * over the adapted core (`a`) and for the reader over the plain base (`b`); the words and pairs never met; whether
 * the two readers give the same answer; the language. Fitted on the unseen families and the teacher's test
 * (`scripts/learnt-gate.scala`), never on the sealed set; one implementation for the bench and the page.
 *
 * The file: a line `weights w0 w1 …` (the bias first, in the order of `features`), a line `threshold t` (the largest
 * probability of wrong that is shown).
 */
final case class Gate(weights: Vector[Double], threshold: Double):
  /** the probability that `a`'s answer is wrong */
  def wrong(features: Array[Double]): Double =
    require(features.length == weights.size, s"the gate has ${weights.size} weights, ${features.length} signs")
    1 / (1 + math.exp(-features.indices.map(j => weights(j) * features(j)).sum))
  /** `a`'s answer shown: it has one, and the gate deems it right */
  def shows(a: Reader.Reading, barsA: Map[String, Float], b: Reader.Reading, barsB: Map[String, Float],
      russian: Boolean): Boolean =
    a.result.nonEmpty && wrong(Gate.features(a, barsA, b, barsB, russian)) <= threshold

object Gate:
  val kinds: Vector[String] = Vector("tag", "frame", "link", "pick")
  /** a margin relative to its bar, minus one, held to [−3, 3]; a kind with no decision counts as sure (3) */
  def relative(least: Map[String, Float], bars: Map[String, Float], language: String, kind: String): Double =
    least.get(kind).fold(3.0)(m =>
      math.max(-3.0, math.min(3.0, m / math.max(1e-3, bars.getOrElse(s"$language $kind", 1f)).toDouble - 1)))
  /** an answer as the bench writes it */
  def answer(r: Reader.Reading): String = r.result.fold("")(_.mkString("|"))
  /** the signs: 1, a's four margins, b's four, log(1 + words never met), log(1 + pairs never met), the two agree,
   * the text is Cyrillic */
  def features(a: Reader.Reading, barsA: Map[String, Float], b: Reader.Reading, barsB: Map[String, Float],
      russian: Boolean): Array[Double] =
    val language = if russian then "ru" else "en"
    Array(1.0) ++ kinds.map(relative(a.least, barsA, language, _)) ++ kinds.map(relative(b.least, barsB, language, _)) ++
      Array(math.log1p(a.news._1.toDouble), math.log1p(a.news._2.toDouble),
        if b.result.nonEmpty && answer(b) == answer(a) then 1.0 else 0.0, if russian then 1.0 else 0.0)

  def parse(text: String): Either[String, Gate] =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).map(l => l.takeWhile(_ != ' ') -> l.dropWhile(_ != ' ').trim)
      .toMap
    for
      w <- lines.get("weights").toRight("no weights line").map(_.split("\\s+").toVector.map(_.toDouble))
      t <- lines.get("threshold").toRight("no threshold line").map(_.toDouble)
    yield Gate(w, t)
  def write(g: Gate): String = s"weights ${g.weights.mkString(" ")}\nthreshold ${g.threshold}\n"
