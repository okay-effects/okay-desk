package okay.desk.puzzle

import okay.effects.json.Json
import okay.inlay.{Cat, Compile, Forward, Interpret, Layer, Lookup, Many, Program, Val}

/** Sourced, complete property snapshots. Absence of a snapshot is unknown, never false. The model supplies no facts: a
  * compiled okay-inlay lookup selects the subjects; the solver receives an ordinary constraint.
  */
object World:
  final case class Fact(subject: String, property: String, values: Vector[String], source: String, date: String)
  final case class Need(sort: String, property: String, value: String, withValue: Ref)
  final case class Resolved(constraint: Constraint, evidence: Vector[Fact])

  val MaxFacts = 64
  private val English = """(?i)^The one whose (.+?) is (.+?) goes with (.+?)\.?$""".r
  private val Russian = """(?i)^Тот, у кого (.+?) — (.+?), вместе с (.+?)\.?$""".r

  /** The sort is named explicitly: `The one whose countries/capital is Paris goes with dog.` */
  def read(text: String, schema: PuzzleDomain.Schema): Either[String, Need] =
    val parts = text match
      case English(p, v, a) => Some((p, v, a))
      case Russian(p, v, a) => Some((p, v, a))
      case _                => None
    parts.toRight("world clue not read").flatMap { (path, v, a) =>
      val keys = path.split("/", 2).map(_.trim)
      if keys.length != 2 then Left("name the sort and property as sort/property")
      else
        val refs = schema.puzzle.refs.filter(r => schema.all(r.value).exists(_.equalsIgnoreCase(a.trim)))
        for
          sort <- schema.puzzle.sort(keys(0).toLowerCase).toRight(s"unknown sort: ${keys(0)}")
          target <- refs match
            case Vector(r) => Right(r)
            case _         => Left(s"expected one declared value: $a")
          _ <- Either.cond(sort.name != target.sort, (), "the world clue must connect different sorts")
        yield Need(sort.name, keys(1), v.trim, target)
    }

  def isClue(text: String): Boolean =
    val t = text.toLowerCase
    t.startsWith("the one whose ") || t.startsWith("тот, у кого ")

  /** Compile the inverse property lookup, gathering ALL matching subjects rather than averaging them. */
  private def matching(facts: Vector[Fact], property: String, value: String, weights: Boolean): Set[String] =
    val rows = facts.flatMap(f => f.values.map(v => (f.subject, f.property, v))).distinct
    if rows.isEmpty then Set.empty
    else
      val subject = Cat("subject", rows.map(_._1).distinct)
      val prop = Cat("property", (rows.map(_._2) :+ property).distinct)
      val obj = Cat("value", (rows.map(_._3) :+ value).distinct)
      val askProp = Cat("askProperty", prop.values)
      val askValue = Cat("askValue", obj.values)
      val found = Many("found", subject.values)
      val program = Program(
        Seq(subject, prop, obj, askProp, askValue),
        Seq(Layer(heads = Seq(Lookup(Seq(found -> subject), Seq(askProp, askValue), Seq(prop, obj), all = true)))),
        maxLen = rows.size + 2
      )
      val inputs = rows.map { (s, p, v) =>
        Map("subject" -> Val.C(Some(s)), "property" -> Val.C(Some(p)), "value" -> Val.C(Some(v)))
      } :+ Map("askProperty" -> Val.C(Some(property)), "askValue" -> Val.C(Some(value)))
      val output = if weights then Forward.run(program, Compile(program), inputs) else Interpret.run(program, inputs)
      output.last("found") match
        case Val.M(vs) => vs
        case _         => Set.empty

  def resolve(need: Need, puzzle: Puzzle, facts: Vector[Fact], weights: Boolean = true): Either[String, Resolved] =
    puzzle.sort(need.sort).toRight(s"unknown sort: ${need.sort}").flatMap { sort =>
      val evidence = facts.filter(f => sort.values.contains(f.subject) && f.property == need.property).distinct
      val missing = sort.values.filterNot(v => evidence.exists(_.subject == v))
      val conflicts = evidence
        .groupBy(_.subject)
        .collect {
          case (s, fs) if fs.map(_.values.toSet).distinct.size > 1 => s
        }
        .toVector
        .sorted
      if !puzzle.refs.contains(need.withValue) || need.withValue.sort == need.sort then
        Left("world clue needs a declared value of another sort")
      else if missing.nonEmpty then Left(s"world facts missing: ${missing.mkString(", ")} / ${need.property}")
      else if conflicts.nonEmpty then Left(s"world facts disagree: ${conflicts.mkString(", ")}")
      else if evidence.size > MaxFacts || evidence.map(_.values.size).sum > MaxFacts then
        Left(s"world lookup capacity exceeded ($MaxFacts values)")
      else
        matching(evidence, need.property, need.value, weights).toVector.sorted match
          case Vector(subject) =>
            Right(Resolved(Constraint.Same(Ref(need.sort, subject), need.withValue), evidence))
          case Vector() => Left(s"no world fact matches ${need.property} = ${need.value}")
          case many     => Left(s"world clue is ambiguous: ${many.mkString(", ")}")
    }

  /** Imports are atomic and bounded; every record must carry its source and snapshot date. `values` is the complete set
    * for that subject/property at that source, including an explicitly empty set.
    */
  def parse(text: String): Either[String, Vector[Fact]] =
    def fail(message: String): Nothing = throw new IllegalArgumentException(message)
    try
      if text.length > 131072 then fail("world fact file is too large")
      val facts = Json.fromText(text) match
        case Json.Arr(rows) if rows.size <= MaxFacts =>
          rows.map {
            case Json.Obj(fields) =>
              val m = fields.toMap
              def str(key: String): String = m.get(key) match
                case Some(Json.Str(s)) if s.trim.nonEmpty => s.trim
                case _                                    => fail(s"world fact needs $key")
              val vs = m.get("values") match
                case Some(Json.Arr(xs)) =>
                  xs.map {
                    case Json.Str(s) if s.trim.nonEmpty => s.trim
                    case _                              => fail("world fact values must be nonempty strings")
                  }.toVector
                    .distinct
                case _ => fail("world fact needs a complete values array")
              val source = str("source")
              if !source.startsWith("https://") then fail("world fact source must be an HTTPS address")
              val date = str("date")
              if !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") then fail("world fact date must be YYYY-MM-DD")
              Fact(str("subject"), str("property"), vs, source, date)
            case _ => fail("world facts must be objects")
          }.toVector
        case _ => fail(s"world facts must be an array of at most $MaxFacts records")
      if facts.map(_.values.size).sum > MaxFacts then fail(s"at most $MaxFacts world values")
      Right(facts)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("invalid world facts"))

  def json(facts: Vector[Fact]): String = Json.print(
    Json.Arr(
      facts.map(f =>
        Json.obj(
          "subject" -> Json.Str(f.subject),
          "property" -> Json.Str(f.property),
          "values" -> Json.Arr(f.values.map(Json.Str(_))),
          "source" -> Json.Str(f.source),
          "date" -> Json.Str(f.date)
        )
      )
    )
  )

  /** A dated, manually checked current-capital snapshot, not all historical P36 statements. */
  val example: Vector[Fact] = Vector(
    Fact("France", "capital", Vector("Paris"), "https://www.wikidata.org/wiki/Q142", "2026-11-08"),
    Fact("Germany", "capital", Vector("Berlin"), "https://www.wikidata.org/wiki/Q183", "2026-11-08")
  )
