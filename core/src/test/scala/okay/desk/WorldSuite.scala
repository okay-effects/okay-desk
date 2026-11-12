package okay.desk

import okay.desk.puzzle.*

/** World snapshots are inputs, not model guesses. The compiled selector must equal both the interpreter and a
  * hand-computed answer; all uncertainty must keep the clue open.
  */
class WorldSuite extends munit.FunSuite:
  val facts = Vector(
    World.Fact("France", "capital", Vector("Paris"), "https://www.wikidata.org/wiki/Q142", "2026-11-08"),
    World.Fact("Germany", "capital", Vector("Berlin"), "https://www.wikidata.org/wiki/Q183", "2026-11-08")
  )
  val text = """Countries: France, Germany
    |Pets: dog, cat
    |France is in house 1.
    |The one whose countries/capital is Paris goes with dog.
    |Which country owns dog?
    |""".stripMargin
  def parsed(fs: Vector[World.Fact]) = PuzzleText.parse(text, PuzzleDomain.rules, fs)
  val need = World.Need("countries", "capital", "Paris", Ref("pets", "dog"))

  test("world facts close a clue, the exact engine solves, and sources survive the reading"):
    val p = parsed(facts)
    assert(p.ready, p.toString)
    assertEquals(p.clues(1).read, Some(Constraint.Same(Ref("countries", "France"), Ref("pets", "dog"))))
    assertEquals(p.clues(1).evidence, facts)
    Solver.solve(p.puzzle) match
      case Solver.Outcome.One(g, _) => assertEquals(Solver.answer(p.puzzle, g).map(_._2), Some("France"))
      case other                    => fail(other.toString)
    val ru = text.replace(
      "The one whose countries/capital is Paris goes with dog.",
      "Тот, у кого countries/capital — Paris, вместе с dog."
    )
    assertEquals(PuzzleText.parse(ru, PuzzleDomain.rules, facts).clues.map(_.read), p.clues.map(_.read))

  test("missing, conflicting, absent and ambiguous facts never become a constraint"):
    val p = parsed(Vector.empty)
    assert(!p.ready)
    assert(p.clues(1).problem.exists(_.contains("missing")))
    assert(!parsed(facts.take(1)).ready, "absence is not a negative fact")
    val conflict = facts :+ facts.head.copy(values = Vector("Lyon"))
    assert(parsed(conflict).clues(1).problem.exists(_.contains("disagree")))
    val both = facts.updated(1, facts(1).copy(values = Vector("Paris")))
    assert(parsed(both).clues(1).problem.exists(_.contains("ambiguous")))
    assert(World.resolve(need.copy(value = "Rome"), p.puzzle, facts).isLeft)

  test("compiled lookup equals interpreter and ordinary set membership on varied complete snapshots"):
    val rnd = new scala.util.Random(72)
    for _ <- 0 until 60 do
      val size = 2 + rnd.nextInt(5)
      val subjects = (1 to size).map(i => s"country$i").toVector
      val pets = "dog" +: (2 to size).map(i => s"pet$i").toVector
      val p = Puzzle(size, Vector(Sort("countries", subjects), Sort("pets", pets)), Vector.empty, None)
      val fs = subjects.map { s =>
        facts.head.copy(subject = s, values = Vector("a", "b", "c").filter(_ => rnd.nextBoolean()))
      }
      val n = need.copy(value = Vector("a", "b", "c", "missing")(rnd.nextInt(4)))
      val compiled = World.resolve(n, p, fs)
      assertEquals(compiled, World.resolve(n, p, fs, weights = false))
      val matching = fs.filter(_.values.contains(n.value)).map(_.subject)
      assertEquals(
        compiled.toOption.map(_.constraint),
        matching match
          case Vector(s) => Some(Constraint.Same(Ref("countries", s), n.withValue))
          case _         => None
      )

  test("fact files round-trip; malformed and unprovenanced imports are rejected atomically"):
    val json = World.json(facts)
    assertEquals(World.parse(json), Right(facts))
    for bad <- Vector(
        "{}",
        "[false]",
        "[{\"subject\":\"France\"}]",
        "not json",
        json.replace("https://", "http://"),
        json.replace("2026-11-08", "today"),
        World.json(Vector.fill(World.MaxFacts + 1)(facts.head))
      )
    do assert(World.parse(bad).isLeft, bad)

  test("unknown relations and invalid headers stay open instead of silently producing a smaller puzzle"):
    val unknown = text.replace("France is in house 1.", "France mysteriously influences cat.")
    assert(!PuzzleText.parse(unknown, PuzzleDomain.rules, facts).ready)
    val duplicate = text.replace("France, Germany", "France, France")
    assert(PuzzleText.parse(duplicate, PuzzleDomain.rules, facts).problems.nonEmpty)
    val question = text.replace("Which country owns dog?", "Who owns elephant?")
    assert(!PuzzleText.parse(question, PuzzleDomain.rules, facts).ready)

  test("a contradiction before an ordering clue returns a core; a one-solution search bound is rejected"):
    val p = parsed(facts).puzzle
    val france = Ref("countries", "France")
    val germany = Ref("countries", "Germany")
    val broken = p.copy(constraints = Vector(Constraint.At(france, 0), Constraint.Left(france, germany)))
    assertEquals(Solver.solve(broken), Solver.Outcome.None(Vector(1)))
    val independent = p.copy(constraints = Vector(Constraint.At(france, 0), Constraint.At(germany, 0)))
    assertEquals(Solver.solve(independent), Solver.Outcome.None(Vector(2)))
    intercept[IllegalArgumentException](Solver.solve(p, bound = 1))
