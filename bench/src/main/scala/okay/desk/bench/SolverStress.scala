package okay.desk.bench

import okay.desk.puzzle.{Constraint, Puzzle, Ref, Solver}
import okay.desk.tasks.{Gold, Tasks}

/**
 * THE GRID SOLVER UNDER WRONG READINGS: every grid of the collection with one clue read wrong — dropped, another
 * form on the same two values, another value in it — whole, and beside each single other clue (the nearly free
 * puzzles, where a search that places unrelated values first finds one clash again and again). The page solves what
 * a model read, so no such puzzle may take long: the slowest are printed, and a puzzle over `limit` stops the run.
 */
object SolverStress:
  private val limit = 20000L

  private def forms(a: Ref, b: Ref): Vector[Constraint] = Vector(Constraint.Same(a, b), Constraint.Differ(a, b),
    Constraint.Left(a, b), Constraint.Left(b, a), Constraint.RightNext(a, b), Constraint.RightNext(b, a),
    Constraint.Adjacent(a, b))

  private def renamed(c: Constraint, from: Ref, to: Ref): Constraint =
    def r(x: Ref) = if x == from then to else x
    c match
      case Constraint.Same(a, b) => Constraint.Same(r(a), r(b))
      case Constraint.Differ(a, b) => Constraint.Differ(r(a), r(b))
      case Constraint.At(a, n) => Constraint.At(r(a), n)
      case Constraint.Middle(a) => Constraint.Middle(r(a))
      case Constraint.Left(a, b) => Constraint.Left(r(a), r(b))
      case Constraint.RightNext(a, b) => Constraint.RightNext(r(a), r(b))
      case Constraint.Adjacent(a, b) => Constraint.Adjacent(r(a), r(b))
      case Constraint.NotAt(a, n) => Constraint.NotAt(r(a), n)
      case Constraint.Apart(a, b, d) => Constraint.Apart(r(a), r(b), d)

  /** a clue read wrong: every other form on its two values, every other value in each of its places */
  def wrong(p: Puzzle, c: Constraint): Vector[Constraint] =
    val other = c.refs match
      case Vector(a, b) => forms(a, b)
      case Vector(a) => (1 to p.positions).toVector.map(Constraint.At(a, _)) :+ Constraint.Middle(a)
      case _ => Vector.empty
    val moved = for from <- c.refs.distinct; to <- p.refs if to != from yield renamed(c, from, to)
    (other ++ moved).distinct.filter(_ != c)

  def puzzles(p: Puzzle): Iterator[Puzzle] =
    val whole = for (c, i) <- p.constraints.iterator.zipWithIndex
      w <- wrong(p, c).iterator.map(Option(_)) ++ Iterator(None)
      yield p.copy(constraints = w.fold(p.constraints.patch(i, Nil, 1))(p.constraints.updated(i, _)))
    val beside = for (c, i) <- p.constraints.iterator.zipWithIndex; w <- wrong(p, c).iterator;
      (o, j) <- p.constraints.iterator.zipWithIndex if j != i yield p.copy(constraints = Vector(w, o))
    whole ++ beside

  @main def SolverStressMain(): Unit =
    val grids = Tasks.entries.map(_.task.gold).collect { case Gold.Grid(p) => p }.distinct
    @volatile var now: Option[(Puzzle, Long)] = None
    val watch = new Thread(() =>
      while true do
        Thread.sleep(1000)
        now.foreach { (p, since) =>
          if System.currentTimeMillis - since > limit then
            println(s"STUCK over $limit ms: ${p.positions} positions, ${p.sorts.size} sorts, ${p.constraints}")
            Runtime.getRuntime.halt(1)
        })
    watch.setDaemon(true); watch.start()
    var count = 0; var none = 0; var total = 0L
    var slowest = Vector.empty[(Long, Puzzle)]
    for g <- grids; p <- puzzles(g) do
      now = Some(p -> System.currentTimeMillis)
      val t = System.nanoTime
      val out = Solver.solve(p)
      val took = (System.nanoTime - t) / 1000
      now = None
      count += 1; total += took
      if out.isInstanceOf[Solver.Outcome.None] then none += 1
      slowest = (slowest :+ (took, p)).sortBy(-_._1).take(5)
      if took > 200000 then
        println(s"SLOW ${took / 1000} ms ${out.getClass.getSimpleName}: ${p.constraints}")
      if count % 20000 == 0 then println(s"$count puzzles, ${total / 1000} ms, slowest ${slowest.head._1 / 1000} ms")
    println(s"${grids.size} grids, $count wrong readings, $none inconsistent, ${total / 1000} ms in all")
    for (took, p) <- slowest do
      println(s"${took / 1000} ms: ${p.positions} positions, ${p.constraints.size} clues: ${p.constraints.take(3)}")
