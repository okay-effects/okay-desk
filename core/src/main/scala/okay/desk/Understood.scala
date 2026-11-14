package okay.desk

/**
 * HOW THE QUESTION WAS UNDERSTOOD (specs/loop.md §2.1): a program said back in plain words, made by the exact side
 * from the program itself — never by the model — so that a misreading shows: "the number of rows" where the person
 * asked for a sum, a day where they meant a month. Shown under every data answer; a wrong part is what a person's
 * correction names. English and Russian; the table's and columns' own names in «», the values as they are.
 */
object Understood:
  private val monthsEn = Vector("January", "February", "March", "April", "May", "June", "July", "August", "September",
    "October", "November", "December")
  private val monthsRu = Vector("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре",
    "октябре", "ноябре", "декабре")
  private def q(name: String) = s"«$name»"
  private def bare(node: String) = node.trim.stripPrefix("\"").stripSuffix("\"")

  def apply(query: Query, ru: Boolean): String =
    def w(en: String, russian: String) = if ru then russian else en
    query match
      case Query.Logical(_, goal) =>
        val reach = """reach\((.+),\s*(.+)\)""".r
        goal.trim match
          case reach("X", node) =>
            val n = bare(node)
            w(s"what reaches $n, directly or through others", s"что ведёт к $n, прямо или через другие")
          case reach(node, "X") =>
            val n = bare(node)
            w(s"what $n reaches, directly or through others", s"куда ведёт $n, прямо или через другие")
          case other => w(s"the rules' answer to $other", s"ответ правил на $other")
      case Query.Relational(from, join, where, group, aggs, select, order, limit) =>
        val subject = aggs.map { a =>
          val c = q(a.column)
          a.fn match
            case "count" => w(s"the number of rows of ${q(from)}", s"число строк ${q(from)}")
            case "sum" => w(s"the sum of $c", s"сумма $c")
            case "avg" => w(s"the average of $c", s"среднее $c")
            case "min" => w(s"the smallest $c", s"наименьшее $c")
            case "max" => w(s"the largest $c", s"наибольшее $c")
            case "growth" | "change" =>
              w(s"how $c changed over ${a.by.fold("time")(q)}", s"как менялось $c по ${a.by.fold("времени")(q)}")
            case other => s"$other $c"
        } match
          case Vector() if select.nonEmpty =>
            val cols = select.map(q).mkString(", ")
            w(s"$cols of ${q(from)}", s"$cols из ${q(from)}")
          case Vector() => w(s"the rows of ${q(from)}", s"строки ${q(from)}")
          case xs => xs.mkString(", ")
        val joined = join.toVector.map(j => w(s"joined with ${q(j.table)}", s"вместе с ${q(j.table)}"))
        val conditions = said(where.toList, ru)
        val grouped = group.map(g => w(s"for each ${q(g)}", s"по каждому значению ${q(g)}"))
        val ordered = order.map(o =>
          w(s"sorted by ${q(o.column)}, ${if o.desc then "largest first" else "smallest first"}",
            s"по ${q(o.column)}, ${if o.desc then "сначала большие" else "сначала меньшие"}"))
        val limited = limit.toVector.map(n =>
          if n == 1 then w("only the first", "только первое") else w(s"the first $n", s"первые $n"))
        (Vector(subject) ++ joined ++ conditions.map(c => w("where ", "где ") + c) ++ grouped ++ ordered ++ limited)
          .mkString(" — ")

  /** conditions in words: a pair that is one month (on or after its first day, before the next month's) said as it */
  private def said(cs: List[Cond], ru: Boolean): Vector[String] = cs match
    case Cond.Cmp(c1, ">=", Value.Str(a)) :: Cond.Cmp(c2, "<", Value.Str(b)) :: rest
        if c1 == c2 && a.matches("\\d{4}-\\d{2}-01") && b == nextMonth(a) =>
      val (y, m) = (a.take(4), a.slice(5, 7).toInt - 1)
      (if ru then s"${q(c1)} в ${monthsRu(m)} $y" else s"${q(c1)} in ${monthsEn(m)} $y") +: said(rest, ru)
    case c :: rest => one(c, ru) +: said(rest, ru)
    case Nil => Vector.empty
  private def nextMonth(first: String): String =
    val (y, m) = (first.take(4).toInt, first.slice(5, 7).toInt)
    if m == 12 then f"${y + 1}-01-01" else f"$y-${m + 1}%02d-01"
  private def one(c: Cond, ru: Boolean): String = c match
    case Cond.Cmp(col, op, v) =>
      val words = op match
        case "=" => "="; case "!=" => "≠"; case "<" => "<"; case "<=" => "≤"; case ">" => ">"; case ">=" => "≥"
        case other => other
      s"${q(col)} $words ${v.text}"
    case Cond.Has(col, text) => if ru then s"${q(col)} содержит «$text»" else s"${q(col)} contains “$text”"
    case Cond.Empty(col, is) =>
      if ru then s"${q(col)} ${if is then "пусто" else "не пусто"}"
      else s"${q(col)} is ${if is then "empty" else "not empty"}"
