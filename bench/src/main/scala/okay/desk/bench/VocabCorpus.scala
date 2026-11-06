package okay.desk.bench

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import okay.effects.json.Json

/**
 * THE TEXT A SMALLER VOCABULARY IS CHOSEN BY (specs/data.md §19): English and Russian prose on the topics a desk's
 * data and questions come from — business, money, people, places, time, measures — written by a local model through
 * okay-mlx's gateway (OKAY_MLX_URL, else http://127.0.0.1:8090/v1), each topic in both languages; the last `held`
 * topics kept apart (`held-*`): text the vocabulary never saw, to measure what a smaller one splits differently.
 * Cached in ~/.cache/okay-desk/vocab-corpus. `sbt "bench/runMain okay.desk.bench.VocabCorpus"`.
 */
object VocabCorpus:
  val dir: Path = Path.of(sys.props("user.home"), ".cache", "okay-desk", "vocab-corpus")
  val topics: Vector[String] = Vector(
    "a small online shop's monthly sales report", "invoices, payments and overdue customers", "a company's quarterly revenue and costs",
    "employees, salaries and departments", "a warehouse's stock, suppliers and deliveries", "flights, airports and delays",
    "a city's population, districts and transport", "weather records over a year", "a school's students, classes and grades",
    "a hospital's patients, visits and doctors", "real estate listings: prices, areas, rooms", "a football league's teams and matches",
    "a bank account's transactions and balances", "stock prices of large companies and sectors", "a restaurant's orders and menu",
    "car sales by brand, model and region", "energy use of households by month", "a library's books, authors and loans",
    "a marketing campaign's clicks, visits and conversions", "taxes, deductions and a yearly declaration",
    "countries, capitals, currencies and languages", "a project's tasks, deadlines and people", "a farm's crops, fields and harvests",
    "hotel bookings: guests, nights, rooms and prices", "a gym's members, visits and subscriptions",
    "online courses: students, lessons and completion", "a delivery service's couriers, routes and times",
    "a support desk's tickets, priorities and response times", "a museum's visitors by day and exhibition",
    "a factory's production lines, shifts and defects", "insurance policies, claims and payouts",
    "rental cars: bookings, mileage and damage", "a charity's donors, donations and campaigns",
    "a cinema's films, sessions and tickets", "public transport: lines, stops, passengers per hour",
    "an election's districts, candidates and votes", "a pharmacy's medicines, prices and stock",
    "a telecom's subscribers, plans and data usage", "a conference's talks, speakers and rooms",
    // held apart
    "a vineyard's grape varieties, yields and wine prices", "a zoo's animals, feeding and visitors",
    "an airline's loyalty program: members, miles and tiers", "a city's bicycle rentals by station and hour")
  val held = 4

  def main(args: Array[String]): Unit =
    val url = sys.env.getOrElse("OKAY_MLX_URL", "http://127.0.0.1:8090/v1")
    val model = sys.env.getOrElse("VOCAB_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val http = HttpClient.newHttpClient()
    Files.createDirectories(dir)
    def ask(prompt: String): String =
      val body = Json.obj("model" -> Json.Str(model), "max_tokens" -> Json.Num(700), "temperature" -> Json.Num(0.7),
        "messages" -> Json.Arr(Vector(Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(prompt))))).render
      val r = http.send(HttpRequest.newBuilder(URI.create(s"$url/chat/completions")).header("Content-Type",
        "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
      Json.fromText(r.body)("choices").list.head("message")("content").string
    val t0 = System.nanoTime()
    for (topic, i) <- topics.zipWithIndex; lang <- Seq("en", "ru") do
      val set = if i >= topics.size - held then "held" else "corpus"
      val f = dir.resolve(f"$set-$lang-$i%02d.txt")
      if !Files.exists(f) then
        val prompt =
          if lang == "en" then s"Write about 300 words of plain prose about $topic, with names, numbers, dates and the " +
            "words a person would use asking about such data. No headings, no lists."
          else s"Напиши примерно 300 слов связного текста на русском языке на тему: $topic (переведи тему). С именами, " +
            "числами, датами и словами, которыми человек спрашивал бы о таких данных. Без заголовков и списков."
        Files.writeString(f, ask(prompt))
        println(f"$f (${(System.nanoTime() - t0) / 1e9}%.0f s)")
