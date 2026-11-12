package okay.desk.tasks

import okay.effects.json.Json
import Gold.*
import Grids.Who
import okay.desk.statement.{Con, Problem, Writing}
import Writing.mark

/**
 * WHO IS WHO, AND WHO IS TALLER: people and what each of them has, told by what they do not have; people in an order,
 * told by comparisons of two. Both are drawn by seed: a hidden answer, clues true of it, and only the clues needed to
 * leave that one answer — which the finite solver then confirms.
 */
object Matching:
  /** a thing a person may have: its value, its name in a list, and how a clue says it in Russian */
  private[tasks] final case class Thing(key: String, en: String, ru: String, ruSaid: String)
  /** `{P}` the person (`{Pof}`: of the person, in Russian), `{V}` and `{W}` the things, `{L}` the lists */
  private[tasks] final case class Theme(title: Words, intro: Words, not: Words, either: Words, ask: Words,
      things: Vector[Thing], people: Vector[Who] = Grids.floors.people)

  private val themes = Vector(
    Theme(Words("Fruit", "Фрукты"),
      Words("{P} each took a different fruit: {L}.", "{P} взяли по одному фрукту, все разные: {L}."),
      Words("{P} did not take the {V}.", "У {Pof} не {V}."),
      Words("{P} took either the {V} or the {W}.", "У {Pof} {V} или {W}."),
      Words("Who took the {V}?", "У кого {V}?"),
      Vector(Thing("apple", "apple", "яблоко", "яблоко"), Thing("pear", "pear", "груша", "груша"),
        Thing("plum", "plum", "слива", "слива"), Thing("peach", "peach", "персик", "персик"),
        Thing("orange", "orange", "апельсин", "апельсин"), Thing("lemon", "lemon", "лимон", "лимон"))),
    Theme(Words("Hats", "Шляпы"),
      Words("{P} wear hats of different colours: {L}.", "{P} носят шляпы разных цветов: {L}."),
      Words("{P}'s hat is not {V}.", "У {Pof} шляпа не {V}."),
      Words("{P}'s hat is either {V} or {W}.", "У {Pof} шляпа {V} или {W}."),
      Words("Whose hat is {V}?", "У кого {V} шляпа?"),
      Vector(Thing("red", "red", "красная", "красная"), Thing("green", "green", "зелёная", "зелёная"),
        Thing("white", "white", "белая", "белая"), Thing("black", "black", "чёрная", "чёрная"),
        Thing("blue", "blue", "синяя", "синяя"), Thing("yellow", "yellow", "жёлтая", "жёлтая"))),
    Theme(Words("Journeys", "Поездки"),
      Words("{P} each travel to a different city: {L}.", "{P} едут в разные города: {L}."),
      Words("{P} does not travel to {V}.", "{P} едет не в {V}."),
      Words("{P} travels to either {V} or {W}.", "{P} едет в {V} или в {W}."),
      Words("Who travels to {V}?", "Кто едет в {V}?"),
      Vector(Thing("Rome", "Rome", "Рим", "Рим"), Thing("Paris", "Paris", "Париж", "Париж"),
        Thing("Oslo", "Oslo", "Осло", "Осло"), Thing("Vienna", "Vienna", "Вена", "Вену"),
        Thing("Prague", "Prague", "Прага", "Прагу"), Thing("Madrid", "Madrid", "Мадрид", "Мадрид"))),
    Theme(Words("Jobs", "Профессии"),
      Words("{P} have different jobs: {L}.", "{P} — люди разных профессий: {L}."),
      Words("{P} is not the {V}.", "{P} — не {V}."),
      Words("{P} is either the {V} or the {W}.", "{P} — {V} или {W}."),
      Words("Who is the {V}?", "Кто из них {V}?"),
      Vector(Thing("doctor", "doctor", "врач", "врач"), Thing("teacher", "teacher", "учитель", "учитель"),
        Thing("engineer", "engineer", "инженер", "инженер"), Thing("cook", "cook", "повар", "повар"),
        Thing("driver", "driver", "водитель", "водитель"), Thing("pilot", "pilot", "пилот", "пилот"))))

  /**
   * THE THEMES OF THE CORPUS (`Drawn`): the collection's four and more — other people, other things, other ways to
   * say that someone has a thing or has not: a verb, "from", a possessive, the thing said first (§11)
   */
  private val drawnThemes = themes ++ Vector(
    Theme(Words("Pets", "Питомцы"),
      Words("{P} each keep a different pet: {L}.", "{P} держат разных животных: {L}."),
      Words("{P} does not keep the {V}.", "{P} держит не {V}."),
      Words("{P} keeps either the {V} or the {W}.", "{P} держит {V} или {W}."),
      Words("Who keeps the {V}?", "Кто держит {V}?"),
      Vector(Thing("hamster", "hamster", "хомяк", "хомяка"), Thing("parrot", "parrot", "попугай", "попугая"),
        Thing("turtle", "turtle", "черепаха", "черепаху"), Thing("rabbit", "rabbit", "кролик", "кролика"),
        Thing("canary", "canary", "канарейка", "канарейку"), Thing("hedgehog", "hedgehog", "ёж", "ежа")),
      Settings.queue.people),
    Theme(Words("Instruments", "Инструменты"),
      Words("{P} each play a different instrument: {L}.", "{P} играют на разных инструментах: {L}."),
      Words("{P} does not play the {V}.", "{P} не играет на {V}."),
      Words("{P} plays either the {V} or the {W}.", "{P} играет на {V} или на {W}."),
      Words("Who plays the {V}?", "Кто играет на {V}?"),
      Vector(Thing("trumpet", "trumpet", "труба", "трубе"), Thing("cello", "cello", "виолончель", "виолончели"),
        Thing("banjo", "banjo", "банджо", "банджо"), Thing("organ", "organ", "орган", "органе"),
        Thing("accordion", "accordion", "аккордеон", "аккордеоне"),
        Thing("clarinet", "clarinet", "кларнет", "кларнете")),
      Settings.offices.people),
    Theme(Words("Countries", "Страны"),
      Words("{P} come from different countries: {L}.", "{P} приехали из разных стран: {L}."),
      Words("{P} is not from {V}.", "{P} — не из {V}."),
      Words("{P} is from either {V} or {W}.", "{P} — из {V} или из {W}."),
      Words("Who is from {V}?", "Кто из них из {V}?"),
      Vector(Thing("Greece", "Greece", "Греция", "Греции"), Thing("Brazil", "Brazil", "Бразилия", "Бразилии"),
        Thing("Egypt", "Egypt", "Египет", "Египта"), Thing("India", "India", "Индия", "Индии"),
        Thing("Mexico", "Mexico", "Мексика", "Мексики"), Thing("New Zealand", "New Zealand", "Новая Зеландия",
          "Новой Зеландии")),
      Settings.ships.people),
    Theme(Words("Subjects", "Предметы"),
      Words("{P} each like a different school subject best: {L}.", "{P} любят разные школьные предметы: {L}."),
      Words("{P}'s favourite subject is not {V}.", "Любимый предмет {Pof} — не {V}."),
      Words("{P}'s favourite subject is either {V} or {W}.", "Любимый предмет {Pof} — {V} или {W}."),
      Words("Whose favourite subject is {V}?", "Чей любимый предмет — {V}?"),
      Vector(Thing("physics", "physics", "физика", "физика"), Thing("history", "history", "история", "история"),
        Thing("geography", "geography", "география", "география"),
        Thing("chemistry", "chemistry", "химия", "химия"), Thing("biology", "biology", "биология", "биология"),
        Thing("music", "music", "музыка", "музыка")),
      Settings.garden.people),
    Theme(Words("Costumes", "Костюмы"),
      Words("{P} came to a party dressed as different animals: {L}.",
        "{P} пришли на праздник в костюмах разных зверей: {L}."),
      Words("{P} was not dressed as the {V}.", "{P} — не в костюме {V}."),
      Words("{P} was dressed as either the {V} or the {W}.", "{P} — в костюме {V} или {W}."),
      Words("Who was dressed as the {V}?", "Кто в костюме {V}?"),
      Vector(Thing("fox", "fox", "лиса", "лисы"), Thing("bear", "bear", "медведь", "медведя"),
        Thing("wolf", "wolf", "волк", "волка"), Thing("hare", "hare", "заяц", "зайца"),
        Thing("owl", "owl", "сова", "совы"), Thing("polar bear", "polar bear", "белый медведь", "белого медведя")),
      Settings.cinema.people),
    Theme(Words("Drinks", "Напитки"),
      Words("{P} each drink something different: {L}.", "{P} пьют разные напитки: {L}."),
      Words("{P} does not drink {V}.", "{P} не пьёт {V}."),
      Words("{P} drinks either {V} or {W}.", "{P} пьёт {V} или {W}."),
      Words("Who drinks {V}?", "Кто пьёт {V}?"),
      Vector(Thing("lemonade", "lemonade", "лимонад", "лимонад"), Thing("kvass", "kvass", "квас", "квас"),
        Thing("kefir", "kefir", "кефир", "кефир"), Thing("compote", "compote", "компот", "компот"),
        Thing("mineral water", "mineral water", "минеральная вода", "минеральную воду"),
        Thing("green tea", "green tea", "зелёный чай", "зелёный чай")),
      Settings.train.people),
    Theme(Words("Duty days", "Дежурства"),
      Words("{P} are on duty on different days: {L}.", "{P} дежурят в разные дни: {L}."),
      Words("{P} is not on duty on {V}.", "{P} дежурит не в {V}."),
      Words("{P} is on duty on either {V} or {W}.", "{P} дежурит в {V} или в {W}."),
      Words("Who is on duty on {V}?", "Кто дежурит в {V}?"),
      Vector(Thing("Monday", "Monday", "понедельник", "понедельник"), Thing("Wednesday", "Wednesday", "среда", "среду"),
        Thing("Thursday", "Thursday", "четверг", "четверг"), Thing("Friday", "Friday", "пятница", "пятницу"),
        Thing("Saturday", "Saturday", "суббота", "субботу"),
        Thing("Sunday", "Sunday", "воскресенье", "воскресенье")),
      Settings.ranking.people),
    Theme(Words("Team roles", "Роли в команде"),
      Words("{P} play in one team in different roles: {L}.", "{P} играют в одной команде на разных позициях: {L}."),
      Words("On this team the {V} is not {P}.", "В этой команде {V} — не {P}."),
      Words("{P} is the {V} or the {W}.", "{P} — либо {V}, либо {W}."),
      Words("Which of them is the {V}?", "Кто же {V}?"),
      Vector(Thing("goalkeeper", "goalkeeper", "вратарь", "вратарь"),
        Thing("defender", "defender", "защитник", "защитник"), Thing("striker", "striker", "нападающий", "нападающий"),
        Thing("captain", "captain", "капитан", "капитан"), Thing("coach", "coach", "тренер", "тренер"),
        Thing("substitute", "substitute", "запасной", "запасной")),
      Settings.queue.people.reverse))

  private def list(xs: Vector[String], and: String): String =
    if xs.size < 2 then xs.mkString else s"${xs.init.mkString(", ")} $and ${xs.last}"

  private enum Clue:
    case Not(p: Who, v: Thing)
    case Either(p: Who, v: Thing, w: Thing)

  /** people and things, one each: clues of what is not so, a question of who has a thing */
  private[tasks] def assign(theme: Theme, n: Int, seed: Long, variant: Int): Task =
    val rnd = scala.util.Random(seed)
    val people = rnd.shuffle(theme.people).take(n).sortBy(_.key)
    val things = rnd.shuffle(theme.things).take(n)
    val has = people.zip(rnd.shuffle(things)).toMap
    val nots = for p <- people; v <- things if has(p) != v yield Clue.Not(p, v)
    val eithers = people.map { p =>
      val other = rnd.shuffle(things.filter(_ != has(p))).head
      if rnd.nextBoolean() then Clue.Either(p, has(p), other) else Clue.Either(p, other, has(p))
    }
    def fits(m: Map[Who, Thing], c: Clue): Boolean = c match
      case Clue.Not(p, v) => m(p) != v
      case Clue.Either(p, v, w) => m(p) == v || m(p) == w
    val all = things.permutations.map(people.zip(_).toMap).toVector
    def unique(cs: Vector[Clue]): Boolean = all.count(m => cs.forall(fits(m, _))) == 1
    var kept: Vector[Clue] = rnd.shuffle(nots) ++ rnd.shuffle(eithers).take(n / 2)
    for c <- rnd.shuffle(kept) do
      val without = kept.filterNot(_ == c)
      if unique(without) then kept = without
    val clues = rnd.shuffle(kept)
    val asked = rnd.shuffle(things).head
    val answer = people.find(has(_) == asked).get
    // every person and every thing is marked where it is said: the text carries its statement (`Writing`)
    def said(c: Clue): (Words, Con) = c match
      case Clue.Not(p, v) => (fill(theme.not, p, v, v), Con.Differ)
      case Clue.Either(p, v, w) => (fill(theme.either, p, v, w), Con.Either)
    def fill(t: Words, p: Who, v: Thing, w: Thing): Words = Words(
      t.en.replace("{P}", mark(p.key, p.en)).replace("{V}", mark(v.key, v.en)).replace("{W}", mark(w.key, w.en)),
      t.ru.replace("{Pof}", mark(p.key, p.ruOf)).replace("{P}", mark(p.key, p.ru))
        .replace("{V}", mark(v.key, v.ruSaid)).replace("{W}", mark(w.key, w.ruSaid)))
    val intro = Words(
      theme.intro.en.replace("{P}", list(people.map(p => mark(p.key, p.en)), "and"))
        .replace("{L}", things.map(x => mark(x.key, x.en)).mkString(", ")),
      theme.intro.ru.replace("{P}", list(people.map(p => mark(p.key, p.ru)), "и"))
        .replace("{L}", things.map(x => mark(x.key, x.ru)).mkString(", ")))
    val question = Words(theme.ask.en.replace("{V}", mark(asked.key, asked.en)),
      theme.ask.ru.replace("{V}", mark(asked.key, asked.ruSaid)))
    def value(x: Thing): Json = t(x.key)
    val where = op("distinct", people.map(p => v(p.key))*) +: clues.map {
      case Clue.Not(p, x) => op("!=", v(p.key), value(x))
      case Clue.Either(p, x, y) => op("or", is(v(p.key), value(x)), is(v(p.key), value(y)))
    }
    // the statement: the people and the things are two attributes, the question asks for the first
    val parts = (intro, Vector(Writing.list(Con.Attribute, n), Writing.list(Con.Attribute, n))) +:
      clues.map(said).map((w, con) => (Words(" " + w.en, " " + w.ru), Vector(Writing.say(con)))) :+
      (Words(" " + question.en, " " + question.ru), Vector(Writing.ask(0)))
    Task.written(Kind.Matching, Words(s"${theme.title.en}: $n people, variant $variant",
      s"${theme.title.ru}: $n ${if n < 5 then "человека" else "человек"}, вариант $variant"),
      Problem.Assign, parts, Words(answer.en, answer.ru),
      finite(people.map(p => p.key -> texts(things.map(_.key)*)), where,
        "answer" -> which(people.map(p => p.key -> is(v(p.key), value(asked)))*)),
      Vector(answer.key))

  /** a comparison: "taller" and its opposite, and the questions of the two ends */
  private[tasks] final case class Scale(title: Words, more: Words, less: Words, top: Words, bottom: Words)
  private val scales = Vector(
    Scale(Words("Who is taller", "Кто выше"), Words("taller", "выше"), Words("shorter", "ниже"),
      Words("Who is the tallest?", "Кто выше всех?"), Words("Who is the shortest?", "Кто ниже всех?")),
    Scale(Words("Who is older", "Кто старше"), Words("older", "старше"), Words("younger", "младше"),
      Words("Who is the oldest?", "Кто старше всех?"), Words("Who is the youngest?", "Кто младше всех?")),
    Scale(Words("Who is faster", "Кто быстрее"), Words("faster", "быстрее"), Words("slower", "медленнее"),
      Words("Who is the fastest?", "Кто быстрее всех?"), Words("Who is the slowest?", "Кто медленнее всех?")),
    Scale(Words("Who is heavier", "Кто тяжелее"), Words("heavier", "тяжелее"), Words("lighter", "легче"),
      Words("Who is the heaviest?", "Кто тяжелее всех?"), Words("Who is the lightest?", "Кто легче всех?")))

  /**
   * people in a hidden order, told by comparisons of neighbours in it. `ask`: 0 the top, 1 the bottom, 2 whether one
   * is above another, 3 the bottom when the comparisons do not decide it
   */
  private[tasks] def order(scale: Scale, n: Int, ask: Int, seed: Long): Task =
    val rnd = scala.util.Random(seed)
    val line = rnd.shuffle(Grids.race.people).take(n) // from the top down
    val pairs = if ask == 3 then line.tail.map(line.head -> _) else line.zip(line.tail)
    // the statement: the scale is a line of positions, "more" is `After` and "less" is `Before` on it
    def said(a: Who, b: Who): (Words, Con) =
      if rnd.nextBoolean() then
        (Words(s"${mark(a.key, a.en)} is ${scale.more.en} than ${mark(b.key, b.en)}.",
          s"${mark(a.key, a.ru)} ${scale.more.ru} ${mark(b.key, b.ruOf)}."), Con.After)
      else (Words(s"${mark(b.key, b.en)} is ${scale.less.en} than ${mark(a.key, a.en)}.",
        s"${mark(b.key, b.ru)} ${scale.less.ru} ${mark(a.key, a.ruOf)}."), Con.Before)
    val clues = rnd.shuffle(pairs)
    val names = line.sortBy(_.key)
    val where = op("distinct", names.map(p => v(p.key))*) +: clues.map((a, b) => op(">", v(a.key), v(b.key)))
    def who(rank: Int): Json = which(names.map(p => p.key -> is(v(p.key), Gold.n(rank)))*)
    val (question, asks, goal, expect, answer) = ask match
      case 0 => (scale.top, Vector(Con.Last), who(n), line.head.key, Words(line.head.en, line.head.ru))
      case 1 => (scale.bottom, Vector(Con.First), who(1), line.last.key, Words(line.last.en, line.last.ru))
      case 2 =>
        val i = rnd.nextInt(n - 2)
        val (a, b) = (line(i), line(i + 2 + rnd.nextInt(n - i - 2)))
        val (x, y) = if rnd.nextBoolean() then (a, b) else (b, a)
        (Words(s"Is ${mark(x.key, x.en)} ${scale.more.en} than ${mark(y.key, y.en)}?",
          s"Правда ли, что ${mark(x.key, x.ru)} ${scale.more.ru} ${mark(y.key, y.ruOf)}?"),
          Vector(Con.Whether, Con.After), op(">", v(x.key), v(y.key)), (x == a).toString,
          if x == a then Words("Yes", "Да") else Words("No", "Нет"))
      case _ =>
        val rest = line.tail.sortBy(_.key)
        (scale.bottom, Vector(Con.First), who(1), rest.map(_.key).mkString("|"),
          Words(s"It cannot be decided: ${list(rest.map(_.en), "or")}",
            s"Определить нельзя: ${list(rest.map(_.ru), "или")}"))
    val texts = clues.map(said(_, _))
    val open = if ask == 3 then Words(", not decided", ", не решается") else Words("", "")
    val parts = texts.zipWithIndex.map { case ((w, con), i) =>
      val gap = if i == 0 then "" else " "
      (Words(gap + w.en, gap + w.ru), Vector(Writing.say(con))) } :+
      (Words(" " + question.en, " " + question.ru), asks.map(Writing.say(_)))
    Task.written(Kind.Order, Words(s"${scale.title.en}: $n people${open.en}",
      s"${scale.title.ru}: $n ${if n < 5 then "человека" else "человек"}${open.ru}"),
      Problem.Assign, parts, answer, finite(names.map(p => p.key -> ints(1, n)), where, "answer" -> goal),
      Vector(expect))

  private[tasks] def scale(more: Words, less: Words, top: String, bottom: String): Scale =
    Scale(Words(s"Who is ${more.en}", s"Кто ${more.ru}"), more, less,
      Words(s"Who is the $top?", s"Кто ${more.ru} всех?"), Words(s"Who is the $bottom?", s"Кто ${less.ru} всех?"))

  /**
   * THE SCALES OF THE CORPUS (`Drawn`): the collection's four and more of the same shape — a reader that met three
   * pairs of words learns the words; which of a pair is "more" is learnt from many (specs/formalization.md §10)
   */
  private val drawnScales = scales ++ Vector(
    scale(Words("stronger", "сильнее"), Words("weaker", "слабее"), "strongest", "weakest"),
    scale(Words("richer", "богаче"), Words("poorer", "беднее"), "richest", "poorest"),
    scale(Words("louder", "громче"), Words("quieter", "тише"), "loudest", "quietest"),
    scale(Words("bigger", "крупнее"), Words("smaller", "мельче"), "biggest", "smallest"),
    scale(Words("happier", "счастливее"), Words("sadder", "несчастнее"), "happiest", "saddest"),
    scale(Words("kinder", "добрее"), Words("meaner", "злее"), "kindest", "meanest"),
    scale(Words("luckier", "удачливее"), Words("unluckier", "неудачливее"), "luckiest", "unluckiest"),
    scale(Words("neater", "аккуратнее"), Words("messier", "неряшливее"), "neatest", "messiest"),
    scale(Words("braver", "смелее"), Words("more cowardly", "трусливее"), "bravest", "most cowardly"),
    scale(Words("more experienced", "опытнее"), Words("less experienced", "неопытнее"), "most experienced",
      "least experienced"),
    scale(Words("more patient", "терпеливее"), Words("less patient", "нетерпеливее"), "most patient",
      "least patient"),
    scale(Words("wiser", "мудрее"), Words("more foolish", "глупее"), "wisest", "most foolish"),
    scale(Words("nimbler", "ловчее"), Words("clumsier", "неуклюжее"), "nimblest", "clumsiest"),
    scale(Words("calmer", "спокойнее"), Words("more nervous", "нервнее"), "calmest", "most nervous"),
    scale(Words("funnier", "смешнее"), Words("more serious", "серьёзнее"), "funniest", "most serious"),
    scale(Words("more generous", "щедрее"), Words("stingier", "скупее"), "most generous", "stingiest"),
    scale(Words("more talkative", "разговорчивее"), Words("more reserved", "сдержаннее"), "most talkative",
      "most reserved"),
    scale(Words("more hard-working", "трудолюбивее"), Words("lazier", "ленивее"), "most hard-working", "laziest"),
    scale(Words("politer", "вежливее"), Words("ruder", "грубее"), "politest", "rudest"),
    scale(Words("more careful", "осторожнее"), Words("more careless", "беспечнее"), "most careful",
      "most careless"),
    scale(Words("hungrier", "голоднее"), Words("fuller", "сытее"), "hungriest", "fullest"))

  /** the themes and the scales by number, for the corpus (`Drawn`): a task of one of them by its seed */
  val (themeCount, scaleCount) = (drawnThemes.size, drawnScales.size)
  /** the theme no training has: the last of the collection's */
  val heldTheme: Int = themes.size - 1
  /** the scales kept out of training: one in four of the first eighteen, so that Unseen stays what it was */
  def heldScale(i: Int): Boolean = i % 4 == 3 && i < 17
  def matching(theme: Int, n: Int, seed: Long): Task = assign(drawnThemes(theme), n, seed, 1)
  def comparison(scale: Int, n: Int, ask: Int, seed: Long): Task = order(drawnScales(scale), n, ask, seed)

  val entries: Vector[Entry] =
    (for
      (theme, i) <- themes.zipWithIndex
      (n, k) <- Vector(3, 4, 4, 5).zipWithIndex
    yield Entry(Kind.Matching, assign(theme, n, 4000 + i * 10 + k, k + 1))) ++
    (for
      (scale, i) <- scales.zipWithIndex
      (n, ask) <- Vector((3, 0), (4, 1), (5, 2), (6, 0), (3, 3))
    yield Entry(Kind.Order, order(scale, n, ask, 5000 + i * 10 + ask + n)))
