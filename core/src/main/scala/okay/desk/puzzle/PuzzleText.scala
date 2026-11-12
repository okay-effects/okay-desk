package okay.desk.puzzle

import okay.inlay.domain.Kit
import okay.desk.know.SourceText

/**
 * A PUZZLE AS TEXT (specs/puzzles.md §4, §6): a header declaring the sorts — `Colours: red (red house; красный дом,
 * красном доме), green (…), …` — a value's forms in brackets, `;` between languages, `,` between forms; the positions'
 * count from the first sort's size; then one clue per line; a line beginning with a question mark or a question word
 * is the question. `read` turns the clues into constraints by a reader and the copier, keeping what each clue was
 * read as and where a clue was not read — the page's lines.
 */
object PuzzleText:
  val worldExample: String = """Countries: France, Germany
    |Pets: dog, cat
    |France is in house 1.
    |The one whose countries/capital is Paris goes with dog.
    |Which country owns dog?
    |""".stripMargin

  final case class Clue(text: String, read: Option[Constraint], form: Option[String], values: Vector[String],
    world: Boolean = false, evidence: Vector[World.Fact] = Vector.empty, problem: Option[String] = None)
  final case class Parsed(schema: PuzzleDomain.Schema, clues: Vector[Clue], question: Option[Question], problems: Vector[String],
      additionalQuestions: Vector[Question] = Vector.empty, inferred: Boolean = false):
    def puzzle: Puzzle = schema.puzzle.copy(constraints = clues.flatMap(_.read), question = question,
      additionalQuestions = additionalQuestions)
    def ready: Boolean = problems.isEmpty && clues.nonEmpty && clues.forall(_.read.nonEmpty)

  private val Header = """^\s*([\p{L} ]+?)\s*:\s*(.+)$""".r
  /** `red (red house; красный дом, красном доме)` → ("red", forms) */
  private def value(item: String): (String, Vector[String]) =
    val i = item.indexOf('(')
    if i < 0 then (item.trim, Vector.empty)
    else (item.take(i).trim, item.drop(i + 1).takeWhile(_ != ')').split("[;,]").map(_.trim).filter(_.nonEmpty).toVector)
  /** items split on commas outside brackets */
  private def items(s: String): Vector[String] =
    val out = Vector.newBuilder[String]; val cur = new StringBuilder; var depth = 0
    for c <- s do
      if c == '(' then depth += 1
      if c == ')' then depth -= 1
      if c == ',' && depth == 0 then { out += cur.toString; cur.clear() } else cur += c
    out += cur.toString
    out.result().map(_.trim).filter(_.nonEmpty)

  private val questionWords = Vector("who", "which", "what", "кто", "какой", "какая", "кому", "хто", "який", "яка", "kto", "który", "która", "co")
  private def isQuestion(line: String): Boolean =
    val l = line.trim.toLowerCase
    l.endsWith("?") || l.startsWith("?") || questionWords.exists(w => l.startsWith(w + " "))

  def parse(text: String, reader: Kit.Reader[PuzzleDomain.Schema], facts: Vector[World.Fact] = Vector.empty): Parsed =
    val lines = text.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toVector.flatMap { l =>
      l match
        case Header(_, _) if l.contains(",") => Vector(l)
        case _ =>
          val line = l.replaceFirst("^\\d+[.)]\\s+", "")
          val sentences = SourceText.sentences(line)
          if sentences.isEmpty then Vector(line) else
            val last = line.lastIndexOf(sentences.last) + sentences.last.length
            sentences ++ Option.when(line.drop(last).trim.nonEmpty)(line.drop(last).trim)
    }
    val headers = lines.collect { case Header(name, rest) if !isQuestion(name + ":" + rest) && rest.contains(",") => (name.trim, items(rest)) }
    val declared = headers.map((n, its) => (n.toLowerCase, its.map(value(_)._1)))
    val invalid = declared.flatMap { (n, vs) =>
      Option.when(vs.distinct.size != vs.size || vs.exists(_.isEmpty))(s"$n: values must be distinct and nonempty")
    } ++ Option.when(declared.map(_._1).distinct.size != declared.size)("sort names must be distinct") ++
      Option.when(declared.flatMap(_._2).distinct.size != declared.flatMap(_._2).size)(
        "value names must be distinct across sorts; use different names and aliases")
    if invalid.nonEmpty then
      val empty = PuzzleDomain.Schema(Puzzle(1, Vector(Sort("x", Vector("x"))), Vector.empty, None))
      return Parsed(empty, Vector.empty, None, invalid)
    val sorts = declared.map((n, vs) => Sort(n, vs))
    val forms = headers.flatMap((_, its) => its.map(value)).filter(_._2.nonEmpty).toMap
    val inferred = sorts.isEmpty
    val inferredSchema = if !inferred then None else GridText.infer(text) match
      case Right(schema) => Some(schema)
      case Left(problem) =>
        val empty = PuzzleDomain.Schema(Puzzle(1, Vector(Sort("x", Vector("x"))), Vector.empty, None))
        return Parsed(empty, Vector.empty, None, Vector(problem))
    val n = inferredSchema.map(_.puzzle.positions).getOrElse(sorts.head.values.size)
    val problems = sorts.filter(_.values.size != n).map(s => s"${s.name}: ${s.values.size} values, the first sort has $n")
    val even = inferredSchema.map(_.puzzle.sorts).getOrElse(sorts.filter(_.values.size == n))
    val schema = inferredSchema.getOrElse(PuzzleDomain.Schema(Puzzle(n, even, Vector.empty, None), forms))
    val rest = lines.filterNot {
      case Header(name, content) => headers.contains((name.trim, items(content)))
      case _ => false
    }
    val (questions, clueLines) = rest.filterNot(GridText.declaration).partition(isQuestion)
    val clues = clueLines.map { line =>
      if World.isClue(line) then
        World.read(line, schema).flatMap(World.resolve(_, schema.puzzle, facts)) match
          case Right(r) => Clue(line, Some(r.constraint), Some("same"), r.constraint.refs.map(_.value),
            world = true, evidence = r.evidence)
          case Left(message) => Clue(line, None, None, Vector.empty, world = true, problem = Some(message))
      else
        val r = Kit.read(PuzzleDomain)(schema, line, reader, PuzzleDomain.copier)
        Clue(line, r.point, r.parts.get("form"), Vector("a", "b").flatMap(r.parts.get))
    }
    // the question: the value written, and the sort asked — the sort whose name the question word points at, else the
    // first sort not the value's ("who" → the persons; "which house" → the colours)
    val readQuestions = questions.map { q =>
      val ql = q.toLowerCase
      PuzzleDomain.copier.found(q, schema).headOption.map(_._2).flatMap(v => schema.puzzle.sortOf(v).map(so => (v, so))).map { (v, so) =>
        val persons = even.find(s => Vector("nation", "nations", "person", "persons", "people", "name", "names", "национальность", "национальности", "люди", "кто", "имя", "imię", "narodowość", "національність").contains(s.name))
        val asked = (if questionWords.take(1).exists(ql.startsWith) || ql.startsWith("кто") || ql.startsWith("хто") || ql.startsWith("kto") then persons else None)
          .orElse(even.find(s => s.name != so.name && ql.contains(s.name.stripSuffix("s"))))
          .orElse(persons.filter(_.name != so.name)).orElse(even.find(_.name != so.name))
        Question(asked.map(_.name).getOrElse(so.name), Ref(so.name, v))
      }
    }
    val questionProblems = questions.zip(readQuestions).collect {
      case (text, None) => "question not read: " + text
    }
    val all = readQuestions.flatten
    Parsed(schema, clues, all.headOption, problems ++ questionProblems, all.drop(1), inferred)

  def label(parsed: Parsed, value: String, language: String): String =
    if language != "ru" then value else parsed.schema.forms.getOrElse(value, Vector.empty)
      .find(_.exists(c => c >= 'а' && c <= 'я'))
      .orElse(parsed.schema.forms.getOrElse(value, Vector.empty).headOption).getOrElse(value)

  /** THE CLASSIC PUZZLE AS TEXT, English and Russian (the page's pre-filled text) */
  val classicEn: String =
    """# The zebra puzzle (Life International, 1962)
      |Colours: red (red house), green (green house), ivory (ivory house), yellow (yellow house), blue (blue house)
      |Nations: Englishman (the Englishman), Spaniard (the Spaniard), Ukrainian (the Ukrainian), Norwegian (the Norwegian), Japanese (the Japanese)
      |Drinks: coffee, tea, milk, orange juice, water
      |Smokes: Old Gold, Kools, Chesterfields, Lucky Strike, Parliaments
      |Pets: dog, snails, fox, horse, zebra
      |The Englishman lives in the red house.
      |The Spaniard owns the dog.
      |Coffee is drunk in the green house.
      |The Ukrainian drinks tea.
      |The green house is immediately to the right of the ivory house.
      |The Old Gold smoker owns snails.
      |Kools are smoked in the yellow house.
      |Milk is drunk in the middle house.
      |The Norwegian lives in the first house.
      |The man who smokes Chesterfields lives in the house next to the man with the fox.
      |Kools are smoked in the house next to the house where the horse is kept.
      |The Lucky Strike smoker drinks orange juice.
      |The Japanese smokes Parliaments.
      |The Norwegian lives next to the blue house.
      |Who owns the zebra?
      |""".stripMargin

  val classicRu: String =
    """# Загадка Эйнштейна
      |Цвета: red (красный дом, красном доме, красного дома, красным домом), green (зелёный дом, зелёном доме, зелёного дома, зелёным домом), ivory (белый дом, белом доме, белого дома, белым домом), yellow (жёлтый дом, жёлтом доме, жёлтого дома, жёлтым домом), blue (синий дом, синем доме, синего дома, синим домом)
      |Национальности: Englishman (англичанин, англичанина), Spaniard (испанец, испанца), Ukrainian (украинец, украинца), Norwegian (норвежец, норвежца), Japanese (японец, японца)
      |Напитки: coffee (кофе), tea (чай), milk (молоко), orange juice (апельсиновый сок), water (воду, вода)
      |Сигареты: Old Gold (Old Gold), Kools (Kools), Chesterfields (Chesterfield), Lucky Strike (Lucky Strike), Parliaments (Parliament)
      |Животные: dog (собака, собаку), snails (улитки, улиток), fox (лиса, лису, лисой), horse (лошадь, лошадью), zebra (зебра, зебру)
      |Англичанин живёт в красном доме.
      |У испанца собака.
      |В зелёном доме пьют кофе.
      |Украинец пьёт чай.
      |Зелёный дом сразу справа от белого дома.
      |Тот, кто курит Old Gold, держит улиток.
      |В жёлтом доме курят Kools.
      |В среднем доме пьют молоко.
      |Норвежец живёт в первом доме.
      |Тот, кто курит Chesterfield, живёт рядом с тем, у кого лиса.
      |Kools курят в доме рядом с тем, где держат лошадь.
      |Тот, кто курит Lucky Strike, пьёт апельсиновый сок.
      |Японец курит Parliament.
      |Норвежец живёт рядом с синим домом.
      |Кто держит зебру?
      |""".stripMargin
