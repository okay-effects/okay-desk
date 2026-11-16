package okay.desk

/**
 * A TABLE'S VALUES NAMED BY A QUESTION, IN ANY OF ITS LANGUAGES (specs/frames.md §6.3; the lesson of okay-inlay's
 * units, specs/domains.md §5: what the text names exactly is a fact, not a reading — the model decides its role).
 * «Сколько заказов из Львова?» names `city = Lviv` of a table written in English: the value said as it is
 * ("Biedronka"), by its letters written in Latin («Анны» is "Anna", «Ольги» "Olga"), or by a common word of everyday
 * data in Russian, Ukrainian or Polish («продукты» is "groceries", «Львов» "Lviv"). The frame reader takes a link as
 * a fact: the lines naming a linked value weigh more (`FrameReading`).
 */
object Links:
  /** Cyrillic written in Latin, the plain way (Russian and Ukrainian letters) */
  private val latin: Map[Char, String] = Map(
    'а' -> "a", 'б' -> "b", 'в' -> "v", 'г' -> "g", 'ґ' -> "g", 'д' -> "d", 'е' -> "e", 'є' -> "ye", 'ё' -> "e",
    'ж' -> "zh", 'з' -> "z", 'и' -> "i", 'і' -> "i", 'ї' -> "yi", 'й' -> "y", 'к' -> "k", 'л' -> "l", 'м' -> "m",
    'н' -> "n", 'о' -> "o", 'п' -> "p", 'р' -> "r", 'с' -> "s", 'т' -> "t", 'у' -> "u", 'ф' -> "f", 'х' -> "kh",
    'ц' -> "ts", 'ч' -> "ch", 'ш' -> "sh", 'щ' -> "shch", 'ъ' -> "", 'ы' -> "y", 'ь' -> "", 'э' -> "e", 'ю' -> "yu",
    'я' -> "ya", '\'' -> "", 'ʼ' -> "")
  def inLatin(word: String): String = word.toLowerCase.map(c => latin.getOrElse(c, c.toString)).mkString

  /**
   * everyday data's common words: the English value, its stems in Russian, Ukrainian and Polish. Not a measure's
   * words alone — kinds of spending, places, payment, directions, school subjects, work, states of a thing
   */
  private val words: Vector[(String, Vector[String])] = Vector(
    // spending
    "groceries" -> Vector("продукт", "продукт", "бакале", "spożyw", "zakup"), "food" -> Vector("еда", "еды", "еду",
      "питан", "їж", "харч", "jedzen", "żywnoś"), "transport" -> Vector("транспорт", "проезд", "проїзд", "dojazd"),
    "restaurants" -> Vector("ресторан", "кафе", "restaurac"), "utilities" -> Vector("коммунал", "комунал",
      "rachunk", "media"), "health" -> Vector("здоров", "медицин", "лекарств", "лік", "zdrow", "lekarst"),
    "entertainment" -> Vector("развлечен", "розваг", "rozrywk"), "rent" -> Vector("аренд", "оренд", "квартплат",
      "czynsz", "najem"), "salary" -> Vector("зарплат", "зарплатн", "pensj", "wypłat"), "refund" -> Vector("возврат",
      "повернен", "zwrot"), "transfer" -> Vector("перевод", "переказ", "przelew"), "household" -> Vector("хозяйств",
      "бытов", "побутов", "господар", "gospodar", "chemi"), "electronics" -> Vector("электрон", "електрон",
      "elektron"), "office" -> Vector("канцеляр", "офис", "офіс", "biur"), "clothes" -> Vector("одежд", "одяг",
      "ubran", "odzież"), "pharmacy" -> Vector("аптек", "aptek"), "clinic" -> Vector("клиник", "клінік", "поликлиник",
      "klinik", "przychodn"), "cinema" -> Vector("кино", "кіно", "kino"),
    // payment and states
    "cash" -> Vector("наличн", "налич", "готівк", "gotówk"), "card" -> Vector("карт", "karta", "kartą", "kart"),
    "paid" -> Vector("оплачен", "оплачен", "сплачен", "opłacon", "zapłacon"), "unpaid" -> Vector("неоплачен",
      "несплачен", "nieopłacon", "niezapłacon"), "income" -> Vector("доход", "приход", "дохід", "przychod",
      "dochod"), "expense" -> Vector("расход", "витрат", "wydat"), "open" -> Vector("открыт", "відкрит", "otwart"),
    "closed" -> Vector("закрыт", "закрит", "zamknięt"),
    "in" -> Vector("income", "earn", "доход", "приход", "поступлен", "заработ", "дохід", "надходжен", "przych",
      "dochod", "wpływ", "zarob"), "out" -> Vector("spend", "spent", "payment", "потрат", "трат", "расход", "платёж",
      "платеж", "витрат", "платіж", "wydat", "płatnoś"),
    // places and directions
    "north" -> Vector("север", "північ", "północ"), "south" -> Vector("юг", "юге", "юга", "південь", "півдн",
      "południ"), "east" -> Vector("восток", "схід", "сход", "wschod", "wschód"), "west" -> Vector("запад", "захід",
      "заход", "zachod", "zachód"), "center" -> Vector("центр", "centrum"), "kyiv" -> Vector("киев", "київ", "кийов",
      "kijów", "kijow"), "lviv" -> Vector("львов", "львів", "lwów", "lwow"), "kharkiv" -> Vector("харьков", "харків",
      "charków"), "odesa" -> Vector("одесс", "одес", "odess"), "warsaw" -> Vector("варшав", "warszaw"),
    "krakow" -> Vector("краков", "краків", "kraków", "krakow"), "poland" -> Vector("польш", "польщ", "polsk"),
    "ukraine" -> Vector("украин", "україн", "ukrain"), "germany" -> Vector("германи", "німеччин", "niemc"),
    // school
    "math" -> Vector("математик", "алгебр", "matematyk"), "physics" -> Vector("физик", "фізик", "fizyk"),
    "history" -> Vector("истори", "історі", "histori"), "english" -> Vector("английск", "англійськ", "angielsk"),
    "chemistry" -> Vector("хими", "хімі", "chemi"), "biology" -> Vector("биологи", "біологі", "biologi"),
    // work
    "cashier" -> Vector("кассир", "касир", "kasjer"), "manager" -> Vector("менеджер", "управляющ", "kierowni",
      "menedżer"), "stock" -> Vector("склад", "magazyn"), "night" -> Vector("ночн", "ночь", "ночі", "нічн", "nocn",
      "noc"), "day" -> Vector("дневн", "денн", "dzienn"), "morning" -> Vector("утрен", "ранков", "поранк",
      "porann"), "evening" -> Vector("вечерн", "вечірн", "wieczorn"),
    // goods
    "coffee" -> Vector("кофе", "кава", "кави", "каву", "kaw"), "tea" -> Vector("чай", "чаю", "herbat"),
    "mug" -> Vector("кружк", "чашк", "горнят", "kubek", "kubk"), "beans" -> Vector("зерн", "ziarn"),
    "grinder" -> Vector("кофемолк", "кавомолк", "młynek", "młynk"), "milk" -> Vector("молок", "mlek"),
    "bread" -> Vector("хлеб", "хліб", "chleb"), "water" -> Vector("вод", "wod"))

  private def wordsOf(text: String): Vector[String] = "(?U)\\w[\\w'ʼ]*".r.findAllIn(text.toLowerCase).toVector

  /** two words the same but for an ending: a shared beginning of all but the shorter one's last letter, `least`
   * letters at least ("Olga's", «Анны» in Latin "anny" for "Anna"; not "many" for "manager") */
  private def same(a: String, b: String, least: Int): Boolean =
    val n = math.max(least, math.min(a.length, b.length) - 1)
    a.length >= least && b.length >= least && a.take(n) == b.take(n)

  /** whether a question names a value: its own words, its letters in Latin, or a common word for it */
  def names(question: String, value: String): Boolean =
    val ws = wordsOf(question)
    val vs = wordsOf(value)
    val own = vs.nonEmpty && vs.forall(v => v.length >= 3 && ws.exists(w => w == v || same(w, v, 4)))
    val lat = vs.nonEmpty && vs.forall(v =>
      v.length >= 3 && ws.exists(w => w.exists(_ > 'z') && same(inLatin(w), v, 3)))
    val common = vs.headOption.flatMap(v => words.find(_._1 == v)).exists((_, stems) =>
      ws.exists(w => stems.exists(s => w == s || s.length >= 3 && w.startsWith(s))))
    own || lat || common

  /** the text values of a table a question names: (column, value). Three of a column or more are a list the question
   * describes ('(meetup, conference, concert, workshop)'), not a value it means: none of them */
  def of(t: Table, question: String): Vector[(String, String)] = t.columns.flatMap { c =>
    val vs = t.rows.map(_(t.columns.indexOf(c))).filter(_.num.isEmpty).map(_.text).filter(_.nonEmpty).distinct
    if vs.size > 40 || vs.exists(_.matches("\\d{4}-\\d{2}-\\d{2}.*")) then Vector.empty
    else
      val named = vs.filter(names(question, _))
      if named.size >= 3 then Vector.empty else named.map(c -> _) }

  /** the value a frame's condition line names, with its column: "where: city = Lviv" is (city, Lviv) */
  def named(line: String): Option[(String, String)] =
    val body = line.dropWhile(_ != ':').drop(1).trim
    "^(\\S+) (=|!=) (.+)$".r.findFirstMatchIn(body).map(m => m.group(1) -> m.group(3))
