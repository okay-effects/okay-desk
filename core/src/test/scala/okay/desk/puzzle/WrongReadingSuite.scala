package okay.desk.puzzle

/**
 * A PUZZLE READ WRONG IS ANSWERED AT ONCE. The page solves what a model read, so a clash the arc rules cannot see
 * before a value is fixed must not be searched for at every leaf: two values of one sort "on one position", a value
 * "next to" the one it is "with". Each of these took minutes before `Solver.impossible`; the whole family — every
 * grid of the collection with one clue read wrong, 462,685 puzzles — is `bench/runMain …SolverStressMain`.
 */
class WrongReadingSuite extends munit.FunSuite:
  private val p = Puzzle.classic
  private def r(s: String, v: String) = Ref(s, v)
  private def quick[A](body: => A): A =
    val t = System.nanoTime
    val out = body
    assert((System.nanoTime - t) / 1000000 < 5000, "the solver took seconds")
    out

  test("two values of one sort on one position: none, and the clue named"):
    val colours = Constraint.Same(r("colour", "green"), r("colour", "red"))
    val wrong = p.copy(constraints = p.constraints.updated(0, colours))
    assertEquals(quick(Solver.solve(wrong)), Solver.Outcome.None(Vector(1)))
    // through another value: the Englishman is with red, and with green
    val green = Constraint.Same(r("nation", "Englishman"), r("colour", "green"))
    val through = p.copy(constraints = p.constraints :+ green)
    assertEquals(quick(Solver.solve(through)), Solver.Outcome.None(Vector(1, 15)))

  test("with it and next to it: none, both clues named, in a full puzzle and in one of two clues"):
    val with_ = Constraint.Same(r("nation", "Norwegian"), r("colour", "blue"))
    assertEquals(quick(Solver.solve(p.copy(constraints = p.constraints :+ with_))), Solver.Outcome.None(Vector(14, 15)))
    val two = p.copy(constraints = Vector(with_, Constraint.Adjacent(r("nation", "Norwegian"), r("colour", "blue"))))
    assertEquals(quick(Solver.solve(two)), Solver.Outcome.None(Vector(1, 2)))

  test("a value against itself: none"):
    val fox = r("pet", "fox")
    for c <- Vector(Constraint.Adjacent(fox, fox), Constraint.Left(fox, fox), Constraint.Differ(fox, fox)) do
      assertEquals(quick(Solver.solve(p.copy(constraints = Vector(c)))), Solver.Outcome.None(Vector(1)))

  test("the puzzle itself is read as before: one solution, the Japanese owns the zebra"):
    quick(Solver.solve(p)) match
      case Solver.Outcome.One(g, _) => assertEquals(Solver.answer(p, g).map(_._2), Some("Japanese"))
      case other => fail(other.toString)

  test("a few clues: the free values are placed last, several solutions at once"):
    val few = p.copy(constraints = p.constraints.take(2))
    assert(quick(Solver.solve(few)).isInstanceOf[Solver.Outcome.Several])
