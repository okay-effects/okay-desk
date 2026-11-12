package okay.desk.know

/** Registered binary meanings, their question forms, and explicitly sourced composition rules. */
object Relations:
  final case class Artifact(parameters: FactReader.Parameters, margins: Map[String, Float])
      derives okay.desk.exec.Wire
  def parse(text: String): Either[String, FactReader.Reader] =
    okay.desk.exec.Wire.decode[Artifact](text).flatMap { a =>
      try Right(FactReader.Reader(a.parameters, FactReader.Kinds.dropRight(1) ++ kinds, a.margins))
      catch case e: Exception => Left("invalid relation reader: " + e.getMessage)
    }
  val names = Vector("located-in", "capital-of", "part-of", "orbits", "parent-of", "borders")
  val kinds = names ++ names.map("not-" + _) :+ "other"
  val phrases = Map("located-in" -> "is located in", "capital-of" -> "is the capital of",
    "part-of" -> "is part of", "orbits" -> "orbits", "parent-of" -> "is a parent of", "borders" -> "borders")
  /** A registered predicate needs an explicit source cue; unrelated prose is never a relation by vector similarity. */
  def evidenced(kind: String, text: String): Boolean =
    val lower = " " + text.toLowerCase.replaceAll("[\\s\\u00a0\\u202f]+", " ") + " "
    val cues = kind.stripPrefix("not-") match
      case "located-in" => Vector(" located in ", " situated in ", " lies in ", " is in ", "country in ",
        " city in ", " found in ", "находится", "расположен")
      case "capital-of" => Vector("capital of ", "capital city of ", "capital and largest city of ",
        "capital and most populous city of ", "столица", "столицей")
      case "part-of" => Vector("part of ", "component of ", "constituent of ", " as a component", "частью")
      case "orbits" => Vector(" orbit ", " orbits ", " orbiting ", " revolves around ", " revolving around ",
        " circles ", "вращается вокруг")
      case "parent-of" => Vector(" parent ", " parents ", " mother ", " father ", " son ", " daughter ",
        " child ", "родитель")
      case "borders" => Vector(" borders ", " border with ", " common border", "along a border", " adjoins ",
        " share a border", "граничит")
      case _ => Vector.empty
    val negative = "(?iu)\\b(?:not|no|false|never|не)\\b".r.findFirstIn(lower).nonEmpty
    negative == kind.startsWith("not-") && cues.exists(lower.contains)

  def name(s: String): String = s.trim.toLowerCase.stripPrefix("the ")
  private val N = "[\\p{L}\\p{N}_][\\p{L}\\p{N}_ -]{0,79}?"
  private def matched(text: String, pattern: String): Option[(String, String)] =
    pattern.r.findFirstMatchIn(text).map(m => name(m.group(1)) -> name(m.group(2)))
  def goal(text: String): Option[Deduction.Atom] =
    val q = SourceText.sentences(text.linesIterator.toVector.lastOption.getOrElse(text)).lastOption.getOrElse(text.trim)
    val patterns = Vector(
      "located-in" -> s"(?i)^Is ($N) (?:located )?in ($N)\\?$$",
      "capital-of" -> s"(?i)^Is ($N) the capital of ($N)\\?$$",
      "part-of" -> s"(?i)^Is ($N) part of ($N)\\?$$",
      "orbits" -> s"(?i)^Does ($N) orbit ($N)\\?$$",
      "parent-of" -> s"(?i)^Is ($N) a parent of ($N)\\?$$",
      "borders" -> s"(?i)^Does ($N) border ($N)\\?$$",
      "located-in" -> s"(?iU)^Находится ли ($N) в ($N)\\?$$",
      "capital-of" -> s"(?iU)^($N) — столица ($N)\\?$$")
    patterns.iterator.flatMap { (r, p) => matched(q, p).map((a, b) => Deduction.Atom(a, b, relation = r)) }
      .nextOption().orElse {
        val find = Vector(
          ("located-in", s"(?i)^Where is ($N)\\?$$", false),
          ("capital-of", s"(?i)^What is the capital of ($N)\\?$$", true),
          ("parent-of", s"(?i)^Who is a parent of ($N)\\?$$", true),
          ("orbits", s"(?i)^What does ($N) orbit\\?$$", false),
          ("borders", s"(?i)^What borders ($N)\\?$$", true),
          ("part-of", s"(?i)^What is part of ($N)\\?$$", true),
          ("part-of", s"(?i)^What is ($N) part of\\?$$", false),
          ("located-in", s"(?iU)^Где находится ($N)\\?$$", false),
          ("capital-of", s"(?iU)^Какая столица у ($N)\\?$$", true))
        find.iterator.flatMap { (r, p, inverse) => p.r.findFirstMatchIn(q).map { m =>
          if inverse then Deduction.Atom("?", name(m.group(1)), relation = r)
          else Deduction.Atom(name(m.group(1)), "?", relation = r)
        }}.nextOption()
      }

  def research(question: String): Option[Vector[String]] = goal(question).map { g =>
    if g.relation == "capital-of" && g.subject == "?" then
      Vector(s"\"capital and largest city of ${g.category}\"", s"\"capital city of ${g.category}\"",
        s"\"capital of ${g.category}\"")
    else Vector(if g.subject == "?" then g.category else g.subject)
  }

  /** Rules are evidence, not automatic assumptions that every binary relation is transitive. */
  def rule(text: String): Option[Deduction.Meaning] =
    val plain = text.trim.stripSuffix(".").toLowerCase
    val chains = for
      first <- names.iterator
      second <- names.iterator
      result <- names.iterator
      if plain == s"if x ${phrases(first)} y and y ${phrases(second)} z, then x ${phrases(result)} z"
    yield Deduction.Meaning.Chain(first, second, result)
    chains.nextOption().orElse {
      val transfers = for
        first <- names.iterator
        result <- names.iterator
        if plain == s"if x ${phrases(first)} y, then x ${phrases(result)} y"
      yield Deduction.Meaning.Transfer(first, result)
      transfers.nextOption()
    }
