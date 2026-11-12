package okay.desk.statement

/**
 * A STATEMENT ASSEMBLED FROM A READER'S DECISIONS (specs/formalization.md §4, the vector tier): what a weak model
 * decides over the tokens of a text, and how the decisions become a statement with nothing else added. The model
 * runs over the text once; the decisions are read off its states:
 *
 *  - a TAG of every token: outside a piece, the beginning of a piece, inside one, or the beginning of a piece that
 *    opens the next frame's own pieces (the end of a list is where the next one begins);
 *  - the FRAMES of every sentence: its next constructor, or "nothing more";
 *  - the LINK of a name: the thing named before that it names again, or a new thing — asked only when the data does
 *    not answer (the same words named a thing before);
 *  - the PICK of a question: which of the declared attributes it asks for.
 *
 * The frames take the sentence's pieces in the order of the text: a constructor with fixed roles takes as many as it
 * has roles, a list takes pieces up to the next opening. So a statement whose frames copy their pieces in the text's
 * order — every statement of the drawn corpus — is assembled exactly from its own decisions (`decisions`, the law of
 * `AssembleSuite`); one that copies a piece twice or out of order is not in this reader's reach yet, and says so.
 */
object Assemble:
  enum Tag:
    case Outside, Begin, Inside, Open

  /** what a reader is asked while a statement is assembled; the answers of a right statement are `decisions` */
  trait Choices:
    /** the constructor of the sentence's frame `k`, or none: nothing more is stated */
    def frame(sentence: Int, k: Int): Option[Con]
    /** the thing the words `at` name again, of those named before — or none: a new thing */
    def link(at: Span, earlier: Vector[Span]): Option[Span]
    /** the attribute a question of the sentence asks for, of those declared so far (each from its first piece to
     * its last) that the things the question names are not values of */
    def pick(sentence: Int, attributes: Vector[Span]): Int

  /** the letters and digits of a token: a token is often a word with the space before it, or with a stop after */
  def core(text: String, t: Span): Span =
    var (a, b) = (t.from, t.until)
    while a < b && !text(a).isLetterOrDigit do a += 1
    while b > a && !text(b - 1).isLetterOrDigit do b -= 1
    Span(a, b)

  /** the pieces the tags say: each with whether it opens the next frame's pieces */
  def pieces(text: String, tokens: Vector[Span], tags: Vector[Tag]): Vector[(Span, Boolean)] =
    val out = Vector.newBuilder[(Span, Boolean)]
    var open: Option[(Span, Boolean)] = None
    for (token, tag) <- tokens.zip(tags) do
      val c = core(text, token)
      (tag, open) match
        case (Tag.Inside, Some((s, o))) if c.from < c.until => open = Some((Span(s.from, c.until), o))
        case (Tag.Inside, _) => ()
        case (Tag.Begin | Tag.Open, _) if c.from < c.until =>
          out ++= open
          open = Some((c, tag == Tag.Open))
        case _ =>
          out ++= open
          open = None
    out ++= open
    // a piece is whole words: a word begun is a word taken ("Norweg" tagged, "ian" not, is "Norwegian")
    out.result().map((s, o) => (words(text, s), o)).foldLeft(Vector.empty[(Span, Boolean)]) { (acc, p) =>
      // two pieces of one word are that one word
      acc.lastOption match
        case Some((q, o)) if p._1.from < q.until => acc.init :+ (Span(q.from, math.max(q.until, p._1.until)), o)
        case _ => acc :+ p }

  /** a span widened to the whole words it touches */
  def words(text: String, s: Span): Span =
    var (a, b) = (s.from, s.until)
    while a > 0 && text(a - 1).isLetterOrDigit && text(a).isLetterOrDigit do a -= 1
    while b < text.length && text(b).isLetterOrDigit && b > 0 && text(b - 1).isLetterOrDigit do b += 1
    Span(a, b)

  /** whether a token goes on a word another token began: its tag cannot change a piece (`words`) */
  def within(text: String, t: Span): Boolean =
    val c = core(text, t)
    c.from > 0 && c.from == t.from && text(c.from - 1).isLetterOrDigit && c.from < c.until

  private val most = 8

  def assemble(problem: Problem, text: String, tokens: Vector[Span], tags: Vector[Tag], choices: Choices)
      : Either[String, Statement] =
    val all = pieces(text, tokens, tags)
    var things = Vector.empty[Span]
    var mentions = Vector.empty[Arg.Mention]
    var attributes = Vector.empty[Span]
    var stray = Option.empty[String]
    def mention(at: Span, fresh: Boolean): Arg.Mention =
      val known = mentions.find(m => Statement.same(m.at.in(text), at.in(text))).map(_.of)
      val of = if fresh then at else known.orElse(choices.link(at, things)).getOrElse(at)
      val m = new Arg.Mention(at, of)
      mentions :+= m
      if of == at && !things.contains(at) then things :+= at
      m
    val sentences = Sentences.of(text).zipWithIndex.map { (sentence, i) =>
      val read = Iterator.from(0).map(k => choices.frame(i, k)).take(most).takeWhile(_.nonEmpty).flatten.toVector
      var left = all.filter(_._1.within(sentence))
      // a sentence with nothing marked in it states nothing of things ("each house has a unique attribute for
      // each of the following characteristics"): its frames that take pieces are left out. Leaving a clue out never
      // makes a wrong answer — the right arrangement still fits; at worst no single one does, and that is asked
      val cons = if left.isEmpty then read.filter(_.roles.isEmpty) else read
      val frames = cons.map { con =>
        val roles = con.roles.filter(_ != Role.Attribute)
        val taken =
          if con.many then left.take(1) ++ left.drop(1).takeWhile(!_._2)
          else left.take(roles.size)
        left = left.drop(taken.size)
        // a list's item is what lies between its commas: pieces with only spaces between them are one item
        // ("google pixel 6" read as three pieces is one phone) — the punctuation of a list, not its words
        val mine = if con != Con.Attribute then taken else
          taken.foldLeft(Vector.empty[(Span, Boolean)]) { (acc, p) =>
            acc.lastOption match
              case Some((q, open)) if text.substring(q.until, p._1.from).forall(_.isWhitespace) =>
                acc.init :+ (Span(q.from, p._1.until), open)
              case _ => acc :+ p }
        // a number goes to the frame's number wherever it is said ("there is ONE house between A and B"); the
        // other pieces fill the other roles in the order of the text
        // (in the order of the text when the piece there is a number: a thing may be named by digits too)
        val pieces = mine.map(_._1)
        def numeric(p: Span): Boolean = Numbers.of(p.in(text)).nonEmpty
        val number = Option.when(roles.contains(Role.Number))(
          pieces.lift(roles.indexOf(Role.Number)).filter(numeric).orElse(pieces.find(numeric))).flatten
        val others = roles.filter(_ != Role.Number)
        val named = pieces.filterNot(number.contains).zipWithIndex.map { (at, j) =>
          mention(at, others.lift(j).getOrElse(others.lastOption.getOrElse(Role.Thing)) == Role.Fresh) }
          .patch(roles.indexOf(Role.Number) max 0, number.toVector.map(Arg.Number(_)), 0)
        // a question does not ask a thing for the attribute it is a value of: the data closes those
        val own = named.collect { case m: Arg.Mention => m.of }
        val open = Some(attributes.zipWithIndex.filterNot((a, _) => own.exists(_.within(a)))).filter(_.nonEmpty)
          .getOrElse(attributes.zipWithIndex)
        val picks = con.roles.filter(_ == Role.Attribute).map(_ =>
          Arg.Pick(open.lift(choices.pick(i, open.map(_._1))).fold(0)(_._2)))
        if con == Con.Attribute then attributes ++= mine.headOption.map(a => Span(a._1.from, mine.last._1.until))
        Frame(con, picks ++ named) }
      if left.nonEmpty && stray.isEmpty then
        stray = Some(s"pieces no frame takes in «${sentence.in(text)}»: ${left.map(_._1.in(text)).mkString(", ")}")
      Sentence(sentence, frames) }
    stray.toLeft(Statement(problem, sentences))

  /** the decisions of a right reading: the tags of the tokens and the answers a reader is asked for */
  final case class Decisions(tags: Vector[Tag], choices: Choices)

  /**
   * THE DECISIONS A STATEMENT IS ASSEMBLED FROM, or why it is not in reach: a piece copied twice or out of the
   * text's order, a piece that is not whole tokens, a sign (a denied claim) — the reader does not decide these yet
   */
  def decisions(s: Statement, text: String, tokens: Vector[Span]): Either[String, Decisions] =
    // a frame's pieces in the text's order: the assembler takes them so (a number goes to its role wherever it is)
    val placed = for sentence <- s.sentences; (f, j) <- sentence.frames.zipWithIndex
      at <- f.args.flatMap(_.span).sortBy(_.from) yield (at, sentence, j)
    val opens = placed.zipWithIndex.collect { case ((at, sentence, j), i)
      if i > 0 && placed(i - 1)._2 == sentence && placed(i - 1)._3 != j => at }.toSet
    val ordered = placed.zip(placed.drop(1)).forall((a, b) => a._1.until <= b._1.from)
    val tags = tokens.map { token =>
      val c = core(text, token)
      placed.map(_._1).find(p => c.from < c.until && c.from < p.until && p.from < c.until) match
        // a token of no letters inside a piece (the hyphen of «Нью-Йорк») goes on with it: `pieces` passes it by
        case None if c.from >= c.until && placed.exists(p => p._1.from < token.from && token.until < p._1.until) =>
          Tag.Inside
        case None => Tag.Outside
        case Some(p) if tokens.exists(t => t.until <= token.from && { val o = core(text, t)
          o.from < o.until && o.from < p.until && p.from < o.until }) => Tag.Inside
        case Some(p) => if opens(p) then Tag.Open else Tag.Begin }
    val listed = s.sentences.flatMap(_.frames).filter(_.con == Con.Attribute).map(_.args.flatMap(_.span))
      .collect { case spans if spans.nonEmpty => Span(spans.head.from, spans.last.until) }
    val choices = new Choices:
      def frame(sentence: Int, k: Int): Option[Con] = s.sentences(sentence).frames.lift(k).map(_.con)
      def link(at: Span, earlier: Vector[Span]): Option[Span] =
        s.mentions.find(_.at == at).map(_.of).filter(_ != at)
      def pick(sentence: Int, attributes: Vector[Span]): Int =
        val asked = s.sentences(sentence).frames.flatMap(_.args).collectFirst { case Arg.Pick(i) => i }.getOrElse(0)
        math.max(0, listed.lift(asked).fold(0)(attributes.indexOf))
    if !ordered then Left("a piece copied twice, or out of the text's order")
    else if s.mentions.exists(_.denied) then Left("a denied claim: the sign is not decided yet")
    else if s.sentences.exists(_.frames.flatMap(_.args).count(_.isInstanceOf[Arg.Pick]) > 1) then
      Left("two questions of attributes in a sentence")
    else assemble(s.problem, text, tokens, tags, choices) match
      case Right(`s`) => Right(Decisions(tags, choices))
      case Right(_) => Left("a piece that is not whole tokens")
      case Left(why) => Left(why)
