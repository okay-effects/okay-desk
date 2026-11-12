package okay.desk.tasks

import okay.desk.puzzle.{Constraint, Puzzle, Question, Ref, Solver, Sort}
import Grids.{Feature, Voice, item, nation}

/**
 * EINSTEIN'S PUZZLE AND ITS KIN: the two classics word for word (the zebra of 1962 and the fish), each with several
 * questions, with a clue taken away (no single answer) and with a clue too many (no answer at all); then puzzles drawn
 * by seed, from three positions to six, in three settings.
 */
object GridTasks:
  private[tasks] def unique(p: Puzzle): Boolean = Solver.solve(p, 2) match
    case Solver.Outcome.One(_, _) => true
    case _ => false

  private def r(sort: String, value: String): Ref = Ref(sort, value)
  private[tasks] def smoke(brands: String*): Feature = Feature("smoke", "smokes", "smoke", "курит",
    Words("Each smokes a different brand", "Каждый курит свою марку"), Words("What does {A} smoke?", "Что курит {A}?"),
    brands.toVector.map(b => item(b, b, b, b)))
  private def colours(third: (String, String, String)): Feature = Grids.houses.features.head.copy(items = Vector(
    item("red", "the red house", "красном доме", "красный"),
    item("green", "the green house", "зелёном доме", "зелёный"),
    item(third._1, s"the ${third._1} house", s"${third._2} доме", third._3),
    item("yellow", "the yellow house", "жёлтом доме", "жёлтый"),
    item("blue", "the blue house", "синем доме", "синий")))
  private def drinks(fourth: (String, String, String)): Feature = Grids.houses.features(2).copy(items = Vector(
    item("coffee", "coffee", "кофе", "кофе"), item("tea", "tea", "чай", "чай"),
    item("milk", "milk", "молоко", "молоко"), item(fourth._1, fourth._1, fourth._2, fourth._3),
    item("water", "water", "воду", "вода")))

  private val several = Words("The clues do not decide it: more than one arrangement fits them",
    "Условия этого не определяют: им удовлетворяет больше одной расстановки")
  private val none = Words("No arrangement satisfies all the clues: they contradict each other",
    "Ни одна расстановка не удовлетворяет всем условиям: они противоречат друг другу")

  /** a classic in its variants: (title, the clues, the questions); the answers are the exact solver's */
  private def classic(voice: Voice, base: Puzzle,
      variants: Vector[(Words, Vector[Constraint], Vector[Question], Boolean)]): Vector[Entry] =
    variants.map((title, clues, questions, mirror) => Entry(Kind.Einstein, {
      val p = base.copy(constraints = clues, question = questions.headOption, additionalQuestions = questions.drop(1))
      val (expect, answer) = Solver.solve(p, 2) match
        case Solver.Outcome.One(g, _) =>
          val values = Solver.answers(p, g)
          (values.map(_._2), Grids.named(voice, values.map((q, v) => Ref(q.asked, v))))
        case Solver.Outcome.Several(_, _, _) => (Vector("several"), several)
        case Solver.Outcome.None(_) => (Vector("none"), none)
      Grids.task(Grids.houses, title, voice, p, _ => mirror, expect, answer)
    }))

  private def reordered(clues: Vector[Constraint]): Vector[Constraint] = scala.util.Random(7).shuffle(clues)

  private val zebra: Vector[Entry] =
    val p = Puzzle.classic
    val pets = Grids.houses.features(1).copy(items = Vector(item("dog", "the dog", "собаку", "собака"),
      item("snails", "snails", "улиток", "улитки"), item("fox", "the fox", "лису", "лиса"),
      item("horse", "the horse", "лошадь", "лошадь"), item("zebra", "the zebra", "зебру", "зебра")))
    val voice = Voice(Grids.houses, Grids.houses.people.take(5), Vector(colours(("ivory", "бежевом", "бежевый")), pets,
      drinks(("orange juice", "апельсиновый сок", "апельсиновый сок")),
      smoke("Old Gold", "Kools", "Chesterfields", "Lucky Strike", "Parliaments")))
    def q(asked: String, sort: String, value: String) = Question(asked, r(sort, value))
    val owner = q("nation", "pet", "zebra")
    val water = q("nation", "drink", "water")
    classic(voice, p, Vector(
      (Words("The zebra puzzle", "Задача о зебре"), p.constraints, Vector(owner), false),
      (Words("The zebra puzzle: who drinks water?", "Задача о зебре: кто пьёт воду?"), p.constraints, Vector(water),
        false),
      (Words("The zebra puzzle: both questions", "Задача о зебре: оба вопроса"), p.constraints, Vector(water, owner),
        false),
      (Words("The zebra puzzle: the Japanese's house", "Задача о зебре: дом японца"), p.constraints,
        Vector(q("colour", "nation", "Japanese")), false),
      (Words("The zebra puzzle: the fox", "Задача о зебре: лиса"), p.constraints,
        Vector(q("nation", "pet", "fox"), q("smoke", "pet", "fox")), false),
      (Words("The zebra puzzle, told the other way round", "Задача о зебре, рассказанная иначе"),
        reordered(p.constraints), Vector(owner), true),
      (Words("The zebra puzzle without its last clue", "Задача о зебре без последнего условия"),
        p.constraints.init, Vector(owner), false),
      (Words("The zebra puzzle with a clue too many", "Задача о зебре с лишним условием"),
        p.constraints :+ Constraint.Same(r("nation", "Norwegian"), r("drink", "milk")), Vector(owner), false)))

  private val fish: Vector[Entry] =
    val people = Vector(nation("Brit", "британец", "британца"), nation("Swede", "швед", "шведа"),
      nation("Dane", "датчанин", "датчанина"), nation("Norwegian", "норвежец", "норвежца"),
      nation("German", "немец", "немца"))
    val pets = Grids.houses.features(1).copy(items = Vector(item("dogs", "dogs", "собак", "собаки"),
      item("birds", "birds", "птиц", "птицы"), item("cats", "cats", "кошек", "кошки"),
      item("horses", "horses", "лошадей", "лошади"), item("fish", "fish", "рыбок", "рыбки")))
    val features = Vector(colours(("white", "белом", "белый")), pets, drinks(("beer", "пиво", "пиво")),
      smoke("Pall Mall", "Dunhill", "Blend", "Blue Master", "Prince"))
    val voice = Voice(Grids.houses, people, features)
    val clues = Vector(
      Constraint.Same(r("nation", "Brit"), r("colour", "red")),
      Constraint.Same(r("nation", "Swede"), r("pet", "dogs")),
      Constraint.Same(r("nation", "Dane"), r("drink", "tea")),
      Constraint.RightNext(r("colour", "white"), r("colour", "green")),
      Constraint.Same(r("colour", "green"), r("drink", "coffee")),
      Constraint.Same(r("smoke", "Pall Mall"), r("pet", "birds")),
      Constraint.Same(r("colour", "yellow"), r("smoke", "Dunhill")),
      Constraint.Middle(r("drink", "milk")),
      Constraint.At(r("nation", "Norwegian"), 1),
      Constraint.Adjacent(r("smoke", "Blend"), r("pet", "cats")),
      Constraint.Adjacent(r("pet", "horses"), r("smoke", "Dunhill")),
      Constraint.Same(r("smoke", "Blue Master"), r("drink", "beer")),
      Constraint.Same(r("nation", "German"), r("smoke", "Prince")),
      Constraint.Adjacent(r("nation", "Norwegian"), r("colour", "blue")),
      Constraint.Adjacent(r("smoke", "Blend"), r("drink", "water")))
    val p = Puzzle(5, Sort("nation", people.map(_.key)) +: features.map(f => Sort(f.sort, f.items.map(_.key))),
      clues, None)
    def q(asked: String, sort: String, value: String) = Question(asked, r(sort, value))
    val owner = q("nation", "pet", "fish")
    classic(voice, p, Vector(
      (Words("Einstein's riddle: who keeps the fish?", "Загадка Эйнштейна: у кого рыбки?"), clues, Vector(owner),
        false),
      (Words("Einstein's riddle: the Swede's drink", "Загадка Эйнштейна: что пьёт швед?"), clues,
        Vector(q("drink", "nation", "Swede")), false),
      (Words("Einstein's riddle: the German's house", "Загадка Эйнштейна: дом немца"), clues,
        Vector(q("colour", "nation", "German")), false),
      (Words("Einstein's riddle: water and horses", "Загадка Эйнштейна: вода и лошади"), clues,
        Vector(q("nation", "drink", "water"), q("nation", "pet", "horses")), false),
      (Words("Einstein's riddle, told the other way round", "Загадка Эйнштейна, рассказанная иначе"),
        reordered(clues), Vector(owner), true),
      (Words("Einstein's riddle without the fourth clue", "Загадка Эйнштейна без четвёртого условия"),
        clues.patch(3, Nil, 1),
        Vector(owner), false),
      (Words("Einstein's riddle with a clue too many", "Загадка Эйнштейна с лишним условием"),
        clues :+ Constraint.Left(r("colour", "white"), r("colour", "green")), Vector(owner), false)))

  /** the drawn puzzles of a setting: (positions, features, how many) */
  private def drawn(s: Grids.Setting, seed: Long, sizes: (Int, Int, Int)*): Vector[Entry] =
    for
      ((n, features, many), k) <- sizes.toVector.zipWithIndex
      i <- (1 to many).toVector
    yield Entry(s.kind, Grids.generate(s, n, features, seed + k * 100 + i, i)(unique))

  val entries: Vector[Entry] = zebra ++ fish ++
    drawn(Grids.houses, 1000, (3, 2, 3), (4, 3, 5), (5, 4, 8), (6, 4, 2)) ++
    drawn(Grids.floors, 2000, (3, 2, 2), (4, 3, 4), (5, 4, 4), (6, 3, 1)) ++
    drawn(Grids.race, 3000, (3, 2, 2), (4, 3, 4), (5, 3, 4), (6, 3, 1))
