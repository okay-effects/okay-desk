package okay.desk.statement

/**
 * A TEXT WRITTEN TOGETHER WITH ITS STATEMENT (specs/formalization.md §5): whoever writes a task knows what each
 * piece of it means, so the pieces a statement copies are MARKED as the text is written — `mark(key, words)`: these
 * words name the thing `key` — and every part of the text says the frames it states. `write` takes the marks out and
 * gives the plain text with its statement: the first marked piece of a key names the thing, the later ones name it
 * again; a frame's arguments are its part's marks in the order of the text. This is how the collection gets a gold
 * statement for every task in both languages without a single hand-counted offset.
 */
object Writing:
  private val (open, mid, close) = ('', '', '')
  private val numeral = "#"
  private val denial = "\uE003"

  /** `words`, naming the thing `key` */
  def mark(key: String, words: String): String = s"$open$key$mid$words$close"
  /** `words`, saying that the claim `key` is not so */
  def deny(key: String, words: String): String = mark(denial + key, words)
  /**
   * a text marked by hand: `[key|words]` are words naming `key`, `[words]` words that are their own key, and
   * `[!key|words]`, `[!words]` the same denied
   */
  def noted(words: String): String = "\\[(!?)([^\\]|]*)(?:\\|([^\\]]*))?\\]".r.replaceAllIn(words, m =>
    val (key, said) = (m.group(2), Option(m.group(3)).getOrElse(m.group(2)))
    java.util.regex.Matcher.quoteReplacement(if m.group(1).isEmpty then mark(key, said) else deny(key, said)))
  /** a number the statement copies */
  def number(n: Int): String = mark(numeral, n.toString)
  /**
   * `form` with the `name` inside it marked for `key`, as whole words: «врачом» whole, not the «врач» in it — a
   * reader sees words; the whole form when the name is not in it as written
   */
  def within(key: String, form: String, name: String): String =
    val i = form.toLowerCase.indexOf(name.toLowerCase)
    if i < 0 then mark(key, form)
    else
      val from = form.lastIndexWhere(!_.isLetterOrDigit, i) + 1
      val until = form.indexWhere(!_.isLetterOrDigit, i + name.length) match
        case -1 => form.length
        case e => e
      form.take(from) + mark(key, form.substring(from, until)) + form.drop(until)
  /** the first letter of marked words as a capital */
  def capital(words: String): String =
    val i = if words.headOption.contains(open) then words.indexOf(mid) + 1 else 0
    words.take(i) + words.slice(i, i + 1).toUpperCase + words.drop(i + 1)
  def plain(words: String): String = strip(words)._1

  /**
   * a frame as its writer says it: `pieces` of the part's next marks are its arguments, after the `picks` — or the
   * part's marks of these numbers (`marks`), for a piece that several frames copy
   */
  final case class Said(con: Con, pieces: Int, picks: Vector[Int] = Vector.empty, marks: Vector[Int] = Vector.empty)
  def say(con: Con, marks: Int*): Said =
    if marks.isEmpty then Said(con, con.roles.count(_ != Role.Attribute)) else Said(con, 0, marks = marks.toVector)
  def list(con: Con, pieces: Int): Said = Said(con, pieces)
  def ask(attribute: Int): Said = Said(Con.Ask, 1, Vector(attribute))

  /** a part of the text: its words, marked, and the frames it states */
  final case class Part(words: String, frames: Vector[Said] = Vector.empty)

  /** the text, its statement, and the key of every thing by the words that name it first */
  final case class Stated(text: String, statement: Statement, keys: Vector[(String, String)])

  private def strip(marked: String): (String, Vector[(String, Span)]) =
    val text = StringBuilder()
    val marks = Vector.newBuilder[(String, Span)]
    var i = 0
    while i < marked.length do
      if marked(i) == open then
        val m = marked.indexOf(mid, i)
        val e = marked.indexOf(close, m)
        marks += marked.substring(i + 1, m) -> Span(text.length, text.length + e - m - 1)
        text ++= marked.substring(m + 1, e)
        i = e + 1
      else
        text += marked(i)
        i += 1
    (text.toString, marks.result())

  def write(problem: Problem, parts: Vector[Part]): Stated =
    // every part's plain words, where they start, and its marks in the whole text
    val (text, placed) = parts.foldLeft(("", Vector.empty[(Part, Int, Vector[(String, Span)])])) {
      case ((sofar, done), part) =>
        val (words, marks) = strip(part.words)
        val shifted = marks.map((k, s) => k -> Span(s.from + sofar.length, s.until + sofar.length))
        (sofar + words, done :+ (part, sofar.length, shifted))
    }
    val named = placed.flatMap(_._3).filter(_._1 != numeral).map((k, s) => k.stripPrefix(denial) -> s)
    val first: Map[String, Span] = named.reverse.toMap
    val sentences = Sentences.of(text)
    val frames = placed.flatMap { (part, start, marks) =>
      val begins = (start until text.length).find(i => !text(i).isWhitespace).getOrElse(start)
      part.frames.foldLeft((Vector.empty[(Int, Frame)], marks)) { case ((out, left), said) =>
        val (next, rest) = left.splitAt(said.pieces)
        require(next.size == said.pieces && said.marks.forall(marks.indices.contains),
          s"${said.con}: the marked pieces of «${part.words}» are not enough")
        val mine = next ++ said.marks.map(marks)
        val args = said.picks.map(Arg.Pick(_)) ++ mine.map((key, span) =>
          if key == numeral then Arg.Number(span)
          else Arg.Mention(span, first(key.stripPrefix(denial)), key.startsWith(denial)))
        (out :+ (mine.headOption.fold(begins)(_._2.from), Frame(said.con, args)), rest)
      }._1
    }
    val statement = Statement(problem, sentences.map(s =>
      Sentence(s, frames.collect { case (at, f) if s.from <= at && at < s.until => f })))
    Stated(text, statement, named.map(_._1).distinct.map(k => first(k).in(text) -> k))
