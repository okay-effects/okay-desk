package okay.desk.bench

import okay.desk.*

/**
 * THE PAGE'S READINGS SCORED (specs/pilot.md §7): a TSV the live page's driver wrote — a question, then `PROGRAM`
 * with the program it ran or `ASK` with the question it asked back — against `RealBench`'s golds: right (the result
 * equals a gold's), asked (a question back: the dialogue's abstention), wrong. `sbt "bench/runMain
 * okay.desk.bench.PageScore <tsv>"`. The margin's measurement: what a margin turns wrong answers into, and right ones.
 */
object PageScore:
  def main(args: Array[String]): Unit =
    val path = sys.env.getOrElse("DESK_GDP", s"${sys.props("user.home")}/.cache/okay-desk/real/gdp.csv")
    val gdp = Table.parse("gdp.csv", String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), "UTF-8")).fold(e => sys.error(e), identity)
    val tables = Map(gdp.name -> gdp)
    def run(q: Query): Either[String, Table] = q match
      case r: Query.Relational => Exact.run(tables, r)
      case l: Query.Logical => Logic.run(tables, l)
    val golds: Map[String, Vector[Table]] = RealBench.questions.flatMap(q => Vector(q.en, q.ru).map(t => t -> q.golds.flatMap(g => Query.parse(g).flatMap(run).toOption))).toMap
    val outside = RealBench.questions.filter(_.outside).flatMap(q => Vector(q.en, q.ru)).toSet
    for file <- args do
      val lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(file)).toArray.map(_.toString).filter(_.nonEmpty)
      var right = 0; var asked = 0; var wrong = 0; var refusedOutside = 0
      for l <- lines do
        val parts = l.split("\t", 3)
        val (q, kind, body) = (parts(0), parts.lift(1).getOrElse(""), parts.lift(2).getOrElse(""))
        val outcome =
          if kind == "ASK" then { asked += 1; if outside(q) then refusedOutside += 1; s"asked: $body" }
          else
            val json = body.dropWhile(_ != '{')
            val got = Query.parse(json).flatMap(run).toOption
            if got.exists(r => golds.getOrElse(q, Vector.empty).exists(gl => Sample.same(r, gl))) then { right += 1; "RIGHT" } else { wrong += 1; s"WRONG $json" }
        println(f"  $outcome%-60s $q")
      println(s"$file: right $right, asked $asked (of them outside the grammar $refusedOutside), wrong $wrong — of ${lines.size}")
