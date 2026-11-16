package okay.desk.tasks

/**
 * SOMEONE OF A SOLVED GRID, IN WORDS (the operator, 2026-11-16): a question about one of the grid's things ("What does
 * the Japanese do?", «Что делает японец?») finds their line — by the puzzle vocabulary's forms in either language, so
 * «японец» names "Japanese" — and says it as a sentence: "The Japanese lives in the green house, keeps a zebra, drinks
 * coffee, smokes Parliaments", «Японец живёт в зелёном доме, держит зебру, пьёт кофе, курит Parliaments». A value the
 * vocabulary does not know is said as it is.
 */
object Someone:
  /** how a value is said: as the subject (a person), or as what the subject does, in English and in Russian */
  private final case class Said(person: Boolean, en: String, ru: String)

  private val settings = Vector(Grids.houses, Grids.floors, Grids.race)

  /** the classic zebra's values the drawn settings lack: (value, English, Russian), by the verb they go with */
  private val classic: Vector[(String, String, Vector[(String, String, String)])] = Vector(
    ("lives in", "живёт в", Vector(("ivory", "the ivory house", "белом доме"))),
    ("keeps", "держит", Vector(("snails", "snails", "улиток"), ("fox", "a fox", "лису"), ("birds", "birds", "птиц"))),
    ("drinks", "пьёт", Vector(("orange juice", "orange juice", "апельсиновый сок"), ("beer", "beer", "пиво"))),
    ("smokes", "курит", Vector("Old Gold", "Kools", "Chesterfields", "Lucky Strike", "Parliaments", "Pall Mall",
      "Dunhill", "Blend", "Blue Master", "Prince").map(b => (b, b, b))))

  private val said: Map[String, Said] =
    val drawn = settings.flatMap { s =>
      s.people.map(w => w.key -> Said(true, w.en, w.ru)) ++
        s.features.flatMap(f => f.items.map(i => i.key -> Said(false, s"${f.verb} ${i.en}", s"${f.ruVerb} ${i.ru}"))) }
    val more = classic.flatMap((verb, ruVerb, vs) =>
      vs.map((v, en, ru) => v -> Said(false, s"$verb $en", s"$ruVerb $ru")))
    // the first setting that says a value says it (a colour is a house's in Einstein's puzzle, not a shirt's)
    (drawn ++ more).reverse.toMap

  /** every form of a value, lower case: its own name, its Russian forms (the vocabulary's and the settings') */
  private val forms: Vector[(String, String)] =
    val vocabulary = okay.desk.puzzle.GridText.vocabulary.flatMap(_._2)
      .flatMap(w => (w.value +: w.forms).map(_ -> w.value))
    val drawn = settings.flatMap(s => s.people.flatMap(w => Vector(w.key, w.ru, w.ruOf).map(_ -> w.key)) ++
      s.features.flatMap(_.items.flatMap(i => Vector(i.key, i.ru, i.name.ru).map(_ -> i.key))))
    (vocabulary ++ drawn).map((f, v) => f.toLowerCase -> v).distinct

  private def wordsOf(text: String): Vector[String] = "(?U)\\w+".r.findAllIn(text.toLowerCase).toVector

  /** one word a form of the other: the same but for its last letter or two («японец», «японцу»; «зебра», «зебре») */
  private def stem(w: String, f: String): Boolean =
    val n = math.max(4, math.min(w.length, f.length) - 1)
    w.length >= 4 && math.abs(w.length - f.length) <= 2 && w.take(n) == f.take(n)

  /** the values a question names: a form said whole, or a word sharing its stem
   * («японцу» is "Japanese"); and the value's own name said in the question (any language: its stem) */
  def named(q: String): Set[String] =
    val words = wordsOf(q)
    val t = " " + words.mkString(" ") + " "
    forms.collect { case (f, v) if t.contains(" " + wordsOf(f).mkString(" ") + " ") ||
        wordsOf(f).size == 1 && f.length >= 4 && words.exists(w => stem(w, f)) => v }
      .toSet

  private val stop = Set("what", "who", "does", "do", "did", "the", "a", "an", "is", "of", "about", "tell", "me",
    "and", "что", "кто", "делает", "про", "о", "об", "расскажи", "чем", "занимается", "известно", "такой", "такая")

  /** a grid line's values: "5: Japanese, green, zebra, coffee, Parliaments" */
  private def values(line: String): Vector[String] =
    line.dropWhile(_ != ':').drop(1).split(",").toVector.map(_.trim).filter(_.nonEmpty)

  /** the lines of the solved grid the question is about, each said as a sentence; none: it names no one in it */
  def about(lines: Vector[String], q: String, ru: Boolean): Vector[String] =
    val known = named(q)
    // a name of the question's language the vocabulary lacks: by its stem, as before
    val stems = wordsOf(q).filter(w => w.length > 2 && !stop(w)).map(w => w.take(math.max(4, math.min(w.length, 5))))
    lines.filter { l =>
      val vs = values(l)
      vs.exists(known) || vs.exists(v => stems.exists(s => v.toLowerCase.startsWith(s))) }
      .map(l => sentence(values(l), ru))

  /** the subject — the line's person, else its first value — and what it does, each value its verb */
  def sentence(vs: Vector[String], ru: Boolean): String =
    val subject = vs.find(v => said.get(v).exists(_.person)).getOrElse(vs.head)
    def name(v: String): String = said.get(v).fold(v)(s => if ru then s.ru else s.en)
    val rest = vs.filter(_ != subject).map(v => said.get(v).filterNot(_.person).fold(v)(s => if ru then s.ru else s.en))
    val head = name(subject)
    head.head.toUpper.toString + head.tail + (if rest.isEmpty then "" else " " + rest.mkString(", "))
