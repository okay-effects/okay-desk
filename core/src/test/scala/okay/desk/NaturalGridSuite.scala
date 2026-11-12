package okay.desk

import okay.desk.puzzle.*
import okay.desk.know.Knowledge
import okay.desk.exec.{Computation, Wire}

class NaturalGridSuite extends munit.FunSuite:
  val uniqueness = "Каждый из пяти домов окрашен в свой цвет, а их жители — разных национальностей, " +
    "владеют разными животными, пьют разные напитки и курят разные марки сигарет."
  val clues = PuzzleText.classicRu.linesIterator.filter(l => l.nonEmpty && !l.startsWith("#") && !l.contains(":"))
    .filterNot(_.trim.startsWith("Кто")).mkString("\n")
    .replace("У испанца собака.", "У испанца есть собака.")
    .replace("дом сразу справа", "дом стоит сразу справа")
    .replace("держит улиток", "разводит улиток")
    .replace("Kools", "Kool").replace("среднем доме", "центральном доме")
    .replace("Тот, кто курит Chesterfield, живёт рядом с тем, у кого лиса.",
      "Сосед того, кто курит Chesterfield, держит лису.")
  val text = "На улице стоят пять домов.\n" + clues + "\nКто пьёт воду? Кто держит зебру?\n" + uniqueness +
    " Ещё одно замечание: в утверждении 6 справа означает справа относительно вас."
  def parse(t: String) = PuzzleText.parse(t, PuzzleDomain.rules)

  val fishPreamble = "На одной улице подряд стоят пять домов, каждый — своего цвета. " +
    "В каждом живёт человек, все пять — разных национальностей. " +
    "Каждый человек предпочитает уникальную марку сигарет, напиток и домашнее животное. Кроме того:"
  val fishText = fishPreamble + "\n\n" + """Норвежец живёт в первом доме.
    |Англичанин живёт в красном доме.
    |Зелёный дом находится слева от белого, рядом с ним.
    |Датчанин пьёт чай.
    |Тот, кто курит Marlboro, живёт рядом с тем, кто выращивает кошек.
    |Тот, кто живёт в жёлтом доме, курит Dunhill.
    |Немец курит Rothmans.
    |Тот, кто живёт в центре, пьёт молоко.
    |Сосед того, кто курит Marlboro, пьёт воду.
    |Тот, кто курит Pall Mall, выращивает птиц.
    |Швед выращивает собак.
    |Норвежец живёт рядом с синим домом.
    |Тот, кто выращивает лошадей, живёт в синем доме.
    |Тот, кто курит Winfield, пьет пиво.
    |В зелёном доме пьют кофе.
    |Вопрос:
    |Кто разводит рыбок?""".stripMargin

  test("reported fish variant copies new brands, split uniqueness, plurals and immediate left"):
    val parsed = parse(fishText)
    assert(Knowledge.isConstraints(fishText))
    assert(parsed.ready, parsed.problems.mkString("; ") + parsed.clues.filter(_.read.isEmpty).toString)
    assertEquals(parsed.clues.size, 15)
    assertEquals(parsed.clues(2).read,
      Some(Constraint.RightNext(Ref("colours", "ivory"), Ref("colours", "green"))))
    assertEquals(parsed.puzzle.sort("smokes").get.values.toSet,
      Set("Marlboro", "Dunhill", "Rothmans", "Pall Mall", "Winfield"))
    Solver.solve(parsed.puzzle) match
      case Solver.Outcome.One(grid, _) =>
        assertEquals(Solver.answers(parsed.puzzle, grid).head._2, "German")
      case other => fail(other.toString)

  test("unseen cigarette names are copied and colour uniqueness alone cannot close other domains"):
    val renamed = parse(fishText.replace("Marlboro", "Atlas Seven").replace("Rothmans", "Nova"))
    assert(renamed.ready)
    assert(renamed.puzzle.sort("smokes").get.values.contains("Atlas Seven"))
    Solver.solve(renamed.puzzle) match
      case Solver.Outcome.One(grid, _) => assertEquals(Solver.answers(renamed.puzzle, grid).head._2, "German")
      case other => fail(other.toString)
    assert(!parse(fishText.replace("Каждый человек предпочитает уникальную марку сигарет, напиток и домашнее животное.",
      "Каждый человек предпочитает марку сигарет, напиток и домашнее животное.")).ready)

  test("natural categories, singular brands, middle and neighbour forms produce two computed answers"):
    assert(Knowledge.isConstraints(text))
    val parsed = parse(text)
    assert(parsed.ready, parsed.problems.mkString("; ") + parsed.clues.filter(_.read.isEmpty).toString)
    assert(parsed.inferred)
    assertEquals(parsed.puzzle.questions.map(_.of.value), Vector("water", "zebra"))
    assertEquals(parsed.clues.size, 14)
    Solver.solve(parsed.puzzle) match
      case Solver.Outcome.One(grid, _) =>
        assertEquals(Solver.answers(parsed.puzzle, grid).map((q, v) => q.of.value -> v),
          Vector("water" -> "Norwegian", "zebra" -> "Japanese"))
      case other => fail(other.toString)
    val job = Computation.Job.Solve(parsed.puzzle, 10)
    assertEquals(Wire.decode[Computation.Job](Wire.encode(job)), Right(job))
    assert(Computation.run(job).isRight)

  test("changing the source clues changes the answers; no classic solution is injected"):
    val renamed = text.replace("Англичанин", "TEMP").replace("Норвежец", "Англичанин").replace("TEMP", "Норвежец")
    val parsed = parse(renamed)
    assert(parsed.ready)
    Solver.solve(parsed.puzzle) match
      case Solver.Outcome.One(grid, _) =>
        assertEquals(Solver.answers(parsed.puzzle, grid).head._2, "Englishman")
      case other => fail(other.toString)
    val inconsistent = parse(text + "\nНорвежец живёт в красном доме.")
    assert(inconsistent.ready)
    assert(Solver.solve(inconsistent.puzzle).isInstanceOf[Solver.Outcome.None])

  test("unlisted values, conflicting counts and unread questions never use remembered missing premises"):
    assert(!parse(text.replace("испанца", "Алексея")).ready)
    assert(!parse(text.replace("пять домов", "шесть домов")).ready)
    assert(!parse(text.replace(uniqueness, "")).ready)
    assert(!parse(text.replace("Кто держит зебру?", "Кто держит дракона?")).ready)
    val negative = parse(text.replace("живёт рядом с синим", "живёт не рядом с синим"))
    assert(!negative.ready)

  test("explicit category headers can ask several questions on one line too"):
    val parsed = parse(PuzzleText.classicEn.replace("Who owns the zebra?", "Who drinks water? Who owns the zebra?"))
    assert(parsed.ready)
    assertEquals(parsed.puzzle.questions.size, 2)

  test("a colour inside a longer cigarette name does not become a second copied argument"):
    val p = Puzzle(2, Vector(Sort("colours", Vector("blue", "red")),
      Sort("smokes", Vector("Blue Master", "Kools"))), Vector.empty, None)
    val found = PuzzleDomain.copier.found("Blue Master lives next to blue.", PuzzleDomain.Schema(p)).map(_._2)
    assertEquals(found, Vector("Blue Master", "blue"))
    assert(PuzzleDomain.copier.found("Reddit", PuzzleDomain.Schema(p)).isEmpty)
