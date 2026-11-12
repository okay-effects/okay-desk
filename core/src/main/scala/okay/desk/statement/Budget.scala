package okay.desk.statement

/**
 * THE DECISIONS A STATEMENT TAKES (specs/formalization.md §3, the measure of logic.md §5.5), counted without any
 * model: what a reader must decide to write it, and what is decided for it. A decision is the kind of problem; a
 * sentence's next constructor or "nothing more"; a piece to copy (and the end of a list); whether a name is of
 * something new or of which thing named before; whether a claim is said or denied; which attribute is asked for.
 * FREE are the choices that are not a model's: a declared name is new by its role, a name written exactly as words
 * that named a thing before is of that thing (the data's own answer), and a choice among one is no choice.
 */
final case class Budget(kind: Int, frames: Int, pieces: Int, links: Int, signs: Int, picks: Int, free: Int):
  def decisions: Int = kind + frames + pieces + links + signs + picks
  def +(o: Budget): Budget = Budget(kind + o.kind, frames + o.frames, pieces + o.pieces, links + o.links,
    signs + o.signs, picks + o.picks, free + o.free)

object Budget:
  val none: Budget = Budget(0, 0, 0, 0, 0, 0, 0)

  /** the thing the data itself links these words to: the one the same words named before them */
  def linked(s: Statement, text: String, at: Span): Option[Span] =
    s.mentions.find(m => m.at.until <= at.from && Statement.same(m.at.in(text), at.in(text))).map(_.of)

  def of(s: Statement, text: String): Budget =
    val attributes = s.frames.count(_.con == Con.Attribute)
    val args = for
      f <- s.frames
      roles = f.con.roles ++ Vector.fill(f.args.size - f.con.roles.size)(f.con.roles.lastOption.getOrElse(Role.Thing))
      (role, a) <- roles.zip(f.args)
    yield (role, a)
    // what a piece names is decided once, however many frames copy the piece
    val again = args.map(_._2.span).zipWithIndex.map((at, i) => at.nonEmpty && args.take(i).exists(_._2.span == at))
    val perFrame = args.zip(again).map {
      case ((role @ (Role.Thing | Role.Claim), Arg.Mention(at, _, _)), copied) =>
        val open = !copied && s.things.exists(_.until <= at.from) && linked(s, text, at).isEmpty
        val settled = !copied && !open // by its role or by the data: no decision of a model
        none.copy(pieces = 1, links = if open then 1 else 0, signs = if role == Role.Claim then 1 else 0,
          free = if settled then 1 else 0)
      case ((_, Arg.Pick(_)), _) => if attributes > 1 then none.copy(picks = 1) else none.copy(free = 1)
      case ((Role.Fresh, _), _) => none.copy(pieces = 1, free = 1)
      case _ => none.copy(pieces = 1)
    }
    val (stops, ends) = (s.sentences.map(_.frames.size + 1).sum, s.frames.count(_.con.many))
    perFrame.foldLeft(none.copy(kind = 1, frames = stops, pieces = ends))(_ + _)
