package okay.desk.statement

/**
 * ONE DECISION WRONG (specs/formalization.md §7 step 5), with no model anywhere: the statements a reader writes by
 * getting exactly one decision of a right statement wrong, and what the exact machines make of each of them. A wrong
 * statement is REFUSED when it is not a statement of the text or no program of a solver; SIGNALLED when the solver
 * finds none or several answers where the task has one — the statement says too much or too little, and the solver
 * says so; HARMLESS when the answer is the task's all the same; SILENT when it is another answer with nothing to
 * tell it from a right one. Only the silent ones are wrong answers a page could show; the rest is what the exact
 * side of the system gives back to the reading — which is why a statement, not an answer, is what a model writes.
 */
object Faults:
  enum Kind:
    /** another constructor of the same pieces: "before" for "after", "if" for "exactly when" */
    case Constructor
    /** the pieces of a directed constructor the wrong way round */
    case Order
    /** a name taken for another thing named before, or for a new one */
    case Link
    /** a claim said where it was denied, denied where it was said */
    case Sign
    /** another attribute asked for */
    case Pick
    /** "nothing more" said too early: a frame not written */
    case Dropped

  enum Verdict:
    case Refused, Signalled, Harmless, Silent

  private val directed =
    Set(Con.Before, Con.After, Con.JustBefore, Con.JustAfter, Con.Either, Con.If, Con.Edge, Con.Path)

  /** every statement one decision away from `s`, with the decision that went wrong */
  def of(s: Statement): Vector[(Kind, Statement)] =
    val attributes = s.frames.count(_.con == Con.Attribute)
    // a first naming that later names point at stays: taking it away is more than one decision
    val pointed = s.mentions.filter(m => m.at != m.of).map(_.of).toSet
    def put(i: Int, j: Int, f: Option[Frame]): Statement =
      val at = s.sentences(i)
      val frames = f.fold(at.frames.patch(j, Nil, 1))(at.frames.updated(j, _))
      s.copy(sentences = s.sentences.updated(i, at.copy(frames = frames)))
    for
      (sentence, i) <- s.sentences.zipWithIndex
      (f, j) <- sentence.frames.zipWithIndex
      roles = f.con.roles ++ Vector.fill(f.args.size - f.con.roles.size)(f.con.roles.lastOption.getOrElse(Role.Thing))
      changed =
        Con.of(s.problem).filter(c => c != f.con && c.roles == f.con.roles && c.many == f.con.many)
          .map(c => Kind.Constructor -> f.copy(con = c)) ++
        Option.when(directed(f.con))(Kind.Order -> f.copy(args = f.args.updated(0, f.args(1)).updated(1, f.args(0)))) ++
        roles.zip(f.args).zipWithIndex.flatMap {
          case ((role @ (Role.Thing | Role.Claim), m @ Arg.Mention(at, of, denied)), k) =>
            val others = s.things.filter(t => t.until <= at.from && t != of) ++ Option.when(at != of)(at)
            val links = if at == of && pointed(at) then Vector.empty else others
            links.map(t => Kind.Link -> f.copy(args = f.args.updated(k, m.copy(of = t)))) ++
              Option.when(role == Role.Claim)(Kind.Sign -> f.copy(args = f.args.updated(k, m.copy(denied = !denied))))
          case ((_, Arg.Pick(p)), k) =>
            (0 until attributes).filter(_ != p).map(q => Kind.Pick -> f.copy(args = f.args.updated(k, Arg.Pick(q))))
          case _ => Vector.empty
        }
      (kind, wrong) <- changed.map((kind, g) => kind -> Option(g)) :+ (Kind.Dropped -> None)
    yield kind -> put(i, j, wrong)

  /** an answer that is none, several, or one of a few: the solver's own word that the statement does not decide */
  def undecided(result: Vector[String]): Boolean =
    result.exists(r => r == "none" || r == "several" || r.contains('|'))

  /** the result of a statement on the solver its shape chooses, the answers of a Datalog goal in order */
  def result(s: Statement, text: String): Either[String, Vector[String]] =
    s.check(text).flatMap(_ => Compile.of(s, text)).flatMap(_.run(text)).map(_.sorted)

  /** what the exact machines make of the statement `s` of a text whose right statement gives `right` */
  def verdict(s: Statement, text: String, right: Vector[String]): Verdict = result(s, text) match
    case Left(_) => Verdict.Refused
    case Right(got) if got == right => Verdict.Harmless
    case Right(got) if undecided(got) && !undecided(right) => Verdict.Signalled
    case Right(_) => Verdict.Silent
