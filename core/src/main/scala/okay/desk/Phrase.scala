package okay.desk

import Space.{Col, Kind, Program, Schema}

/**
 * THE PHRASES OF A PROGRAM (specs/data.md §25, step 2): a grammar from a point of the space to a question in
 * English or Russian, compositional — one phrase per constructor (the subject, the condition, the group, the
 * order, the limit, the absence, the closure), several wordings each, drawn at random; in Russian the names
 * decline by the word before (`Russian`), and each kind of name (a table, a column, a value) is said in Russian or
 * left in English by its own coin, so the readers see every mix. The pairs are the space × these phrases.
 */
object Phrase:
  /** the Russian forms of the generator's names: tables (nominative, genitive, prepositional, dative), columns
   * (nominative, genitive, dative), values (one form) */
  object Russian:
    val tables: Map[String, (String, String, String, String)] = Map(
      "products" -> ("товары", "товаров", "товарах", "товарам"), "employees" -> ("сотрудники", "сотрудников", "сотрудниках", "сотрудникам"),
      "invoices" -> ("счета", "счетов", "счетах", "счетам"), "flights" -> ("рейсы", "рейсов", "рейсах", "рейсам"),
      "tickets" -> ("билеты", "билетов", "билетах", "билетам"), "students" -> ("студенты", "студентов", "студентах", "студентам"),
      "visits" -> ("визиты", "визитов", "визитах", "визитам"), "shipments" -> ("отправки", "отправок", "отправках", "отправкам"),
      "sales" -> ("продажи", "продаж", "продажах", "продажам"), "reviews" -> ("отзывы", "отзывов", "отзывах", "отзывам"),
      "calls" -> ("звонки", "звонков", "звонках", "звонкам"), "bookings" -> ("брони", "броней", "бронях", "броням"),
      "people" -> ("люди", "людей", "людях", "людям"), "accounts" -> ("аккаунты", "аккаунтов", "аккаунтах", "аккаунтам"),
      "staff" -> ("работники", "работников", "работниках", "работникам"), "orders" -> ("заказы", "заказов", "заказах", "заказам"),
      "customers" -> ("клиенты", "клиентов", "клиентах", "клиентам"), "payments" -> ("платежи", "платежей", "платежах", "платежам"),
      "users" -> ("пользователи", "пользователей", "пользователях", "пользователям"), "items" -> ("позиции", "позиций", "позициях", "позициям"),
      "events" -> ("события", "событий", "событиях", "событиям"), "projects" -> ("проекты", "проектов", "проектах", "проектам"),
      "tasks" -> ("задачи", "задач", "задачах", "задачам"), "contracts" -> ("договоры", "договоров", "договорах", "договорам"),
      "deliveries" -> ("доставки", "доставок", "доставках", "доставкам"), "countries" -> ("страны", "стран", "странах", "странам"),
      "cities" -> ("города", "городов", "городах", "городам"))
    val columns: Map[String, (String, String, String)] = Map(
      "category" -> ("категория", "категории", "категории"), "region" -> ("регион", "региона", "региону"),
      "status" -> ("статус", "статуса", "статусу"), "type" -> ("тип", "типа", "типу"), "owner" -> ("владелец", "владельца", "владельцу"),
      "team" -> ("команда", "команды", "команде"), "vendor" -> ("поставщик", "поставщика", "поставщику"),
      "city" -> ("город", "города", "городу"), "kind" -> ("вид", "вида", "виду"), "price" -> ("цена", "цены", "цене"),
      "qty" -> ("количество", "количества", "количеству"), "total" -> ("итог", "итога", "итогу"), "score" -> ("балл", "балла", "баллу"),
      "hours" -> ("часы", "часов", "часам"), "weight" -> ("вес", "веса", "весу"), "age" -> ("возраст", "возраста", "возрасту"),
      "rating" -> ("рейтинг", "рейтинга", "рейтингу"), "cost" -> ("стоимость", "стоимости", "стоимости"), "count" -> ("число", "числа", "числу"),
      "day" -> ("день", "дня", "дню"), "since" -> ("год", "года", "году"), "customer" -> ("клиент", "клиента", "клиенту"),
      "country" -> ("страна", "страны", "стране"), "amount" -> ("сумма", "суммы", "сумме"))
    val values: Map[String, String] = Map("north" -> "север", "south" -> "юг", "east" -> "восток", "west" -> "запад",
      "open" -> "открыто", "closed" -> "закрыто", "new" -> "новые", "done" -> "готово", "red" -> "красные", "blue" -> "синие",
      "green" -> "зелёные", "gold" -> "золото", "silver" -> "серебро", "home" -> "дом", "work" -> "работа", "spare" -> "запас",
      "paid" -> "оплачено", "unpaid" -> "не оплачено")

  enum Case:
    case Nom, Gen, Prep, Dat

  /** which names are said in Russian: tables, columns, values — each by its coin */
  final case class Mix(tables: Boolean, columns: Boolean, values: Boolean)

  final class Saying(rnd: scala.util.Random, ru: Boolean, mix: Mix):
    def pick(xs: String*): String = xs(rnd.nextInt(xs.size))
    def table(t: String, c: Case): String =
      if !ru || !mix.tables then t else Russian.tables.get(t).fold(t) { (nom, gen, prep, dat) => c match
        case Case.Nom => nom; case Case.Gen => gen; case Case.Prep => prep; case Case.Dat => dat }
    def column(col: Col, c: Case): String =
      if !ru || !mix.columns then col.name else Russian.columns.get(col.name).fold(col.name) { (nom, gen, dat) => c match
        case Case.Gen => gen; case Case.Dat => dat; case _ => nom }
    def value(v: Value): String = if !ru || !mix.values then v.text else Russian.values.getOrElse(v.text, v.text)

    def condition(cond: (Col, String, Value)): String =
      val (col, op, v) = cond
      (col.kind, op) match
      case (Kind.Num, ">") => if ru then pick(s"с ${column(col, Case.Nom)} больше ${v.text}", s"у которых ${column(col, Case.Nom)} выше ${v.text}", s"где ${column(col, Case.Nom)} > ${v.text}")
        else pick(s"with ${col.name} over ${v.text}", s"with ${col.name} greater than ${v.text}", s"where ${col.name} > ${v.text}")
      case (Kind.Num, "<") => if ru then pick(s"с ${column(col, Case.Nom)} меньше ${v.text}", s"у которых ${column(col, Case.Nom)} ниже ${v.text}")
        else pick(s"with ${col.name} below ${v.text}", s"with ${col.name} less than ${v.text}")
      case (Kind.Time, _) => if ru then pick(s"за ${v.text}", s"на ${v.text}", s"с ${column(col, Case.Nom)} = ${v.text}")
        else pick(s"on ${v.text}", s"in ${v.text}", s"with ${col.name} = ${v.text}")
      case _ => if ru then pick(s"с ${column(col, Case.Nom)} = ${value(v)}", s"у которых ${column(col, Case.Nom)} равен ${value(v)}",
          s"где ${column(col, Case.Nom)} это ${value(v)}", s"со значением ${value(v)} в ${column(col, Case.Nom)}")
        else pick(s"with ${col.name} = ${value(v)}", s"where ${col.name} is ${value(v)}", s"whose ${col.name} is ${value(v)}", s"with ${col.name} ${value(v)}")

    def conditions(where: Vector[(Col, String, Value)]): String =
      if where.isEmpty then "" else " " + where.map(condition).mkString(if ru then " и " else " and ")

    def group(g: Col): String = if ru then pick(s"по каждому ${column(g, Case.Dat)}", s"в разрезе ${column(g, Case.Gen)}", s"по ${column(g, Case.Dat)}")
      else pick(s"per ${g.name}", s"by ${g.name}", s"for each ${g.name}")

    /** the order's words when it is on the aggregate or a column: descending or ascending */
    def order(desc: Boolean): String = if ru then (if desc then pick("по убыванию", "от большего к меньшему", "сначала самые большие") else pick("по возрастанию", "от меньшего к большему", "сначала самые маленькие"))
      else if desc then pick("largest first", "descending", "in descending order") else pick("smallest first", "ascending", "in ascending order")

    def say(p: Program, s: Schema): String = p match
      case Program.Closure(edges, node, forward) =>
        if forward then (if ru then pick(s"От чего зависит $node, прямо или через другие?", s"Что достижимо из $node?", s"Куда ведёт $node по $edges?")
          else pick(s"What does $node depend on, directly or through others?", s"What is reachable from $node?", s"Where does $node lead through $edges?"))
        else (if ru then pick(s"Какие узлы доходят до $node по $edges, прямо или через другие?", s"Что ведёт к $node?", s"Кто достигает $node?")
          else pick(s"Which nodes reach $node through $edges, directly or through others?", s"What leads to $node?", s"Who reaches $node?"))
      case Program.Absent(target, link, where) =>
        val t = table(target, Case.Nom); val o = table(link.from, Case.Gen)
        (if ru then pick(s"Какие $t без $o", s"$t, у которых нет $o", s"$t, не встречающиеся в ${table(link.from, Case.Prep)}")
          else pick(s"Which $t have no ${link.from}", s"$t without ${link.from}", s"$t that never appear in ${link.from}")) + conditions(where) + (if rnd.nextBoolean() then "?" else ".")
      case Program.Rel(from, link, where, grp, agg, ord, limit) =>
        val conds = conditions(where)
        val grouped = grp.map(g => " " + group(g)).getOrElse("")
        val ordered = ord.map((_, desc) => ", " + order(desc)).getOrElse("")
        val limited = limit.map { n =>
          if n == 1 then "" else if ru then pick(s", первые $n", s", топ-$n") else pick(s", top $n", s", the first $n") }.getOrElse("")
        val head = (agg, ord, limit) match
          case (Some(("count", _)), _, _) =>
            if ru then pick(s"Сколько ${table(from, Case.Gen)}", s"Число ${table(from, Case.Gen)}", s"Посчитай ${table(from, Case.Nom)}")
            else pick(s"How many ${from}", s"Count the $from", s"Number of $from")
          case (Some((fn, Some(c))), _, _) =>
            val col = column(c, Case.Gen)
            if ru then fn match
              case "sum" => pick(s"Сумма $col по ${table(from, Case.Dat)}", s"Итого $col для ${table(from, Case.Gen)}", s"Суммарный ${column(c, Case.Nom)} ${table(from, Case.Gen)}")
              case "avg" => pick(s"Средний ${column(c, Case.Nom)} ${table(from, Case.Gen)}", s"Средняя величина $col по ${table(from, Case.Dat)}")
              case "min" => pick(s"Наименьший ${column(c, Case.Nom)} среди ${table(from, Case.Gen)}", s"Минимум $col по ${table(from, Case.Dat)}")
              case _ => pick(s"Наибольший ${column(c, Case.Nom)} среди ${table(from, Case.Gen)}", s"Максимум $col по ${table(from, Case.Dat)}")
            else fn match
              case "sum" => pick(s"Total ${c.name} of $from", s"Sum of ${c.name} for $from", s"${c.name} summed over $from")
              case "avg" => pick(s"Average ${c.name} of $from", s"Mean ${c.name} for $from")
              case "min" => pick(s"The smallest ${c.name} among $from", s"Minimum ${c.name} of $from")
              case _ => pick(s"The largest ${c.name} among $from", s"Maximum ${c.name} of $from")
          case (None, Some((c, desc)), Some(1)) =>
            val col = s.of(from).find(_.name == c).map(column(_, Case.Nom)).getOrElse(c)
            if ru then (if desc then pick(s"${table(from, Case.Nom)} с самым большим $col", s"Строка ${table(from, Case.Gen)} с наибольшим $col", s"У какого из ${table(from, Case.Gen)} самый высокий $col")
              else pick(s"${table(from, Case.Nom)} с самым маленьким $col", s"Строка ${table(from, Case.Gen)} с наименьшим $col", s"У какого из ${table(from, Case.Gen)} самый низкий $col"))
            else if desc then pick(s"The $from row with the largest $c", s"Which of the $from has the highest $c", s"The $from with the biggest $c")
              else pick(s"The $from row with the smallest $c", s"Which of the $from has the lowest $c", s"The $from with the least $c")
          case (None, Some((c, _)), _) =>
            val col = s.of(from).find(_.name == c).map(column(_, Case.Dat)).getOrElse(c)
            if ru then pick(s"${table(from, Case.Nom)} по $col", s"Покажи ${table(from, Case.Nom)}, упорядочив по $col") else pick(s"$from by $c", s"Show $from ordered by $c", s"List $from sorted by $c")
          case _ =>
            if ru then pick(s"Какие ${table(from, Case.Nom)}", s"Покажи ${table(from, Case.Nom)}", s"Выведи ${table(from, Case.Nom)}", s"Список ${table(from, Case.Gen)}")
            else pick(s"Which $from", s"Show the $from", s"List $from", s"The $from")
        // a limit of 1 with an order is said in the head; a limit with a count is "the top N groups"
        val tail = (ord, limit) match
          case (Some(_), Some(1)) => ""
          case _ => ordered + limited
        val end = if head.startsWith("How many") || head.startsWith("Сколько") || head.startsWith("Which") || head.startsWith("Какие") || head.startsWith("У какого") then "?" else "."
        head + conds + grouped + tail + end

  /** a question for a program: `mix` drawn here when not given */
  def say(rnd: scala.util.Random, p: Program, s: Schema, ru: Boolean, mix: Option[Mix] = None): String =
    val m = mix.getOrElse(Mix(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean()))
    Saying(rnd, ru, m).say(p, s)
