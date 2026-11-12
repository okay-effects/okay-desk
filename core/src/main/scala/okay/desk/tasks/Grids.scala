package okay.desk.tasks

import okay.desk.puzzle.{Constraint, Puzzle, Question, Ref, Sort}
import okay.desk.statement.{Con, Problem, Writing}
import Writing.{mark, within}

/**
 * ORDERED GRIDS IN WORDS: Einstein's puzzle and its kin — values of several sorts on positions in a row (houses,
 * floors, places in a race). A setting says how its people, features and positions are worded in English and in
 * Russian. `generate` draws a solution and clues true of it, keeps only the clues the exact solver needs to find the
 * solution unique, and words them: the text and the gold puzzle are the same clues, the answer is the drawn solution's.
 */
object Grids:
  /** who the puzzle is about: the name as a subject, and in Russian also "of whom" (the genitive) */
  final case class Who(key: String, en: String, ru: String, ruOf: String)
  /** a feature's value: the puzzle's value, the object of the feature's verb in both languages, its name alone */
  final case class Item(key: String, en: String, ru: String, name: Words)
  /**
   * a feature said by a verb ("keeps a dog", "does not keep a dog", "держит собаку"); `each` introduces its values,
   * `ask` asks for it about `{A}`
   */
  final case class Feature(sort: String, verb: String, base: String, ruVerb: String, each: Words, ask: Words,
      items: Vector[Item])
  /** the positions' wording: `{A}` and `{B}` the two said; in Russian `{B}` in the genitive, except in `near` */
  final case class Setting(kind: Kind, sort: String, one: String, title: Int => Words, intro: Int => Words,
      people: Vector[Who], features: Vector[Feature], at: String => Words, middle: Words, left: Words, right: Words,
      next: Words, prev: Words, near: Words, place: Option[Words => Words] = None,
      notAt: Option[Words => Words] = None, between: Option[Words => Words] = None)

  /**
   * POSITIONS SAID IN WORDS (specs/formalization.md §17): "in the third house", «в третьем доме»; "not in the second
   * house"; "one house between them". The number is marked where it is said, a word as much as digits: the
   * statement copies it, `Numbers` reads it. `place`: where a thing is, by an ordinal; `notAt`: where it is not;
   * `between`: how many positions lie between two ({A}, {B}, then the number) — a setting with none of them draws
   * no such clue.
   */
  private val ordinalEn = Vector("first", "second", "third", "fourth", "fifth", "sixth")
  /** the Russian ordinal in the prepositional of a masculine noun: «в третьем доме», «на третьем этаже» */
  private val ordinalRu = Vector("первом", "втором", "третьем", "четвёртом", "пятом", "шестом")
  private val cardinalEn = Vector("no", "one", "two", "three")
  private val cardinalRu = Vector("ноль", "один", "два", "три")
  def ordinal(n: Int): Words = Words(Writing.mark("#", ordinalEn(n - 1)), Writing.mark("#", ordinalRu(n - 1)))
  def cardinal(n: Int): Words = Words(Writing.mark("#", cardinalEn(n)), Writing.mark("#", cardinalRu(n)))

  private[tasks] val count = Map(3 -> Words("Three", "три"), 4 -> Words("Four", "четыре"), 5 -> Words("Five", "пять"),
    6 -> Words("Six", "шесть"))
  /** "три дома", "пять домов" */
  private[tasks] def ru(n: Int, few: String, many: String): String = s"${count(n).ru} ${if n < 5 then few else many}"
  def item(key: String, en: String, ru: String, name: String): Item = Item(key, en, ru, Words(key, name))
  def who(key: String, ru: String, ruOf: String): Who = Who(key, key, ru, ruOf)
  def nation(key: String, ru: String, ruOf: String): Who = Who(key, s"the $key", ru, ruOf)

  private[tasks] val drink = Feature("drink", "drinks", "drink", "пьёт",
    Words("Each has a favourite drink", "У каждого свой любимый напиток"),
    Words("What does {A} drink?", "Что пьёт {A}?"),
    Vector(item("tea", "tea", "чай", "чай"), item("coffee", "coffee", "кофе", "кофе"),
      item("milk", "milk", "молоко", "молоко"), item("water", "water", "воду", "вода"),
      item("juice", "juice", "сок", "сок"), item("cocoa", "cocoa", "какао", "какао")))
  private[tasks] val pet = Feature("pet", "keeps", "keep", "держит",
    Words("Each keeps a different pet", "Каждый держит своё животное"),
    Words("Which pet does {A} keep?", "Какое животное держит {A}?"),
    Vector(item("dog", "a dog", "собаку", "собака"), item("cat", "a cat", "кошку", "кошка"),
      item("horse", "a horse", "лошадь", "лошадь"), item("fish", "fish", "рыбок", "рыбки"),
      item("parrot", "a parrot", "попугая", "попугай"), item("zebra", "a zebra", "зебру", "зебра")))

  val houses: Setting = Setting(Kind.Einstein, "nation", "the person who",
    n => Words(s"${count(n).en} houses", ru(n, "дома", "домов").capitalize),
    n => Words(s"${count(n).en} houses stand in a row, numbered 1 to $n from left to right. " +
      "In each lives a person of a different nation",
      s"В ряд стоят ${ru(n, "дома", "домов")}, они пронумерованы слева направо от 1 до $n. " +
        "В каждом живёт человек своей национальности"),
    Vector(nation("Englishman", "англичанин", "англичанина"), nation("Spaniard", "испанец", "испанца"),
      nation("Ukrainian", "украинец", "украинца"), nation("Norwegian", "норвежец", "норвежца"),
      nation("Japanese", "японец", "японца"), nation("Dane", "датчанин", "датчанина"),
      nation("German", "немец", "немца"), nation("Swede", "швед", "шведа")),
    Vector(
      Feature("colour", "lives in", "live in", "живёт в",
        Words("Each house has its own colour", "У каждого дома свой цвет"),
        Words("What colour is the house where {A} lives?", "Какого цвета дом, в котором живёт {A}?"),
        Vector(item("red", "the red house", "красном доме", "красный"),
          item("green", "the green house", "зелёном доме", "зелёный"),
          item("white", "the white house", "белом доме", "белый"),
          item("yellow", "the yellow house", "жёлтом доме", "жёлтый"),
          item("blue", "the blue house", "синем доме", "синий"),
          item("black", "the black house", "чёрном доме", "чёрный"))),
      pet, drink,
      Feature("instrument", "plays", "play", "играет на",
        Words("Each plays a different instrument", "Каждый играет на своём инструменте"),
        Words("Which instrument does {A} play?", "На каком инструменте играет {A}?"),
        Vector(item("piano", "the piano", "пианино", "пианино"), item("violin", "the violin", "скрипке", "скрипка"),
          item("guitar", "the guitar", "гитаре", "гитара"), item("flute", "the flute", "флейте", "флейта"),
          item("drums", "the drums", "барабанах", "барабаны"), item("harp", "the harp", "арфе", "арфа")))),
    n => Words(s"lives in house $n", s"живёт в доме $n"),
    Words("lives in the middle house", "живёт в среднем доме"),
    Words("{A} lives somewhere to the left of {B}", "{A} живёт где-то левее {B}"),
    Words("{A} lives somewhere to the right of {B}", "{A} живёт где-то правее {B}"),
    Words("{A} lives immediately to the right of {B}", "{A} живёт сразу справа от {B}"),
    Words("{A} lives immediately to the left of {B}", "{A} живёт сразу слева от {B}"),
    Words("{A} and {B} live in neighbouring houses", "{A} и {B} живут в соседних домах"))

  val floors: Setting = Setting(Kind.Places, "tenant", "the person who",
    n => Words(s"${count(n).en} floors", ru(n, "этажа", "этажей").capitalize),
    n => Words(s"A house has ${count(n).en.toLowerCase} floors, numbered from 1 at the bottom to $n at the top. " +
      "One person lives on each floor",
      s"В доме ${ru(n, "этажа", "этажей")}, они пронумерованы снизу вверх от 1 до $n. " +
        "На каждом этаже живёт один человек"),
    Vector(who("Anna", "Анна", "Анны"), who("Boris", "Борис", "Бориса"), who("Vera", "Вера", "Веры"),
      who("Gleb", "Глеб", "Глеба"), who("Dina", "Дина", "Дины"), who("Egor", "Егор", "Егора")),
    Vector(
      Feature("job", "works as", "work as", "работает",
        Words("Each has a different job", "У каждого своя профессия"),
        Words("What does {A} work as?", "Кем работает {A}?"),
        Vector(item("doctor", "a doctor", "врачом", "врач"), item("teacher", "a teacher", "учителем", "учитель"),
          item("engineer", "an engineer", "инженером", "инженер"), item("cook", "a cook", "поваром", "повар"),
          item("driver", "a driver", "водителем", "водитель"),
          item("painter", "a painter", "художником", "художник"))),
      pet, drink,
      Feature("game", "plays", "play", "играет в",
        Words("Each plays a different game", "Каждый играет в свою игру"),
        Words("Which game does {A} play?", "Во что играет {A}?"),
        Vector(item("chess", "chess", "шахматы", "шахматы"), item("tennis", "tennis", "теннис", "теннис"),
          item("football", "football", "футбол", "футбол"), item("hockey", "hockey", "хоккей", "хоккей"),
          item("checkers", "checkers", "шашки", "шашки"), item("golf", "golf", "гольф", "гольф")))),
    n => Words(s"lives on floor $n", s"живёт на этаже $n"),
    Words("lives on the middle floor", "живёт на среднем этаже"),
    Words("{A} lives somewhere below {B}", "{A} живёт ниже {B}"),
    Words("{A} lives somewhere above {B}", "{A} живёт выше {B}"),
    Words("{A} lives exactly one floor above {B}", "{A} живёт ровно на этаж выше {B}"),
    Words("{A} lives exactly one floor below {B}", "{A} живёт ровно на этаж ниже {B}"),
    Words("{A} and {B} live on adjacent floors", "{A} и {B} живут на соседних этажах"))

  val race: Setting = Setting(Kind.Places, "runner", "the runner who",
    n => Words(s"${count(n).en} runners", ru(n, "бегуна", "бегунов").capitalize),
    n => Words(s"${count(n).en} runners finished a race in places 1 (the winner) to $n, with no ties",
      s"В забеге участвуют ${ru(n, "бегуна", "бегунов")}; места с 1 (победитель) по $n, ничьих нет"),
    Vector(who("Max", "Макс", "Макса"), who("Nina", "Нина", "Нины"), who("Oleg", "Олег", "Олега"),
      who("Petra", "Петра", "Петры"), who("Roman", "Роман", "Романа"), who("Sofia", "София", "Софии")),
    Vector(
      Feature("shirt", "wears", "wear", "бежит в",
        Words("Each wears a shirt of a different colour", "Все бегут в майках разного цвета"),
        Words("Which shirt does {A} wear?", "В какой майке бежит {A}?"),
        Vector(item("red", "the red shirt", "красной майке", "красная"),
          item("green", "the green shirt", "зелёной майке", "зелёная"),
          item("white", "the white shirt", "белой майке", "белая"),
          item("yellow", "the yellow shirt", "жёлтой майке", "жёлтая"),
          item("blue", "the blue shirt", "синей майке", "синяя"),
          item("black", "the black shirt", "чёрной майке", "чёрная"))),
      Feature("country", "represents", "represent", "представляет",
        Words("Each represents a different country", "Каждый представляет свою страну"),
        Words("Which country does {A} represent?", "Какую страну представляет {A}?"),
        Vector(item("Spain", "Spain", "Испанию", "Испания"), item("Italy", "Italy", "Италию", "Италия"),
          item("Poland", "Poland", "Польшу", "Польша"), item("Norway", "Norway", "Норвегию", "Норвегия"),
          item("Japan", "Japan", "Японию", "Япония"), item("Canada", "Canada", "Канаду", "Канада"))),
      Feature("number", "runs under", "run under", "бежит под",
        Words("Each runs under a different number", "У каждого свой номер"),
        Words("Under which number does {A} run?", "Под каким номером бежит {A}?"),
        Vector("7", "12", "25", "31", "48", "56").map(k => item(k, s"number $k", s"номером $k", k)))),
    n => Words(s"finished in place $n", s"занимает место $n"),
    Words("finished exactly in the middle", "занимает место ровно посередине"),
    Words("{A} finished somewhere ahead of {B}", "{A} финиширует раньше {B}"),
    Words("{A} finished somewhere behind {B}", "{A} финиширует позже {B}"),
    Words("{A} finished immediately after {B}", "{A} финиширует на одно место позже {B}"),
    Words("{A} finished immediately before {B}", "{A} финиширует на одно место раньше {B}"),
    Words("{A} and {B} finished one right after the other, in some order", "{A} и {B} занимают соседние места"))

  /**
   * the wording of a puzzle: its people and features, as chosen. Every person and every value is MARKED where it is
   * said (`Writing.mark`), so the text carries its statement: the name in the list declares the thing, a form of it
   * in a clue names it again
   */
  final class Voice(s: Setting, people: Vector[Who], features: Vector[Feature]):
    private def key(r: Ref): String = s"${r.sort}/${r.value}"
    private def who(r: Ref): Option[Who] = Option.when(r.sort == s.sort)(people.find(_.key == r.value).get)
    private def of(r: Ref): (Feature, Item) =
      val f = features.find(_.sort == r.sort).get
      (f, f.items.find(_.key == r.value).get)
    private def item(r: Ref, russian: Boolean): String =
      val i = of(r)._2
      if russian then within(key(r), i.ru, i.name.ru) else within(key(r), i.en, i.name.en)
    private def np(r: Ref): String =
      who(r).fold(s"${s.one} ${of(r)._1.verb} ${item(r, false)}")(w => within(key(r), w.en, w.key))
    private def vp(r: Ref, not: Boolean = false): String =
      val f = of(r)._1
      if not then s"does not ${f.base} ${item(r, false)}" else s"${f.verb} ${item(r, false)}"
    private def ruVp(r: Ref): String = s"${of(r)._1.ruVerb} ${item(r, true)}"
    /** the Russian subject; a comma closes "тот, кто …" before what follows */
    private def nom(r: Ref): String = who(r).fold(s"тот, кто ${ruVp(r)},")(w => mark(key(r), w.ru))
    private def gen(r: Ref): String = who(r).fold(s"того, кто ${ruVp(r)}")(w => mark(key(r), w.ruOf))
    private def sentence(text: String, end: String = "."): String = Writing.capital(text) + end
    private def two(t: Words, a: Ref, b: Ref, subjects: Boolean = false): Words = Words(
      sentence(t.en.replace("{A}", np(a)).replace("{B}", np(b))),
      sentence(t.ru.replace("{A}", nom(a)).replace("{B}", if subjects then nom(b) else gen(b))))
    private def one(a: Ref, t: Words): Words = Words(sentence(s"${np(a)} ${t.en}"), sentence(s"${nom(a)} ${t.ru}"))

    /**
     * a clue in words, and the constructor it states of the things in the order they are said; `mirror`: an order
     * said from its other end ("to the right of" for "to the left of")
     */
    def say(c: Constraint, mirror: Boolean): (Words, Con) = c match
      case Constraint.Same(a, b) =>
        val (x, y) = if who(b).nonEmpty then (b, a) else (a, b)
        (Words(sentence(s"${np(x)} ${vp(y)}"), sentence(s"${nom(x)} ${ruVp(y)}")), Con.Same)
      case Constraint.Differ(a, b) =>
        val (x, y) = if who(b).nonEmpty then (b, a) else (a, b)
        (Words(sentence(s"${np(x)} ${vp(y, not = true)}"), sentence(s"${nom(x)} не ${ruVp(y)}")), Con.Differ)
      case Constraint.At(a, n) => s.place.filter(_ => mirror).fold((one(a, s.at(Writing.number(n))), Con.At))(p =>
        (one(a, p(ordinal(n))), Con.At))
      case Constraint.NotAt(a, n) => (one(a, s.notAt.get(ordinal(n))), Con.NotAt)
      case Constraint.Apart(a, b, d) => (two(s.between.get(cardinal(d - 1)), a, b, subjects = true), Con.Between)
      case Constraint.Middle(a) => (one(a, s.middle), Con.Middle)
      case Constraint.Left(a, b) => if mirror then (two(s.right, b, a), Con.After) else (two(s.left, a, b), Con.Before)
      case Constraint.RightNext(a, b) =>
        if mirror then (two(s.prev, b, a), Con.JustBefore) else (two(s.next, a, b), Con.JustAfter)
      case Constraint.Adjacent(a, b) => (two(s.near, a, b, subjects = true), Con.Beside)

    def ask(q: Question): Words =
      if q.asked == s.sort then Words(sentence(s"who ${vp(q.of)}", "?"), sentence(s"кто ${ruVp(q.of)}", "?"))
      else
        val f = features.find(_.sort == q.asked).get
        Words(f.ask.en.replace("{A}", np(q.of)), f.ask.ru.replace("{A}", nom(q.of).stripSuffix(",")))

    def name(r: Ref): Words = who(r).fold(of(r)._2.name)(w => Words(w.en, w.ru))

    /** the attributes in the order the introduction lists them: the people, then the features */
    val attributes: Vector[String] = s.sort +: features.map(_.sort)

    def intro(n: Int): Words =
      def named(f: Feature, russian: Boolean): String =
        f.items.map(i => mark(key(Ref(f.sort, i.key)), i.name.in(russian))).mkString(", ")
      def them(russian: Boolean): String =
        people.map(p => if russian then mark(key(Ref(s.sort, p.key)), p.ru)
          else within(key(Ref(s.sort, p.key)), p.en, p.key)).mkString(", ")
      val start = s.intro(n)
      Words((s"${start.en}: ${them(false)}." +: features.map(f => s"${f.each.en}: ${named(f, false)}.")).mkString(" "),
        (s"${start.ru}: ${them(true)}." +: features.map(f => s"${f.each.ru}: ${named(f, true)}.")).mkString(" "))

  /**
   * a puzzle as a task: the introduction, the numbered clues, the question(s); `expect` is the exact solver's result
   * — the values asked for, or "several" / "none" — and `answer` says it to a person. The statement written with it:
   * an attribute for every list of the introduction, a clue's constructor, a question's attribute
   */
  def task(s: Setting, title: Words, voice: Voice, p: Puzzle, mirrored: Int => Boolean, expect: Vector[String],
      answer: Words): Task =
    val intro = (voice.intro(p.positions), voice.attributes.map(_ => Writing.list(Con.Attribute, p.positions)))
    val clues = p.constraints.zipWithIndex.map { (c, i) =>
      val (w, con) = voice.say(c, mirrored(i))
      (Words(s"\n${i + 1}. ${w.en}", s"\n${i + 1}. ${w.ru}"), Vector(Writing.say(con))) }
    val asks = p.questions.zipWithIndex.map { (q, i) =>
      val (w, gap) = (voice.ask(q), if i == 0 then "\n" else " ")
      (Words(gap + w.en, gap + w.ru), Vector(Writing.ask(voice.attributes.indexOf(q.asked)))) }
    Task.written(s.kind, title, Problem.Assign, intro +: (clues ++ asks), answer, Gold.Grid(p), expect)

  /** the answers named: the values the questions ask for */
  def named(voice: Voice, answers: Vector[Ref]): Words =
    Words(answers.map(voice.name(_).en).mkString(", "), answers.map(voice.name(_).ru).mkString(", "))

  /**
   * A DRAWN PUZZLE: `n` positions, the people and `features` features, by the seed. Clues true of a drawn solution
   * are taken generously, then every clue the uniqueness does not need is dropped — a named position first, so that
   * what stays is mostly who-with-what and neighbours. `unique` is the exact solver's verdict that a set of clues
   * leaves one solution. The question is mostly who: `any` asks about any attribute as likely as about another —
   * a reader taught on mostly-who learns that a question asks for the first list, not what it asks for.
   */
  def generate(s: Setting, n: Int, features: Int, seed: Long, variant: Int, any: Boolean = false)(
      unique: Puzzle => Boolean): Task =
    val rnd = scala.util.Random(seed)
    val people = rnd.shuffle(s.people).take(n)
    val chosen = rnd.shuffle(s.features).take(features).map(f => f.copy(items = rnd.shuffle(f.items).take(n)))
    val sorts = Sort(s.sort, people.map(_.key)) +: chosen.map(f => Sort(f.sort, f.items.map(_.key)))
    val grid: Map[Ref, Int] = sorts.flatMap(so =>
      rnd.shuffle((1 to n).toVector).zip(so.values).map((pos, v) => Ref(so.name, v) -> pos)).toMap
    val refs = sorts.flatMap(so => so.values.map(Ref(so.name, _)))
    val order = sorts.map(_.name).zipWithIndex.toMap
    val pairs = for a <- refs; b <- refs if a != b yield (a, b)
    val cross = pairs.filter((a, b) => order(a.sort) < order(b.sort))
    val same = rnd.shuffle(cross.filter((a, b) => grid(a) == grid(b))).take(n * features).map(Constraint.Same(_, _))
    val next = rnd.shuffle(pairs.filter((a, b) => grid(a) == grid(b) + 1)).take(n).map(Constraint.RightNext(_, _))
    val beside = pairs.filter((a, b) => (grid(a) - grid(b)).abs == 1 && refs.indexOf(a) < refs.indexOf(b))
    val near = rnd.shuffle(beside).take(n).map(Constraint.Adjacent(_, _))
    val left = rnd.shuffle(pairs.filter((a, b) => grid(a) < grid(b) - 1)).take(n).map(Constraint.Left(_, _))
    val differ = rnd.shuffle(cross.filter((a, b) => grid(a) != grid(b))).take(n / 2).map(Constraint.Differ(_, _))
    val notAt = if s.notAt.isEmpty then Vector.empty else
      rnd.shuffle(refs).take(n / 2).map(r => Constraint.NotAt(r, rnd.shuffle((1 to n).filter(_ != grid(r))).head))
    val apart = if s.between.isEmpty || n < 3 then Vector.empty else
      rnd.shuffle(pairs.filter((a, b) =>
        (2 to 3).contains((grid(a) - grid(b)).abs) && refs.indexOf(a) < refs.indexOf(b)))
        .take(n / 2).map((a, b) => Constraint.Apart(a, b, (grid(a) - grid(b)).abs))
    val at = rnd.shuffle(refs).take(2).map(r =>
      if n % 2 == 1 && grid(r) == (n + 1) / 2 then Constraint.Middle(r) else Constraint.At(r, grid(r)))
    def puzzle(cs: Vector[Constraint]): Puzzle = Puzzle(n, sorts, cs, None)
    // the order the clues are tried for dropping in: one named position first, the other last
    var kept: Vector[Constraint] =
      at.take(1) ++ rnd.shuffle(differ ++ left ++ next ++ near ++ same ++ notAt ++ apart) ++ at.drop(1)
    if !unique(puzzle(kept)) then kept = refs.map(r => Constraint.At(r, grid(r))) ++ kept
    for c <- kept do
      val without = kept.filterNot(_ == c)
      if unique(puzzle(without)) then kept = without
    val clues = rnd.shuffle(kept)
    val told = clues.collect { case Constraint.Same(a, b) => Set(a, b) }.toSet
    val asks = for
      of <- rnd.shuffle(refs)
      so <- sorts if so.name != of.sort
      answer = Ref(so.name, so.values.find(v => grid(Ref(so.name, v)) == grid(of)).get)
      if !told(Set(of, answer))
    yield (Question(so.name, of), answer)
    val (question, answer) =
      if any then rnd.shuffle(sorts.map(_.name).filter(so => asks.exists(_._1.asked == so))).headOption
        .flatMap(so => asks.find(_._1.asked == so)).getOrElse(asks.head)
      else asks.find(_._1.asked == s.sort).filter(_ => rnd.nextInt(3) > 0).getOrElse(asks.head)
    val mirrored = clues.map(_ => rnd.nextBoolean())
    val title = s.title(n)
    val voice = Voice(s, people, chosen)
    task(s, Words(s"${title.en}, variant $variant", s"${title.ru}, вариант $variant"), voice,
      puzzle(clues).copy(question = Some(question)), mirrored, Vector(answer.value), named(voice, Vector(answer)))
