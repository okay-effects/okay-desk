package okay.desk.bench

import mlx.llm.{Chat, Model, Models}
import okay.desk.*
import okay.desk.skill.*

/**
 * THE READERS ON REAL DATA (specs/data.md §28): the World Bank's GDP series (country, code, year, value; ~14,000
 * rows), the file the page's example opens. Two measures, the skill file unchanged:
 *
 *  1. PEOPLE'S QUESTIONS — written by hand, not by `Phrase`, in English and Russian, each with its gold program(s)
 *     (a question a person may mean two ways has two); some OUTSIDE the space (two years compared, a share): for
 *     those the right outcome is an exact answer or a refusal, never a wrong one. Printed: right, refused, wrong.
 *  2. THE SPACE OVER THIS SCHEMA — points drawn by `Space.random` over the GDP schema, said by `Phrase`, measured by
 *     constructor (the grammar's phrasing, on a real schema).
 *
 * `sbt "bench/runMain okay.desk.bench.RealBench"`; DESK_MODELS (default: the page's core), DESK_LAYER (19),
 * DESK_SPACE=n the space's pairs (200), DESK_GDP the CSV (default ~/.cache/okay-desk/real/gdp.csv, fetched by
 * `curl -L -o … https://raw.githubusercontent.com/datasets/gdp/main/data/gdp.csv`).
 */
