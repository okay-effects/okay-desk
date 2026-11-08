package okay.desk.learn

import okay.desk.Space
import okay.desk.calc.*
import okay.desk.organ.{Organ, Organs}
import okay.scala3.*

/**
 * A BOOK READ ON PURPOSE (BACKLOG D 24, 26; specs/organs.md §9): the documentation of a dataset — its data
 * dictionary (what each table, column and value is called, in any language) and its example questions — read into
 * (1) a LANGUAGE record the exact fill uses at once (a name's synonyms are forms of it: the data decides where the
 * question writes one), (2) PAIRS: the examples the data alone closes with the dictionary, (3) QUESTIONS: the
 * examples it leaves open — curiosity: what the reader must ask a person or a model, and (4) an ORGAN induced from
 * the pairs. Nothing is guessed: a pair is a reading the data closed, the organ is law-checked, the open examples
 * stay questions. The format is a few lines a person writes beside the data:
 *
 *     # gdp
 *     table gdp: GDP, gross domestic product; ru: ВВП, валовой внутренний продукт
 *     column Value: GDP, value, amount; ru: ВВП, значение
 *     column Year: year; ru: год, году
 *     value Ukraine: ru: Украина, Украины
 *     example: Where does GDP grow fastest?
 *     example: Какой был ВВП Украины в 2020 году?
 */
object Book:
  final case class Dictionary(code: String, tables: Map[String, Vector[String]], columns: Map[String, Vector[String]],
      values: Map[String, String], examples: Vector[String]):
    /** the dictionary as a language record: no words of constructors, only the names' forms */
    def language: Language = Language(code, Map.empty, Map.empty, tables, columns, values, 2, Map.empty)

  private val Line = """^(table|column|value)\s+([^:]+):\s*(.*)$""".r
  /** `en forms; ru: forms` → every language's forms, lower-cased, letters only */
  private def forms(text: String): Vector[String] =
    text.split(";").toVector.flatMap(part => part.split(":").last.split(",").map(_.trim.toLowerCase).filter(_.matches("(?U)[\\p{L}\\p{N} -]+")))

  def parse(text: String, code: String = "book"): Dictionary =
    var tables = Map.empty[String, Vector[String]]; var columns = Map.empty[String, Vector[String]]
    var values = Map.empty[String, String]; var examples = Vector.empty[String]
    for line <- text.linesIterator.map(_.trim) if line.nonEmpty && !line.startsWith("#") do
      line match
        case Line("table", name, rest) => tables = tables.updated(name.trim, forms(rest))
        case Line("column", name, rest) => columns = columns.updated(name.trim, forms(rest))
        case Line("value", name, rest) => forms(rest).headOption.foreach(f => values = values.updated(name.trim, f))
        case l if l.startsWith("example:") => examples :+= l.stripPrefix("example:").trim
        case _ => ()
    Dictionary(code, tables, columns, values, examples)

  /** what a reading of the book yields */
  final case class Lesson(pairs: Vector[(String, Term)], questions: Vector[(String, Ty, Vector[String])], organ: Organ):
    def text: String = s"${pairs.size} examples read by the data with the dictionary, ${questions.size} left as questions; " +
      s"the organ: ${organ.cues.size} cues over ${organ.vocabulary.size} stems"

  /** THE BOOK READ: the examples by the data alone with the dictionary — closed ones are pairs, open ones questions
   * (the hole and its options); the organ induced from the pairs (per language) */
  def read(s: Space.Schema, d: Dictionary, languages: Vector[Language] = Vector(Language.En.language, Language.Ru.language),
      words: okay.desk.skill.Words = okay.desk.skill.Words.none): Lesson =
    val g = new Grammar(s)
    val langs = d.language +: languages
    val read = d.examples.map { q =>
      val ctx = Context(s, q, Term.Hole(Ty.Program), words, langs)
      val out = run(Dialogue.read[Pure](g, ctx, Fill.exact[Strategy.Rest[Pure]]))
      q -> out
    }
    val pairs = read.collect { case (q, Dialogue.Outcome.Closed(t)) => (q, t) }
    val questions = read.collect { case (q, Dialogue.Outcome.Open(ty, _, cs, _)) => (q, ty, cs) }
    val organ = Organ.induce(s"book-${d.code}", pairs.map((q, t) =>
      (Organ.words(q, Language.forQuestion(q, c => langs.find(_.code == c)).headOption.getOrElse(Language.En.language)), Organs.templateOf(t))), minCover = 1)
    Lesson(pairs, questions, organ)
