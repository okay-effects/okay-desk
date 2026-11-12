package okay.desk.tasks

/**
 * THE CORPUS (specs/formalization.md §7 step 3): tasks drawn without end, each written together with its statement
 * (`Writing`), so that a text carries every decision of its reading as data — what the heads are trained on and
 * measured by. A task is drawn by a seed from a FAMILY (a setting of a grid, a theme of a matching, a scale of a
 * comparison), small first: three and four positions.
 *
 * The splits are disjoint by construction. A task's split is a function of its English text, so the same text never
 * lands in two of them, whatever the counts asked for; both languages of a task go together. UNSEEN is another
 * matter: whole families no other split has — their words, their names, their ways of saying a clue — the honest
 * measure of reading against remembering. No task of the collection is in the corpus.
 */
object Drawn:
  enum Split:
    case Train, Validate, Test, Unseen, Sealed

  /** a family of tasks: its name, whether it is kept out of training, its share of the draws, and a task of it
   * by a seed */
  final case class Family(name: String, held: Boolean, weight: Int, draw: Long => Task)

  /** a corpus task: where it is from, and the task with its statement in both languages */
  final case class Item(family: String, split: Split, seed: Long, task: Task)

  /** the sizes of a drawn grid, from three positions to the collection's largest: (positions, features beside the
   * people) */
  private val sizes = Vector((3, 2), (3, 2), (3, 3), (4, 2), (4, 3), (4, 3), (5, 3), (5, 4), (6, 3), (6, 4))

  /** a choice among `n` by a task's seed: of the seed mixed, so that no choice follows the family's turn */
  private def choice(seed: Long, salt: Int, n: Int): Int =
    math.floorMod(scala.util.hashing.MurmurHash3.stringHash(s"$seed/$salt"), n)

  /**
   * a setting's family: a task takes each of its phrases — where a thing is, the order of two, the place just after,
   * just before, beside — from one of the setting's wordings (`Settings.ways`), by its seed
   */
  private def grid(name: String, held: Boolean, ways: Vector[Grids.Setting]): Family = Family(name, held, 6, seed =>
    val (n, features) = sizes(choice(seed, 1, sizes.size))
    def of(salt: Int): Grids.Setting = ways(choice(seed, salt, ways.size))
    val s = ways.head.copy(at = of(3).at, middle = of(3).middle, left = of(4).left, right = of(5).right,
      next = of(6).next, prev = of(7).prev, near = of(8).near)
    Grids.generate(s, n, features, seed, 1, any = true)(GridTasks.unique))
  private def worded(name: String, s: Grids.Setting): Vector[Grids.Setting] =
    s +: Settings.ways.getOrElse(name, Vector.empty)

  /** the houses as training draws them: with what each smokes — the fifth attribute of the classic puzzle, which
   * the collection's Einstein tasks have and the drawn setting did not (specs/formalization.md §12.3); and positions
   * said in words — "in the third house", "not in the second", "one house between them" (§17) */
  private val houses = Grids.houses.copy(features = Grids.houses.features :+ GridTasks.smoke("Old Gold", "Kools",
    "Chesterfields", "Lucky Strike", "Parliaments", "Pall Mall", "Dunhill", "Blend", "Blue Master", "Prince"),
    // positions in words (§17): the corpus says them; the collection's houses stay as they were
    place = Some(k => Words(s"lives in the ${k.en} house", s"живёт в ${k.ru} доме")),
    notAt = Some(k => Words(s"does not live in the ${k.en} house", s"живёт не в ${k.ru} доме")),
    between = Some(k =>
      val one = okay.desk.statement.Writing.plain(k.en) == "one"
      Words(s"{A} and {B} have ${k.en} house${if one then "" else "s"} between them",
        s"{A} и {B} живут через ${k.ru} дом${if one then "" else "а"} друг от друга")))

  val families: Vector[Family] =
    Vector("houses" -> houses, "floors" -> Grids.floors, "race" -> Grids.race).map((name, s) =>
      grid(name, name == "race", worded(name, s))) ++
    Settings.all.map((name, s) => grid(name, name == "talks", worded(name, s))) ++
    (0 until Matching.themeCount).toVector.map(i => Family(s"matching$i", i == Matching.heldTheme, 2, seed =>
      Matching.matching(i, 3 + choice(seed, 1, 2), seed))) ++
    (0 until Matching.scaleCount).toVector.map(i => Family(s"comparison$i", Matching.heldScale(i), 1, seed =>
      Matching.comparison(i, 3 + choice(seed, 1, 3), choice(seed, 2, 4), seed)))

  /**
   * THE SEALED FAMILIES (`SealedSet`): written by another hand than the corpus and never trained on; whoever
   * maintains the corpus does not read their wordings or their results by task — totals only (specs §12)
   */
  val sealedFamilies: Vector[Family] =
    SealedSet.settings.map((name, s) => grid(s"sealed-$name", true, Vector(s))) ++
    SealedSet.themes.zipWithIndex.map((t, i) => Family(s"sealed-matching$i", true, 2, seed =>
      Matching.assign(t, 3 + choice(seed, 1, 2), seed, 1))) ++
    SealedSet.scales.zipWithIndex.map((s, i) => Family(s"sealed-comparison$i", true, 1, seed =>
      Matching.order(s, 3 + choice(seed, 1, 3), choice(seed, 2, 4), seed)))

  /** the settings of a grid that training has, in the order they were written */
  val grids: Vector[String] = Vector("houses", "floors") ++ Settings.all.map(_._1).filter(_ != "talks")

  private lazy val collection: Set[String] = Tasks.entries.map(_.task.text.en).toSet

  /** the split of a text among the families seen in training: one in ten to Test, one in ten to Validate */
  def splitOf(english: String): Split = math.floorMod(scala.util.hashing.MurmurHash3.stringHash(english), 10) match
    case 0 => Split.Test
    case 1 => Split.Validate
    case _ => Split.Train

  /**
   * the first `count` tasks of a split, the families in turn: the same tasks whenever asked, a longer list beginning
   * with a shorter one
   */
  def items(split: Split, count: Int): Vector[Item] =
    val whole = split == Split.Unseen || split == Split.Sealed
    val of = (if split == Split.Sealed then sealedFamilies else families.filter(_.held == whole))
      .flatMap(f => Vector.fill(f.weight)(f))
    val seen = scala.collection.mutable.Set.empty[String]
    if of.isEmpty then Vector.empty else Iterator.from(0).flatMap { i =>
      val (family, seed) = (of(i % of.size), 1_000_000L + i)
      val task = family.draw(seed)
      val mine = whole || splitOf(task.text.en) == split
      Option.when(mine && !collection(task.text.en) && seen.add(task.text.en))(Item(family.name, split, seed, task))
    }.take(count).toVector
