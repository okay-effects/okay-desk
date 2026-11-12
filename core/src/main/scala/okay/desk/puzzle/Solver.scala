package okay.desk.puzzle

/**
 * THE ENGINE (specs/puzzles.md §3): a position per (sort, value), domains as sets of positions, propagation —
 * all-different per sort and each constraint's arc rule — then search on the smallest domain, backtracking. Every
 * removal is recorded with its cause: the TRACE is the proof. Outcomes: one solution, several (the values that
 * differ), none (the clues that clash, found by leaving one out at a time). Exact; nothing else decides.
 */
object Solver:
  /** a step of the trace: what was decided and by which clue (1-based; 0 = the rule "one value per position") */
  final case class Step(ref: Ref, position: Int, clue: Int, how: String)
  type Grid = Map[Ref, Int]

  enum Outcome:
    case One(grid: Grid, trace: Vector[Step])
    case Several(count: Int, grids: Vector[Grid], differing: Vector[Ref])
    case None(clashing: Vector[Int])

  private type Doms = Map[Ref, Set[Int]]

  /** the domains after propagation to a fixpoint, or None on a wipe-out; the steps appended as values get fixed */
  private def propagate(p: Puzzle, d0: Doms, trace: scala.collection.mutable.ArrayBuffer[Step], fixed: scala.collection.mutable.Set[Ref]): Option[Doms] =
    var d = d0
    var changed = true
    var dead = false
    def shrink(r: Ref, to: Set[Int], clue: Int, how: => String): Boolean =
      val now = d(r)
      val next = now.intersect(to)
      if next.size < now.size then
        d = d.updated(r, next); changed = true
        if next.size == 1 && !fixed(r) then { fixed += r; trace += Step(r, next.head, clue, how) }
      next.nonEmpty
    while changed && !dead do
      changed = false
      // the constraints, each an arc rule
      for (c, i) <- p.constraints.zipWithIndex if !dead do
        val n = i + 1
        val ok = c match
          case Constraint.Same(a, b) =>
            val both = d(a).intersect(d(b))
            shrink(a, both, n, s"${a.show} is with ${b.show}") && shrink(b, both, n, s"${b.show} is with ${a.show}")
          case Constraint.Differ(a, b) =>
            (if d(b).size == 1 then shrink(a, d(a) -- d(b), n, s"${a.show} is not with ${b.show}") else true) &&
              (if d(a).size == 1 then shrink(b, d(b) -- d(a), n, s"${b.show} is not with ${a.show}") else true)
          case Constraint.At(a, k) => shrink(a, Set(k), n, s"${a.show} is at $k")
          case Constraint.Middle(a) => shrink(a, Set((p.positions + 1) / 2), n, s"${a.show} is in the middle")
          case Constraint.Left(a, b) =>
            val maxB = d(b).max; val minA = d(a).min
            shrink(a, d(a).filter(_ < maxB), n, s"${a.show} is left of ${b.show}") && shrink(b, d(b).filter(_ > minA), n, s"${b.show} is right of ${a.show}")
          case Constraint.RightNext(a, b) =>
            shrink(a, d(b).map(_ + 1), n, s"${a.show} is right next to ${b.show}") && shrink(b, d(a).map(_ - 1), n, s"${b.show} is left next to ${a.show}")
          case Constraint.Adjacent(a, b) =>
            shrink(a, d(b).flatMap(x => Set(x - 1, x + 1)), n, s"${a.show} is next to ${b.show}") &&
              shrink(b, d(a).flatMap(x => Set(x - 1, x + 1)), n, s"${b.show} is next to ${a.show}")
          case Constraint.NotAt(a, k) => shrink(a, d(a) - k, n, s"${a.show} is not at $k")
          case Constraint.Apart(a, b, g) =>
            shrink(a, d(b).flatMap(x => Set(x - g, x + g)), n, s"${a.show} is $g from ${b.show}") &&
              shrink(b, d(a).flatMap(x => Set(x - g, x + g)), n, s"${b.show} is $g from ${a.show}")
        if !ok then dead = true
      // one value per position within a sort: a fixed value excludes its position from the others; a position only one
      // value can take is that value's
      for s <- p.sorts if !dead do
        val rs = s.values.map(v => Ref(s.name, v))
        for r <- rs if !dead && d(r).size == 1; o <- rs if !dead && o != r do
          if !shrink(o, d(o) - d(r).head, 0, s"${d(r).head} is taken by ${r.show}") then dead = true
        for pos <- 1 to p.positions if !dead do
          val can = rs.filter(r => d(r).contains(pos))
          if can.isEmpty then dead = true
          else if can.size == 1 && d(can.head).size > 1 then
            if !shrink(can.head, Set(pos), 0, s"only ${can.head.show} can be at $pos") then dead = true
    if dead then None else Some(d)

  /**
   * What the arc rules cannot see before a value is fixed. The clues "a is with b" make classes of values on one
   * position; a class never holds two values of one sort, nor two values another clue sets apart. Seen here once,
   * such a clash is not met again at every leaf of a search.
   */
  private def impossible(p: Puzzle): Boolean =
    val classes = p.constraints.collect { case Constraint.Same(a, b) => (a, b) }
      .foldLeft(Vector.empty[Set[Ref]]) { case (sofar, (a, b)) =>
        val (joined, rest) = sofar.partition(c => c(a) || c(b))
        rest :+ (joined.flatten.toSet + a + b)
      }
    def one(a: Ref, b: Ref): Boolean = a == b || classes.exists(c => c(a) && c(b))
    classes.exists(c => c.groupBy(_.sort).exists(_._2.size > 1)) || p.constraints.exists {
      case Constraint.Differ(a, b) => one(a, b)
      case Constraint.Left(a, b) => one(a, b)
      case Constraint.RightNext(a, b) => one(a, b)
      case Constraint.Adjacent(a, b) => one(a, b)
      case Constraint.Apart(a, b, _) => one(a, b)
      case _ => false
    }

  /** every solution (up to `bound`), each with the trace of the first propagation and the search's decisions */
  private def search(p: Puzzle, bound: Int): (Vector[(Grid, Vector[Step])], Vector[Step]) =
    val trace0 = scala.collection.mutable.ArrayBuffer.empty[Step]
    val all = (1 to p.positions).toSet
    val start: Doms = p.refs.map(r => r -> all).toMap
    val found = Vector.newBuilder[(Grid, Vector[Step])]
    // A value no clue speaks of is supposed last: with the others placed it always has a place, so a clash among the
    // clues is met once — not again under every arrangement of the values that have nothing to do with it.
    val spoken = p.constraints.flatMap(_.refs).toSet
    val order = p.refs.zipWithIndex.toMap
    var count = 0
    def go(d0: Doms, trace: Vector[Step]): Unit =
      if count >= bound then return
      val buf = scala.collection.mutable.ArrayBuffer.from(trace)
      val fixed = scala.collection.mutable.Set.from(d0.collect { case (r, s) if s.size == 1 => r })
      propagate(p, d0, buf, fixed) match
        case None => ()
        case Some(d) =>
          val open = d.filter(_._2.size > 1)
          open.headOption.map(_ => open.minBy((r, dom) => (!spoken(r), dom.size, order(r)))) match
            case None =>
              count += 1
              found += ((d.view.mapValues(_.head).toMap, buf.toVector))
            case Some((r, dom)) =>
              for pos <- dom.toVector.sorted if count < bound do
                go(d.updated(r, Set(pos)), buf.toVector :+ Step(r, pos, -1, s"suppose ${r.show} is at $pos"))
    if !impossible(p) then go(start, Vector.empty)
    val _ = trace0
    (found.result(), Vector.empty)

  /** the solutions alone, up to `bound`: how many there are, with no clash sought for a puzzle that has none */
  def solutions(p: Puzzle, bound: Int = 2): Vector[Grid] = search(p, bound)._1.map(_._1)

  def solve(p: Puzzle, bound: Int = 10): Outcome =
    require(bound >= 2, "at least two solutions must be sought to establish uniqueness")
    val (solutions, _) = search(p, bound)
    solutions match
      case Vector((g, t)) => Outcome.One(g, t)
      case Vector() =>
        // Deletion-minimal, with original clue numbers, even when there are independent contradictions.
        var core = p.constraints.zipWithIndex
        for i <- p.constraints.indices do
          val without = core.filterNot(_._2 == i)
          if search(p.copy(constraints = without.map(_._1)), 1)._1.isEmpty then core = without
        Outcome.None(core.map(_._2 + 1))
      case many =>
        val grids = many.map(_._1)
        val differing = p.refs.filter(r => grids.map(_(r)).distinct.size > 1)
        Outcome.Several(many.size, grids, differing)

  /** the answer to the question on a grid: the value of the asked sort at the position of `of` */
  def answer(p: Puzzle, g: Grid): Option[(Question, String)] =
    p.question.flatMap(q => g.get(q.of).flatMap(pos => p.sort(q.asked).flatMap(_.values.find(v => g(Ref(q.asked, v)) == pos)).map(v => (q, v))))

  /** Every requested binding is read from the same computed grid. */
  def answers(p: Puzzle, g: Grid): Vector[(Question, String)] = p.questions.flatMap { q =>
    answer(p.copy(question = Some(q), additionalQuestions = Vector.empty), g)
  }

  /** The grid as rows by position: position, then each sort's value. */
  def table(p: Puzzle, g: Grid): Vector[Vector[String]] =
    (1 to p.positions).toVector.map(pos => pos.toString +: p.sorts.map(s => s.values.find(v => g(Ref(s.name, v)) == pos).getOrElse("?")))
