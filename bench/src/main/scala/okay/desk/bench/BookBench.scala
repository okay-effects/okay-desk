package okay.desk.bench

import okay.desk.*
import okay.desk.calc.*
import okay.desk.learn.Book
import okay.desk.organ.{OrganFile, Organs}
import okay.scala3.*

/**
 * THE BOOK OF A DATASET READ (BACKLOG D 24): the GDP data dictionary (`web/page/books/gdp.md`) read into a language
 * and an organ; the 19 real questions (`RealBench`) by the data alone WITHOUT the book and WITH it, then with the
 * organ of the book's examples too; the examples' pairs and questions shown. No model.
 * `sbt "bench/runMain okay.desk.bench.BookBench"`; DESK_GDP, DESK_WORDS, DESK_BOOK as elsewhere.
 */
object BookBench:
  def main(args: Array[String]): Unit =
    val path = sys.env.getOrElse("DESK_GDP", s"${sys.props("user.home")}/.cache/okay-desk/real/gdp.csv")
    val gdp = Table.parse("gdp.csv", String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), "UTF-8")).fold(e => sys.error(e), identity)
    val tables = Map(gdp.name -> gdp)
    val s = Space.Schema(tables); val g = new Grammar(s)
    val words = sys.env.get("DESK_WORDS").map(p => okay.desk.skill.Words.parse(java.nio.file.Files.readString(java.nio.file.Path.of(p))).fold(e => sys.error(e), identity)).getOrElse(okay.desk.skill.Words.none)
    val book = Book.parse(java.nio.file.Files.readString(Bench.root.resolve(sys.env.getOrElse("DESK_BOOK", "web/page/books/gdp.md"))), "gdp")
    println(s"the book: ${book.tables.size} tables, ${book.columns.size} columns, ${book.values.size} values, ${book.examples.size} examples")
    val lesson = Book.read(s, book, words = words)
    println(s"  ${lesson.text}")
    for (q, t) <- lesson.pairs do println(s"    pair: $q → ${Encode.word(t).mkString(" ")}")
    for (q, ty, cs) <- lesson.questions do println(s"    question: $q → ${Dialogue.question(ty, cs, Language.forQuestion(q).headOption.getOrElse(Language.En.language))}")
    java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/book-gdp-organ.json"), OrganFile.json(lesson.organ))
    def run(q: Query): Either[String, Table] = q match
      case r: Query.Relational => Exact.run(tables, r)
      case l: Query.Logical => Logic.run(tables, l)
    type S[X] = Strategy.Rest[Organs][X]
    val arms: Vector[(String, Vector[Language] => Fill[S])] = Vector(
      "the data alone, without the book" -> (_ => Fill.exact[S]),
      "the data with the book's dictionary" -> (_ => Fill.exact[S]),
      "the dictionary, then the book's organ" -> (_ => Fill.first[S](Fill.exact[S], Organs.fill[S](Vector(lesson.organ.name)))))
    for ((arm, fillOf), i) <- arms.zipWithIndex do
      var right = 0; var open = 0; var wrong = 0
      for q <- RealBench.questions; text <- Vector(q.en, q.ru) do
        val langs0 = Language.forQuestion(text)
        val langs = if i == 0 then langs0 else book.language +: langs0
        val ctx = Context(s, text, Term.Hole(Ty.Program), words, langs)
        val term = okay.scala3.run(handle(Strategy.greedyIn[Organs](g, ctx, fillOf(langs)), Organs.byWeights[Pure, (List[Strategy.Step], Option[Term])](Vector(lesson.organ))))._2
        val golds = q.golds.flatMap(gd => Query.parse(gd).flatMap(run).toOption)
        val got = term.flatMap(t => Den.run(s, t).toOption)
        if got.exists((_, r) => golds.exists(gl => Sample.same(r, gl))) then right += 1 else if term.isEmpty then open += 1 else wrong += 1
      println(s"$arm: right $right, open $open, wrong $wrong of ${RealBench.questions.size * 2}")
