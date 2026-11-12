package okay.desk.tasks

import Task.{no, noted, of, says, yes, Noted}
import okay.desk.statement.{Con, Problem, Writing}
import Writing.say

/**
 * FAMILIES, ROUTES AND DEPENDENCIES: facts about pairs and what follows from them by rules — a grandmother, a cousin,
 * every ancestor; where one can get to, what must come first, what goes round in a circle. Each is a Datalog program
 * (the facts of the text and the rules of the relation asked for) with a goal. The tasks of ways — what leads to
 * what, and what is reached — are written with their statements (`okay.desk.statement`); a grandmother or a cousin
 * is a relation of its own, which a statement of ways does not say.
 */
object Relations:
  private val names = Map("anna" -> "Анна", "gleb" -> "Глеб", "boris" -> "Борис", "vera" -> "Вера", "olga" -> "Ольга",
    "dina" -> "Дина", "max" -> "Макс", "egor" -> "Егор", "nina" -> "Нина", "roman" -> "Роман")
  private def people(keys: String*): (String, String) =
    (keys.map(_.capitalize).mkString(", "), keys.map(names).mkString(", "))

  private val (edge, link, whether, path) = (say(Con.Edge), say(Con.Link), say(Con.Whether), say(Con.Path))
  private val (from, to) = (say(Con.From), say(Con.To))
  /** the sentences as a person reads them, the marks taken out */
  private def plain(sentences: Vector[Noted]): (String, String) =
    (sentences.map(s => Writing.plain(Writing.noted(s._1))).mkString(" "),
      sentences.map(s => Writing.plain(Writing.noted(s._2))).mkString(" "))
  /** a task of ways: the facts said, then the question — every sentence with its frames */
  private def ways(kind: Kind, title: (String, String), said: Vector[Noted], answer: (String, String), gold: Gold,
      expect: String*)(ask: Noted): Task = noted(kind, title, Problem.Derive, said :+ ask, answer, gold, expect*)

  // a parent leads to a child: the statement's `Edge`
  private val familySaid = Vector(
    says("[anna|Anna] and [gleb|Gleb] have two children: [boris|Boris] and [vera|Vera].",
      "У [anna|Анны] и [gleb|Глеба] двое детей: [boris|Борис] и [vera|Вера].",
      say(Con.Edge, 0, 2), say(Con.Edge, 0, 3), say(Con.Edge, 1, 2), say(Con.Edge, 1, 3)),
    says("[boris|Boris] and [olga|Olga] have a daughter, [dina|Dina].",
      "У [boris|Бориса] и [olga|Ольги] есть дочь [dina|Дина].", say(Con.Edge, 0, 2), say(Con.Edge, 1, 2)),
    says("[vera|Vera] and [max|Max] have a son, [egor|Egor], and a daughter, [nina|Nina].",
      "У [vera|Веры] и [max|Макса] есть сын [egor|Егор] и дочь [nina|Нина].",
      say(Con.Edge, 0, 2), say(Con.Edge, 0, 3), say(Con.Edge, 1, 2), say(Con.Edge, 1, 3)),
    says("[dina|Dina] has a son, [roman|Roman].", "У [dina|Дины] есть сын [roman|Роман].", edge))
  private val family = plain(familySaid)
  private val facts = """parent(anna, boris). parent(anna, vera). parent(gleb, boris). parent(gleb, vera).
    |parent(boris, dina). parent(olga, dina). parent(vera, egor). parent(vera, nina).
    |parent(max, egor). parent(max, nina). parent(dina, roman).
    |female(anna). female(vera). female(olga). female(dina). female(nina).
    |male(gleb). male(boris). male(max). male(egor). male(roman).""".stripMargin
  private val sibling = "sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y."
  private val ancestor = "ancestor(X, Y) :- parent(X, Y). ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z)."

  private def kin(title: (String, String), ask: (String, String), answer: (String, String), rules: String,
      goal: String, expect: String*): Task =
    of(Kind.Kinship, title, (s"${family._1} ${ask._1}", s"${family._2} ${ask._2}"), answer,
      Gold.Rules(s"$facts\n$rules", goal), expect*)
  private def kinOf(title: (String, String), ask: (String, String), rules: String, goal: String, found: String*): Task =
    kin(title, ask, people(found*), rules, goal, found.sorted*)
  /** a question of the family that is one of ways: an ancestor is whoever leads here, by any number of steps */
  private def kinWays(title: (String, String), answer: (String, String), rules: String, goal: String,
      expect: String*)(ask: Noted): Task =
    ways(Kind.Kinship, title, familySaid, answer, Gold.Rules(s"$facts\n$rules", goal), expect*)(ask)

  val kinship: Vector[Task] = Vector(
    of(Kind.Kinship, ("A grandmother", "Бабушка"),
      ("Anna is Boris's mother. Boris is Vera's father. Who is Vera's grandmother?",
        "Анна — мать Бориса. Борис — отец Веры. Кто бабушка Веры?"), people("anna"),
      Gold.Rules("parent(anna, boris). parent(boris, vera). female(anna). male(boris). female(vera).\n" +
        "grandmother(X, Z) :- parent(X, Y), parent(Y, Z), female(X).", "grandmother(X, vera)"), "anna"),
    kinOf(("Grandparents", "Бабушка и дедушка"),
      ("Which of the people named are Dina's grandparents?", "Кто из названных — бабушка и дедушка Дины?"),
      "grandparent(X, Z) :- parent(X, Y), parent(Y, Z).", "grandparent(X, dina)", "anna", "gleb"),
    kinOf(("Grandchildren", "Внуки"), ("Who are Anna's grandchildren?", "Кто внуки Анны?"),
      "grandparent(X, Z) :- parent(X, Y), parent(Y, Z).", "grandparent(anna, X)", "dina", "egor", "nina"),
    kinOf(("A sister", "Сестра"), ("Who is Egor's sister?", "Кто сестра Егора?"),
      s"$sibling sister(X, Y) :- sibling(X, Y), female(X).", "sister(X, egor)", "nina"),
    kinOf(("A brother", "Брат"), ("Who is Vera's brother?", "Кто брат Веры?"),
      s"$sibling brother(X, Y) :- sibling(X, Y), male(X).", "brother(X, vera)", "boris"),
    kinOf(("An uncle", "Дядя"), ("Who is Nina's uncle?", "Кто дядя Нины?"),
      s"$sibling uncle(X, Y) :- parent(P, Y), sibling(X, P), male(X).", "uncle(X, nina)", "boris"),
    kinOf(("Cousins", "Двоюродные"), ("Who are Dina's cousins?", "Кто двоюродные брат и сестра Дины?"),
      s"$sibling cousin(X, Y) :- parent(P, X), parent(Q, Y), sibling(P, Q).", "cousin(X, dina)", "egor", "nina"),
    kinWays(("Every ancestor", "Все предки"), people("anna", "boris", "dina", "gleb", "olga"), ancestor,
      "ancestor(X, roman)", "anna", "boris", "dina", "gleb", "olga")(
      says("Which of the people named are [roman|Roman]'s ancestors?", "Кто из названных — предки [roman|Романа]?",
        to)),
    kinOf(("A great-grandmother", "Прабабушка"),
      ("Who is Roman's great-grandmother?", "Кто прабабушка Романа?"),
      "great(X, Z) :- parent(X, A), parent(A, B), parent(B, Z), female(X).", "great(X, roman)", "anna"),
    kinWays(("An ancestor or not", "Предок или нет"), no, ancestor, "ancestor(boris, nina)", "no")(
      says("Is [boris|Boris] an ancestor of [nina|Nina]?", "Является ли [boris|Борис] предком [nina|Нины]?", whether,
        path)),
    kinWays(("An ancestor through three generations", "Предок через три поколения"), yes, ancestor,
      "ancestor(gleb, roman)", "yes")(
      says("Is [gleb|Gleb] an ancestor of [roman|Roman]?", "Является ли [gleb|Глеб] предком [roman|Романа]?", whether,
        path)),
    kinWays(("Without children", "Без детей"), people("egor", "nina", "roman"),
      "person(X) :- female(X). person(X) :- male(X). has(X) :- parent(X, Y). " +
        "childless(X) :- person(X), not has(X).", "childless(X)", "egor", "nina", "roman")(
      says("Which of the people named have no children?", "У кого из названных нет детей?", say(Con.Ends))))

  private val flightsSaid = Vector(says(
    "There are direct flights, each in one direction only: from [kyiv|Kyiv] to [warsaw|Warsaw], " +
      "from [warsaw|Warsaw] to [berlin|Berlin], from [berlin|Berlin] to [paris|Paris], " +
      "from [paris|Paris] to [madrid|Madrid], from [lviv|Lviv] to [warsaw|Warsaw], " +
      "and from [rome|Rome] to [vienna|Vienna].",
    "Есть прямые рейсы, каждый только в одну сторону: из [kyiv|Киева] в [warsaw|Варшаву], " +
      "из [warsaw|Варшавы] в [berlin|Берлин], из [berlin|Берлина] в [paris|Париж], " +
      "из [paris|Парижа] в [madrid|Мадрид], из [lviv|Львова] в [warsaw|Варшаву] и из [rome|Рима] в [vienna|Вену].",
    Vector.fill(6)(edge)*))
  private val flights = plain(flightsSaid)
  private val flightFacts = "flight(kyiv, warsaw). flight(warsaw, berlin). flight(berlin, paris). " +
    "flight(paris, madrid). flight(lviv, warsaw). flight(rome, vienna).\n" +
    "reach(X, Y) :- flight(X, Y). reach(X, Z) :- flight(X, Y), reach(Y, Z)."
  private val cities = Map("kyiv" -> "Киев", "warsaw" -> "Варшава", "berlin" -> "Берлин", "paris" -> "Париж",
    "madrid" -> "Мадрид", "lviv" -> "Львов", "rome" -> "Рим", "vienna" -> "Вена")
  private def fly(title: (String, String), answer: (String, String), rules: String, goal: String, expect: String*)(
      ask: Noted): Task =
    ways(Kind.Routes, title, flightsSaid, answer, Gold.Rules(s"$flightFacts\n$rules", goal), expect*)(ask)
  private def found(keys: String*): (String, String) =
    (keys.map(_.capitalize).mkString(", "), keys.map(cities).mkString(", "))
  private def listed(found: String*): (String, String) = (found.mkString(", "), found.mkString(", "))

  // "needs", "imports", "uses": the first leads to the second
  private val buildingSaid = Vector(says(
    "Building a house: the [walls] need the [foundation]; the [wiring] needs the [walls]; " +
      "the [plaster] needs the [wiring]; the [painting] needs the [plaster]; the [roof] needs the [walls]; " +
      "the [windows] need the [walls].",
    "Строим дом: для [walls|стен] нужен [foundation|фундамент]; для [wiring|проводки] нужны [walls|стены]; " +
      "для [plaster|штукатурки] нужна [wiring|проводка]; для [painting|покраски] нужна [plaster|штукатурка]; " +
      "для [roof|крыши] нужны [walls|стены]; для [windows|окон] нужны [walls|стены].",
    Vector.fill(6)(edge)*))
  private val buildingFacts = "needs(walls, foundation). needs(wiring, walls). needs(plaster, wiring). " +
    "needs(painting, plaster). needs(roof, walls). needs(windows, walls).\n" +
    "before(X, Y) :- needs(Y, X). before(X, Z) :- needs(Z, Y), before(X, Y)."
  private val importsSaid = Vector(says(
    "In a program the module [auth] imports [billing], [billing] imports [core], [core] imports [auth], " +
      "[docs] imports [auth], and [core] imports [export].",
    "В программе модуль [auth] импортирует [billing], [billing] импортирует [core], [core] импортирует [auth], " +
      "[docs] импортирует [auth], а [core] импортирует [export].",
    Vector.fill(5)(edge)*))
  private val importFacts = "imports(auth, billing). imports(billing, core). imports(core, auth). " +
    "imports(docs, auth). imports(core, export).\n" +
    "uses(X, Y) :- imports(X, Y). uses(X, Z) :- imports(X, Y), uses(Y, Z)."
  private val roadsSaid = Vector(says(
    "Villages are joined by roads, each passable in both directions: [dubky|Dubky] and [lisove|Lisove], " +
      "[lisove|Lisove] and [richky|Richky], [richky|Richky] and [hirske|Hirske], [stavky|Stavky] and " +
      "[poliana|Poliana].",
    "Сёла соединены дорогами, по каждой можно ехать в обе стороны: [dubky|Дубки] и [lisove|Лесное], " +
      "[lisove|Лесное] и [richky|Речки], [richky|Речки] и [hirske|Горское], [stavky|Ставки] и [poliana|Поляна].",
    Vector.fill(4)(link)*))
  private val roadFacts = "road(dubky, lisove). road(lisove, richky). road(richky, hirske). road(stavky, poliana).\n" +
    "link(X, Y) :- road(X, Y). link(X, Y) :- road(Y, X).\n" +
    "way(X, Y) :- link(X, Y). way(X, Z) :- link(X, Y), way(Y, Z)."
  private val servicesSaid = Vector(
    says("The [web|web site] uses the [api|API].", "[web|Сайт] использует [api|API].", edge),
    says("The [api|API] uses the [database] and the [queue].",
      "[api|API] использует [database|базу данных] и [queue|очередь].", say(Con.Edge, 0, 1), say(Con.Edge, 0, 2)),
    says("The [reports] use the [database].", "[reports|Отчёты] используют [database|базу данных].", edge),
    says("The [mailer] uses the [queue].", "[mailer|Почтовая служба] использует [queue|очередь].", edge),
    says("The [search] uses the [index].", "[search|Поиск] использует [index|индекс].", edge))
  private val serviceFacts = "uses(web, api). uses(api, database). uses(api, queue). uses(reports, database). " +
    "uses(mailer, queue). uses(search, index).\n" +
    "depends(X, Y) :- uses(X, Y). depends(X, Z) :- uses(X, Y), depends(Y, Z)."

  val routes: Vector[Task] = Vector(
    fly(("Flights: can one get there", "Рейсы: можно ли добраться"), yes, "", "reach(kyiv, madrid)", "yes")(
      says("Can one get from [kyiv|Kyiv] to [madrid|Madrid]?", "Можно ли добраться из [kyiv|Киева] в [madrid|Мадрид]?",
        whether, path)),
    fly(("Flights: and back", "Рейсы: а обратно"), no, "", "reach(madrid, kyiv)", "no")(
      says("Can one get from [madrid|Madrid] to [kyiv|Kyiv]?", "Можно ли добраться из [madrid|Мадрида] в [kyiv|Киев]?",
        whether, path)),
    fly(("Flights: where from Warsaw", "Рейсы: куда из Варшавы"), found("berlin", "paris", "madrid"), "",
      "reach(warsaw, X)", "berlin", "madrid", "paris")(
      says("Which cities can be reached from [warsaw|Warsaw]?", "В какие города можно добраться из [warsaw|Варшавы]?",
        from)),
    fly(("Flights: from where to Berlin", "Рейсы: откуда в Берлин"), found("kyiv", "lviv", "warsaw"), "",
      "reach(X, berlin)", "kyiv", "lviv", "warsaw")(
      says("From which cities can [berlin|Berlin] be reached?", "Из каких городов можно добраться в [berlin|Берлин]?",
        to)),
    // "exactly two flights" counts the steps of a way: a statement of ways does not say that
    of(Kind.Routes, ("Flights: exactly two", "Рейсы: ровно два"),
      (s"${flights._1} Where does one arrive from Kyiv by exactly two flights?",
        s"${flights._2} Куда можно попасть из Киева ровно двумя рейсами?"), found("berlin"),
      Gold.Rules(s"$flightFacts\ntwo(X, Z) :- flight(X, Y), flight(Y, Z).", "two(kyiv, X)"), "berlin"),
    fly(("Flights: out of reach", "Рейсы: куда не добраться"), found("lviv", "rome", "vienna"),
      "city(X) :- flight(X, Y). city(Y) :- flight(X, Y). far(X) :- city(X), not reach(kyiv, X), X != kyiv.",
      "far(X)", "lviv", "rome", "vienna")(
      says("Which of the cities named cannot be reached from [kyiv|Kyiv]?",
        "В какие из названных городов нельзя добраться из [kyiv|Киева]?", say(Con.Apart))),
    ways(Kind.Routes, ("Building: what comes first", "Стройка: что сначала"), buildingSaid,
      ("The foundation, the walls, the wiring, the plaster", "Фундамент, стены, проводка, штукатурка"),
      Gold.Rules(buildingFacts, "before(X, painting)"), "foundation", "plaster", "walls", "wiring")(
      says("What must be finished before the [painting]?", "Что должно быть закончено до [painting|покраски]?", from)),
    ways(Kind.Routes, ("Building: what waits for the walls", "Стройка: что ждёт стен"), buildingSaid,
      ("The wiring, the plaster, the painting, the roof, the windows", "Проводка, штукатурка, покраска, крыша, окна"),
      Gold.Rules(buildingFacts, "before(walls, X)"), "painting", "plaster", "roof", "windows", "wiring")(
      says("Which works cannot start until the [walls] are finished?",
        "Какие работы нельзя начать, пока не закончены [walls|стены]?", to)),
    // the roof is asked whether it needs the wiring: a way from the second named to the first
    ways(Kind.Routes, ("Building: roof and wiring", "Стройка: крыша и проводка"), buildingSaid, no,
      Gold.Rules(buildingFacts, "before(wiring, roof)"), "no")(
      says("Must the [wiring] be finished before the [roof]?",
        "Обязательно ли закончить [wiring|проводку] до [roof|крыши]?", whether, say(Con.Path, 1, 0))),
    ways(Kind.Routes, ("Imports: the circle", "Импорты: круг"), importsSaid, listed("auth", "billing", "core"),
      Gold.Rules(s"$importFacts\ncyclic(X) :- uses(X, X).", "cyclic(X)"), "auth", "billing", "core")(
      says("Which modules are part of an import cycle?", "Какие модули входят в цикл импортов?", say(Con.Cycle))),
    ways(Kind.Routes, ("Imports: what docs pulls in", "Импорты: что тянет docs"), importsSaid,
      listed("auth", "billing", "core", "export"),
      Gold.Rules(importFacts, "uses(docs, X)"), "auth", "billing", "core", "export")(
      says("Which modules does [docs] use, directly or through other modules?",
        "Какие модули использует [docs] — напрямую или через другие модули?", from)),
    ways(Kind.Routes, ("Imports: is export in a circle", "Импорты: в круге ли export"), importsSaid, no,
      Gold.Rules(importFacts, "uses(export, export)"), "no")(
      says("Is the module [export] part of an import cycle?", "Входит ли модуль [export] в цикл импортов?", whether,
        say(Con.Round))),
    ways(Kind.Routes, ("Roads: a way through", "Дороги: есть ли путь"), roadsSaid, yes,
      Gold.Rules(roadFacts, "way(dubky, hirske)"), "yes")(
      says("Can one drive from [dubky|Dubky] to [hirske|Hirske]?",
        "Можно ли доехать из [dubky|Дубков] в [hirske|Горское]?", whether, path)),
    ways(Kind.Routes, ("Roads: no way", "Дороги: пути нет"), roadsSaid, no,
      Gold.Rules(roadFacts, "way(dubky, poliana)"), "no")(
      says("Can one drive from [dubky|Dubky] to [poliana|Poliana]?",
        "Можно ли доехать из [dubky|Дубков] в [poliana|Поляну]?", whether, path)),
    ways(Kind.Routes, ("Roads: from Hirske", "Дороги: из Горского"), roadsSaid,
      ("Dubky, Lisove, Richky", "Дубки, Лесное, Речки"),
      Gold.Rules(s"$roadFacts\nother(X) :- way(hirske, X), X != hirske.", "other(X)"), "dubky", "lisove", "richky")(
      says("Which other villages can be reached from [hirske|Hirske]?",
        "В какие другие сёла можно доехать из [hirske|Горского]?", from)),
    ways(Kind.Routes, ("Services: the database fails", "Сервисы: отказала база данных"), servicesSaid,
      ("The API, the reports, the web site", "API, отчёты, сайт"),
      Gold.Rules(serviceFacts, "depends(X, database)"), "api", "reports", "web")(
      says("Which services stop working if the [database] fails?",
        "Какие сервисы перестанут работать, если откажет [database|база данных]?", to)),
    ways(Kind.Routes, ("Services: the search", "Сервисы: поиск"), servicesSaid, no,
      Gold.Rules(serviceFacts, "depends(search, queue)"), "no")(
      says("Does the [search] stop working if the [queue] fails?",
        "Перестанет ли работать [search|поиск], если откажет [queue|очередь]?", whether, path)))
