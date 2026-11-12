package okay.desk.statement

/**
 * A STATEMENT AS MARKED SENTENCES (specs/formalization.md §15): a stated text taken apart sentence by sentence, each
 * sentence its words with the pieces the statement copies marked by hand's notation (`Writing.noted`: `[key|words]`,
 * `[!key|words]` denied, `[#|3]` a number) and the frames it states over those marks. This is the form a TEACHER is
 * given and gives back: a sentence said another way, in another language, keeps its marks' keys, and the frames are
 * the same frames over them — so a rewrite has its gold statement with no reader and no offset counted.
 *
 * The law: `stated(of(s))` is `s` — the same text, the same statement.
 */
object Marked:
  /** a sentence: its words, marked, with what follows it up to the next sentence; and its frames over its marks */
  final case class Sentence(words: String, frames: Vector[Writing.Said])

  private def noted(key: String, words: String): String = s"[$key|$words]"

  def of(s: Writing.Stated): Vector[Sentence] =
    val text = s.text
    val keyOf: Map[String, String] = s.keys.toMap
    val spans = s.statement.sentences.map(_.at)
    s.statement.sentences.zipWithIndex.map { (sentence, i) =>
      val from = if i == 0 then 0 else sentence.at.from
      val until = spans.lift(i + 1).fold(text.length)(_.from)
      // every piece a frame of this sentence copies, in the order of the text
      val pieces = sentence.frames.flatMap(_.args).collect {
        case m: Arg.Mention => m.at -> noted((if m.denied then "!" else "") + keyOf(m.of.in(text)), m.at.in(text))
        case Arg.Number(at) => at -> noted("#", at.in(text)) }.distinctBy(_._1).sortBy(_._1.from)
      val at = pieces.map(_._1).zipWithIndex.toMap
      val words = StringBuilder()
      var i0 = from
      for (span, marked) <- pieces do
        words ++= text.substring(i0, span.from) ++= marked
        i0 = span.until
      words ++= text.substring(i0, until)
      val frames = sentence.frames.map(f => Writing.Said(f.con, 0,
        f.args.collect { case Arg.Pick(k) => k }, f.args.flatMap(_.span).map(at)))
      Sentence(words.toString, frames) }

  def stated(problem: Problem, sentences: Vector[Sentence]): Writing.Stated =
    Writing.write(problem, sentences.map(x => Writing.Part(Writing.noted(x.words), x.frames)))

  /** the keys a sentence marks, in its order — what a rewrite must keep */
  def keys(words: String): Vector[String] =
    "\\[(!?)([^\\]|]*)\\|([^\\]]*)\\]".r.findAllMatchIn(words).map(m =>
      m.group(1) + (if m.group(2) == "#" then "#" + m.group(3).trim else m.group(2))).toVector