object RealBench:
  final case class Q(en: String, ru: String, golds: Vector[String], outside: Boolean = false)

  private def where(cs: (String, String, String)*): String =
    cs.map((c, o, v) => s"""{"column": "$c", "op": "$o", "value": $v}""").mkString("\"where\": [", ", ", "]")
  private val year = (y: Int) => ("Year", "=", y.toString)
  private def agg(fn: String, column: String = "Value") =
    s""""aggregate": [{"fn": "$fn", "column": "$column", "as": "${if fn == "count" then "n" else fn}"}]"""
  private val growthAgg = """"aggregate": [{"fn": "growth", "column": "Value", "as": "growth", "by": "Year"}]"""
  private val country = (n: String) => ("Country Name", "=", "\"" + n + "\"")
  private def growth(desc: Boolean, n: Int) =
    s""""group": ["Country Name"], $growthAgg, """ +
      s""""order": ["${if desc then "-" else ""}growth"], "limit": $n"""

  val questions: Vector[Q] = Vector(
    Q("What was the GDP of Ukraine in 2020?", "Какой был ВВП Украины в 2020 году?",
      Vector(s"""{"from": "gdp", ${where(country("Ukraine"), year(2020))}}""")),
    Q("Which ten countries had the largest GDP in 2023?", "Десять стран с наибольшим ВВП в 2023 году.",
      Vector(s"""{"from": "gdp", ${where(year(2023))}, "order": ["-Value"], "limit": 10}""")),
    Q("Which 5 countries grew fastest?", "Какие 5 стран росли быстрее всего?",
      Vector(s"""{"from": "gdp", ${growth(true, 5)}}""")),
    Q("Which 5 countries grew the slowest?", "У каких 5 стран ВВП рос медленнее всего?",
      Vector(s"""{"from": "gdp", ${growth(false, 5)}}""")),
    Q("Average GDP in 2010?", "Средний ВВП в 2010 году?",
      Vector(s"""{"from": "gdp", ${where(year(2010))}, ${agg("avg")}}""")),
    Q("Total GDP in 2022.", "Суммарный ВВП за 2022 год.",
      Vector(s"""{"from": "gdp", ${where(year(2022))}, ${agg("sum")}}""")),
    Q("The largest GDP ever recorded.", "Самый большой ВВП за всё время.",
      Vector("""{"from": "gdp", "aggregate": [{"fn": "max", "column": "Value", "as": "max"}]}""",
        """{"from": "gdp", "order": ["-Value"], "limit": 1}""")),
    Q("The smallest GDP in 2023.", "Наименьший ВВП в 2023 году.",
      Vector(s"""{"from": "gdp", ${where(year(2023))}, "aggregate": [{"fn": "min", "column": "Value", "as": "min"}]}""",
        s"""{"from": "gdp", ${where(year(2023))}, "order": ["Value"], "limit": 1}""")),
    Q("In which year was the GDP of Poland the highest?", "В каком году ВВП Польши был самым высоким?",
      Vector(s"""{"from": "gdp", ${where(country("Poland"))}, "order": ["-Value"], "limit": 1}""")),
    Q("Which country had the largest GDP in 2000?", "У какой страны был самый большой ВВП в 2000 году?",
      Vector(s"""{"from": "gdp", ${where(year(2000))}, "order": ["-Value"], "limit": 1}""")),
    Q("How many countries had GDP over 1000000000000 in 2023?", "Сколько стран имели ВВП больше 1000000000000 в 2023?",
      Vector(s"""{"from": "gdp", ${where(("Value", ">", "1000000000000"), year(2023))}, ${agg("count", "*")}}""")),
    Q("Countries with GDP below 100000000 in 2023.", "Страны с ВВП меньше 100000000 в 2023.",
      Vector(s"""{"from": "gdp", ${where(("Value", "<", "100000000"), year(2023))}}""")),
    Q("Average GDP per country.", "Средний ВВП по каждой стране.",
      Vector(s"""{"from": "gdp", "group": ["Country Name"], ${agg("avg")}}""")),
    Q("How many rows are in the table?", "Сколько строк в таблице?",
      Vector(s"""{"from": "gdp", ${agg("count", "*")}}""")),
    Q("GDP of Germany by year.", "ВВП Германии по годам.",
      Vector(s"""{"from": "gdp", ${where(country("Germany"))}}""",
        s"""{"from": "gdp", ${where(country("Germany"))}, "order": ["Year"]}""")),
    Q("How did the GDP of Ukraine grow?", "Как рос ВВП Украины?",
      Vector(s"""{"from": "gdp", ${where(country("Ukraine"))}, "group": ["Country Name"], $growthAgg}""")),
    // OUTSIDE the space: the right outcome is an exact answer or a refusal
    Q("Compare the GDP of Ukraine in 2010 and 2020.", "Сравни ВВП Украины в 2010 и 2020 годах.",
      Vector(s"""{"from": "gdp", ${where(country("Ukraine"), ("Year", "in", "[2010, 2020]"))}}"""), outside = true),
    Q("What share of the world's GDP did China have in 2023?", "Какую долю мирового ВВП имел Китай в 2023 году?",
      Vector("""{"no": "a share is not in the space"}"""), outside = true),
    Q("Did the GDP of Japan grow faster than that of Germany?", "Рос ли ВВП Японии быстрее, чем Германии?",
      Vector("""{"no": "a comparison of two entities is not in the space"}"""), outside = true))

  def main(args: Array[String]): Unit =
    val path = sys.env.getOrElse("DESK_GDP", s"${sys.props("user.home")}/.cache/okay-desk/real/gdp.csv")
    val gdp = Table.parse("gdp.csv", String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), "UTF-8"))
      .fold(e => sys.error(e), identity)
    val tables = Map(gdp.name -> gdp)
    println(s"gdp: ${gdp.size} rows, columns ${gdp.columns.mkString(", ")}")
    def run(q: Query): Either[String, Table] = q match
      case r: Query.Relational => Exact.run(tables, r)
      case l: Query.Logical => Logic.run(tables, l)
    val golds = questions.map(_.golds.map(g => Query.parse(g).flatMap(run).toOption))
    val repo = sys.env.getOrElse("DESK_MODELS", "local/Qwen3-0.6B-4bit-cut19-no-free-ru-m4")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val chat = Chat.load(dir)
    Model.load(dir, mapped = true) match
      case q3: mlx.llm.Qwen3 =>
        val layer = sys.env.getOrElse("DESK_LAYER", "19").toInt
        val readers = MlxReading.ready(chat, q3, layer, repo, sys.error("no skill file: train it with DeskBench first"))
        // 1. people's questions
        val zero = Map("en" -> 0, "ru" -> 0)
        var right = zero; var refused = zero; var wrong = zero
        val t0 = System.nanoTime()
        for (q, i) <- questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) do
          val program = readers.program(tables, text)
          val got = program.flatMap(Query.parse).flatMap(run)
          val ok = got.toOption.exists(t => golds(i).flatten.exists(g => Sample.same(t, g)))
          val outcome = if ok then "right" else if program.isLeft then "refused" else "WRONG"
          outcome match
            case "right" => right = right.updated(lang, right(lang) + 1)
            case "refused" => refused = refused.updated(lang, refused(lang) + 1)
            case _ => wrong = wrong.updated(lang, wrong(lang) + 1)
          println(f"  [$lang ${i + 1}%2d${if q.outside then " outside" else ""}] $outcome%-7s " +
            s"$text → ${program.fold(e => s"($e)", identity)}")
        val n = questions.size
        val inside = questions.count(!_.outside)
        println(f"people's questions ($n, $inside inside the space), " +
          f"${(System.nanoTime() - t0) / 1e9 / (2 * n)}%.1f s a question: " +
          s"en ${right("en")} right, ${refused("en")} refused, ${wrong("en")} wrong | " +
          s"ru ${right("ru")} right, ${refused("ru")} refused, ${wrong("ru")} wrong")
        // 2. the space over this schema, in the grammar's phrasing
        val m = sys.env.getOrElse("DESK_SPACE", "200").toInt
        if m > 0 then
          val rnd = scala.util.Random(45)
          val s = Space.Schema(tables)
          val pairs = Iterator.continually {
            val p = Space.random(rnd, s)
            Space.compile(p, tables).toOption.filter(q => run(q).exists(_.size > 0))
              .map(q => Pairs.Pair(tables, Phrase.say(rnd, p, s, rnd.nextBoolean()), Query.print(q)))
          }.flatten.take(m).toVector
          println(s"the space over the GDP schema: ${pairs.size} pairs, " +
            s"e.g. ${pairs.take(3).map(_.question).mkString(" | ")}")
          readers.measureByConstructor(pairs)
      case _ => println(s"$repo: the readers need a Qwen3")
