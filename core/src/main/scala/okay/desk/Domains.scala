package okay.desk

/**
 * MANY SCHEMAS FOR THE PAIRS (specs/everyday.md): tables of a person's or a small business's life, each column with
 * its role — money, a count, a measure, a date, a category, a name — its values drawn by a seed, and the Russian forms
 * of its names for the grammar (`Phrase.Russian`). The data readers were taught on a few generic schemas
 * (`Pairs.table`: name, category, price, qty…) and misread everyday tables (money words, months). The six domains of
 * the measure (`tasks.Everyday`: expenses, sales, stock, shifts, marks, bank) are NOT among these: what the readers
 * learn here is judged on tables they never saw.
 */
object Domains:
  enum Role:
    case Money, Count, Measure, Time, Category, Name

  /** a column: its name, its role, the values of a category or a name, the range of a number; Russian forms */
  final case class Column(name: String, role: Role, values: Vector[String] = Vector.empty, low: Int = 1,
      high: Int = 100, ru: (String, String, String) = ("", "", ""))
  /** a domain: its table's name, Russian forms (nominative, genitive, prepositional, dative), its columns */
  final case class Domain(table: String, ru: (String, String, String, String), columns: Vector[Column])

  private def money(name: String, low: Int, high: Int, ru: (String, String, String)) =
    Column(name, Role.Money, low = low, high = high, ru = ru)
  private def count(name: String, low: Int, high: Int, ru: (String, String, String)) =
    Column(name, Role.Count, low = low, high = high, ru = ru)
  private def measure(name: String, low: Int, high: Int, ru: (String, String, String)) =
    Column(name, Role.Measure, low = low, high = high, ru = ru)
  private def cat(name: String, values: Vector[String], ru: (String, String, String)) =
    Column(name, Role.Category, values, ru = ru)
  private def who(name: String, values: Vector[String], ru: (String, String, String)) =
    Column(name, Role.Name, values, ru = ru)
  private val date = Column("date", Role.Time, ru = ("дата", "даты", "дате"))

  val all: Vector[Domain] = Vector(
    Domain("trips", ("поездки", "поездок", "поездках", "поездкам"), Vector(date,
      cat("destination", Vector("Rome", "Prague", "Vienna", "Gdansk", "Lisbon"),
        ("направление", "направления", "направлению")),
      cat("transport", Vector("train", "plane", "bus", "car"), ("транспорт", "транспорта", "транспорту")),
      measure("nights", 1, 10, ("ночи", "ночей", "ночам")),
      money("cost", 80, 1500, ("стоимость", "стоимости", "стоимости")))),
    Domain("workouts", ("тренировки", "тренировок", "тренировках", "тренировкам"), Vector(date,
      cat("activity", Vector("running", "cycling", "swimming", "yoga", "gym"), ("занятие", "занятия", "занятию")),
      measure("minutes", 15, 120, ("минуты", "минут", "минутам")), measure("km", 1, 40, ("км", "км", "км")),
      measure("calories", 100, 900, ("калории", "калорий", "калориям")))),
    Domain("books", ("книги", "книг", "книгах", "книгам"), Vector(
      who("title", Vector("Dune", "Emma", "Ulysses", "Solaris", "Beloved", "Ivanhoe", "Kim", "Lolita"),
        ("название", "названия", "названию")),
      cat("genre", Vector("novel", "sci-fi", "history", "poetry", "crime"), ("жанр", "жанра", "жанру")),
      measure("pages", 90, 900, ("страницы", "страниц", "страницам")),
      measure("rating", 1, 5, ("оценка", "оценки", "оценке")),
      money("price", 8, 60, ("цена", "цены", "цене")))),
    Domain("utilities", ("коммунальные", "коммунальных", "коммунальных", "коммунальным"), Vector(date,
      cat("service", Vector("electricity", "water", "gas", "internet", "heating"), ("услуга", "услуги", "услуге")),
      measure("usage", 5, 900, ("расход", "расхода", "расходу")), money("bill", 20, 400, ("счёт", "счёта", "счёту")))),
    Domain("subscriptions", ("подписки", "подписок", "подписках", "подпискам"), Vector(
      who("app", Vector("Spotify", "YouTube", "iCloud", "Duolingo", "Disney", "Dropbox"),
        ("приложение", "приложения", "приложению")),
      cat("plan", Vector("monthly", "yearly", "family"), ("тариф", "тарифа", "тарифу")),
      money("fee", 3, 30, ("плата", "платы", "плате")), cat("status", Vector("active", "paused", "cancelled"),
        ("статус", "статуса", "статусу")))),
    Domain("rentals", ("аренда", "аренды", "аренде", "аренде"), Vector(date,
      who("tenant", Vector("Kowalski", "Nowak", "Smith", "Ivanenko", "Muller"),
        ("арендатор", "арендатора", "арендатору")),
      cat("unit", Vector("flat 1", "flat 2", "flat 3", "garage"), ("помещение", "помещения", "помещению")),
      money("rent", 300, 2200, ("плата", "платы", "плате")),
      cat("settled", Vector("yes", "no"), ("оплата", "оплаты", "оплате")))),
    Domain("cafe_orders", ("заказы кафе", "заказов кафе", "заказах кафе", "заказам кафе"), Vector(date,
      cat("dish", Vector("latte", "croissant", "salad", "soup", "cheesecake", "espresso"), ("блюдо", "блюда", "блюду")),
      count("quantity", 1, 12, ("количество", "количества", "количеству")),
      money("total", 3, 90, ("сумма", "суммы", "сумме")),
      cat("table_area", Vector("terrace", "hall", "takeaway"), ("зона", "зоны", "зоне")))),
    Domain("repairs", ("ремонт", "ремонта", "ремонте", "ремонту"), Vector(date,
      cat("room", Vector("kitchen", "bathroom", "bedroom", "hall"), ("комната", "комнаты", "комнате")),
      cat("work", Vector("painting", "plumbing", "tiling", "wiring"), ("работа", "работы", "работе")),
      measure("hours", 1, 16, ("часы", "часов", "часам")), money("price", 50, 2500, ("цена", "цены", "цене")))),
    Domain("donations", ("пожертвования", "пожертвований", "пожертвованиях", "пожертвованиям"), Vector(date,
      who("donor", Vector("Alice", "Bohdan", "Chen", "Dana", "Emil"), ("жертвователь", "жертвователя", "жертвователю")),
      cat("cause", Vector("shelter", "school", "hospital", "animals"), ("цель", "цели", "цели")),
      money("amount", 5, 1000, ("сумма", "суммы", "сумме")))),
    Domain("harvest", ("урожай", "урожая", "урожае", "урожаю"), Vector(date,
      cat("crop", Vector("tomatoes", "apples", "potatoes", "berries", "cucumbers"),
        ("культура", "культуры", "культуре")),
      measure("kg", 1, 120, ("кг", "кг", "кг")), cat("field", Vector("north", "south", "greenhouse"),
        ("участок", "участка", "участку")))),
    Domain("tickets", ("обращения", "обращений", "обращениях", "обращениям"), Vector(date,
      cat("priority", Vector("low", "medium", "high", "urgent"), ("приоритет", "приоритета", "приоритету")),
      who("agent", Vector("Ola", "Piotr", "Kasia", "Max", "Ira"), ("агент", "агента", "агенту")),
      measure("minutes", 5, 300, ("минуты", "минут", "минутам")),
      cat("state", Vector("open", "solved", "waiting"), ("состояние", "состояния", "состоянию")))),
    Domain("fuel", ("топливо", "топлива", "топливе", "топливу"), Vector(date,
      cat("station", Vector("Orlen", "Shell", "BP", "Circle K"), ("заправка", "заправки", "заправке")),
      measure("litres", 10, 70, ("литры", "литров", "литрам")), money("paid", 60, 450, ("оплата", "оплаты", "оплате")),
      measure("odometer", 1000, 90000, ("пробег", "пробега", "пробегу")))),
    Domain("events", ("мероприятия", "мероприятий", "мероприятиях", "мероприятиям"), Vector(date,
      cat("kind", Vector("concert", "conference", "workshop", "meetup"), ("вид", "вида", "виду")),
      cat("city", Vector("Warsaw", "Kyiv", "Berlin", "Krakow"), ("город", "города", "городу")),
      count("guests", 10, 400, ("гости", "гостей", "гостям")),
      money("budget", 500, 20000, ("бюджет", "бюджета", "бюджету")))),
    Domain("medications", ("лекарства", "лекарств", "лекарствах", "лекарствам"), Vector(date,
      who("drug", Vector("ibuprofen", "vitamin D", "aspirin", "omega 3"), ("препарат", "препарата", "препарату")),
      count("doses", 1, 4, ("дозы", "доз", "дозам")), money("cost", 4, 60, ("стоимость", "стоимости", "стоимости")))))

  /** the names of the measure's domains, kept out of these (a law: `DomainsSuite`) */
  val heldOut: Set[String] = Set("expenses", "sales", "stock", "shifts", "marks", "bank")

  /** a domain's table, `n` rows drawn by `rnd`: dates over January–June 2026 */
  def table(d: Domain, rnd: scala.util.Random, n: Int): Table =
    def cell(c: Column): Value = c.role match
      case Role.Time => Value.Str(f"2026-${1 + rnd.nextInt(6)}%02d-${1 + rnd.nextInt(28)}%02d")
      case Role.Category | Role.Name => Value.Str(c.values(rnd.nextInt(c.values.size)))
      case _ => Value.Num(c.low + rnd.nextInt(c.high - c.low + 1))
    val rows = Vector.fill(n)(d.columns.map(cell))
    val sorted = d.columns.indexWhere(_.role == Role.Time) match
      case -1 => rows
      case i => rows.sortBy(_(i).text)
    Table(d.table, d.columns.map(_.name), sorted)

  /** one domain's table drawn at random */
  def any(rnd: scala.util.Random): Table =
    val d = all(rnd.nextInt(all.size))
    table(d, rnd, 12 + rnd.nextInt(24))

  /** the Russian forms the grammar needs: tables, columns */
  lazy val russianTables: Map[String, (String, String, String, String)] = all.map(d => d.table -> d.ru).toMap
  lazy val russianColumns: Map[String, (String, String, String)] =
    all.flatMap(_.columns.filter(_.ru._1.nonEmpty).map(c => c.name -> c.ru)).toMap
