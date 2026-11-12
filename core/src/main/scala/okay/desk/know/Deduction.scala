package okay.desk.know

import okay.inlay.Horn
import Knowledge.{Quote, Source}

/** A common, bounded fact/rule language. Readings are deliberately explicit: an exact source span is parsed into a
  * typed premise before inlay sees it. A citation alone never licenses an arbitrary model-authored proposition.
  */
object Deduction:
  final case class Atom(subject: String, category: String, negative: Boolean = false, relation: String = "is-a"):
    def opposite: Atom = copy(negative = !negative)
    def show: String =
      val article = if "aeiou".contains(category.head) then "an" else "a"
      if relation == "is-a" then s"$subject is ${if negative then "not " else ""}$article $category"
      else s"$subject ${if negative then "not: " else ""}${Relations.phrases(relation)} $category"
  enum Meaning:
    case Fact(atom: Atom)
    case Every(from: String, to: String, negative: Boolean)
    case If(premises: Vector[Atom], conclusion: Atom)
    case Chain(first: String, second: String, result: String)
    case Transfer(first: String, result: String)
  final case class Reading(meaning: Meaning, evidence: Quote)
  final case class Request(goal: Atom, evidence: Vector[Quote])
  enum Status:
    case Proven, Refuted, Unknown, Conflict
  final case class Line(atom: Atom, premises: Vector[Atom], evidence: Quote)
  final case class Result(goal: Atom, status: Status, proof: Vector[Line], readings: Vector[Reading],
      answers: Vector[Atom] = Vector.empty)

  private val Noun = "[\\p{L}\\p{N}_][\\p{L}\\p{N}_ -]{0,79}"
  private val FactEn = s"(?i)^($Noun) is (not )?(?:an? )?($Noun)$$".r
  private val EveryEn = s"(?i)^Every ($Noun) is (not )?(?:an? )?($Noun)$$".r
  private val EveryRu = s"(?iU)^(?:Каждый|Каждая|Каждое) ($Noun) [—–] (не )?($Noun)$$".r
  private val FactRu = s"^($Noun) [—–] (не )?($Noun)$$".r
  private val AskEn = s"(?i)^Is ($Noun?) (not )?an? ($Noun)\\?$$".r
  private val AskRu = s"(?iU)^(?:Верно ли, что )?($Noun) [—–] (не )?($Noun)\\?$$".r
  private def name(s: String): String = s.trim.toLowerCase
  private def atom(s: String, n: String, c: String): Atom = Atom(name(s), name(c), n != null)

  private def canonicalCategory(text: String): Boolean =
    val c = text.toLowerCase
    !Vector(" of ", " in ", " around ", " with ").exists(c.contains) &&
      !Vector("the ", "столица ", "часть ", "родитель ").exists(c.startsWith)

  def meaning(text: String): Option[Meaning] =
    val plain = text.trim.stripSuffix(".").trim
    plain match
      case _ if !SourceText.assertive(text) => None
      case s if Relations.rule(s).nonEmpty => Relations.rule(s)
      case EveryEn(a, n, b) if canonicalCategory(b) => Some(Meaning.Every(name(a), name(b), n != null))
      case EveryRu(a, n, b) if canonicalCategory(b) => Some(Meaning.Every(name(a), name(b), n != null))
      case s if s.toLowerCase.startsWith("if ") && s.toLowerCase.contains(", then ") =>
        val at = s.toLowerCase.indexOf(", then ")
        val premises = s.substring(3, at).split("(?i) and ").toVector.map(meaning)
        val conclusion = meaning(s.substring(at + 7))
        val facts = premises.collect { case Some(Meaning.Fact(a)) => a }
        conclusion.collect {
          case Meaning.Fact(a) if facts.nonEmpty && facts.size <= 8 && facts.size == premises.size =>
            Meaning.If(facts.distinct, a)
        }
      case FactEn(s, n, c) if canonicalCategory(c) &&
          !Vector("every ", "each ", "any ", "all ", "some ", "no ", "not ", "the class ", "it ")
            .exists(s.toLowerCase.startsWith) &&
          !Vector("member of ", "instance of ", "outside ", "classified as ", "included in ",
            "located in ", "the capital of ", "part of ", "parent of ", "a parent of ")
            .exists(c.toLowerCase.startsWith) =>
        Some(Meaning.Fact(atom(s, n, c)))
      case FactRu(s, n, c) if canonicalCategory(c)                 => Some(Meaning.Fact(atom(s, n, c)))
      case _                                                      => Relations.rule(text)

  def goal(question: String): Option[Atom] =
    Relations.goal(question).orElse {
      SourceText.sentences(question.linesIterator.toVector.lastOption.getOrElse(question)).lastOption
        .map(_.trim).flatMap {
      case AskEn(s, n, c) => Some(atom(s, n, c))
      case AskRu(s, n, c) => Some(atom(s, n, c))
      case _              => None
      }
    }

  def questionSource(question: String): Source =
    Source("input:question", "Question premises", "", question, "this question")

  /** Whole sentences only. Unrecognized text stays unread; it never silently becomes a fact. */
  def read(source: Source): Vector[Reading] =
    SourceText.sentences(source.text).filter(_.endsWith(".")).flatMap { span =>
      meaning(span).map(Reading(_, Quote(source.id, span)))
    }

  def collected(question: String, sources: Vector[Source], learned: Vector[FactReader.Reading])
      : Either[String, Vector[Reading]] =
    val all = questionSource(question) +: sources
    val spans = all.flatMap(FactReader.sentences).toSet
    if learned.size > 24 || learned.map(r => Quote(r.source, r.quote)).distinct.size != learned.size then
      Left("too many or duplicate model readings")
    else if learned.exists(r => !spans(Quote(r.source, r.quote))) then
      Left("model reading is not a whole collected source sentence")
    else
      learned.foldLeft[Either[String, Vector[Reading]]](Right(Vector.empty)) { (acc, r) =>
        for before <- acc; m <- FactReader.meaning(r) yield before :+ Reading(m, Quote(r.source, r.quote))
      }.map(rs => (rs ++ all.flatMap(read)).distinctBy(_.evidence))

  def plan(question: String, sources: Vector[Source], learned: Vector[FactReader.Reading] = Vector.empty)
      : Option[Request] =
    goal(question).map { g =>
      val readings = collected(question, sources, learned).getOrElse(Vector.empty)
      var needed = Set(g.relation)
      var changed = true
      while changed do
        val before = needed
        readings.foreach {
          case Reading(Meaning.Chain(a, b, c), _) if needed(c) => needed ++= Set(a, b)
          case Reading(Meaning.Transfer(a, b), _) if needed(b) => needed += a
          case _ => ()
        }
        changed = before != needed
      val relevant = readings.filter {
        case Reading(Meaning.Fact(a), _) => needed(a.relation)
        case Reading(Meaning.Chain(_, _, result), _) => needed(result)
        case Reading(Meaning.Transfer(_, result), _) => needed(result)
        case _ => needed("is-a")
      }
      Request(g, relevant.map(_.evidence).distinct)
    }

  /** The goal must be a reading of the actual question. Every rule and fact must come from a recognized, exact source
    * span; the model supplies neither axioms from memory nor an unverifiable prose conclusion.
    */
  def run(
      question: String,
      sources: Vector[Source],
      request: Request,
      compiled: Boolean = true,
      learned: Vector[FactReader.Reading] = Vector.empty
  ): Either[String, Result] =
    if !goal(question).contains(request.goal) then return Left("deduction goal is not the question's reading")
    if sources.size > 12 || sources.map(_.id).distinct.size != sources.size || sources.exists(_.id == "input:question")
    then return Left("deduction needs distinct collected sources; the question source is reserved")
    if request.evidence.size > 24 then return Left("deduction accepts at most 24 source readings")
    val externalQuotes = request.evidence.distinct.filterNot(_.source == "input:question")
    if externalQuotes.exists(_.text.length > 600) || externalQuotes
        .groupBy(_.source)
        .values
        .exists(qs => qs.map(_.text.split("\\s+").length).sum > 100)
    then return Left("deduction source quotation budget exceeded")
    val collectedReadings = collected(question, sources, learned) match
      case Left(error) => return Left(error)
      case Right(rs) => rs
    val bySpan = collectedReadings.map(r => r.evidence -> r).toMap
    if request.evidence.exists(q => !bySpan.contains(q)) then
      return Left("deduction evidence has no exact typed reading")
    val readings = request.evidence.distinct.map(bySpan)
    val subjects = (Vector(request.goal.subject) ++ readings.flatMap {
      case Reading(Meaning.Fact(a), _)   => Vector(a.subject)
      case Reading(Meaning.If(ps, c), _) => ps.map(_.subject) :+ c.subject
      case _                             => Vector.empty
    }).distinct
    val seeds = readings.collect { case r @ Reading(Meaning.Fact(a), _) => a -> r.evidence }
    val directRules = readings.flatMap {
      case Reading(Meaning.Every(a, b, n), q) => subjects.map(s => (Vector(Atom(s, a)), Atom(s, b, n), q))
      case Reading(Meaning.If(ps, c), q)      => Vector((ps, c, q))
      case _                                  => Vector.empty
    }
    // Enumerate only structurally possible rule instances. Truth is computed by the compiled Horn block below.
    var rules = directRules
    var possible = (seeds.map(_._1) ++ directRules.flatMap((ps, c, _) => ps :+ c)).distinct
    var changed = true
    while changed do
      val generated = readings.flatMap {
        case Reading(Meaning.Transfer(first, result), quote) =>
          possible.filter(a => a.relation == first && !a.negative).map(a =>
            (Vector(a), a.copy(relation = result), quote))
        case Reading(Meaning.Chain(first, second, result), quote) =>
          for
            a <- possible if a.relation == first && !a.negative
            b <- possible if b.relation == second && !b.negative && a.category == b.subject
          yield (Vector(a, b).distinct, Atom(a.subject, b.category, relation = result), quote)
        case _ => Vector.empty
      }
      val next = (rules ++ generated).distinct
      val nextAtoms = (possible ++ generated.map(_._2)).distinct
      if next.size > Horn.MaxRules || nextAtoms.size > Horn.MaxAtoms then return Left("deduction capacity exceeded")
      changed = next.size != rules.size || nextAtoms.size != possible.size
      rules = next
      possible = nextAtoms
    val atoms = (Vector(request.goal, request.goal.opposite) ++ possible).distinct
    if atoms.size > Horn.MaxAtoms || rules.size > Horn.MaxRules then return Left("deduction capacity exceeded")
    def id(a: Atom): String = atoms.indexOf(a).toString
    val hornRules = rules.zipWithIndex.map { case ((ps, c, _), i) => Horn.Rule(i.toString, ps.map(id), id(c)) }
    val block = Horn.Block(atoms.indices.map(_.toString).toVector, hornRules)
    val out = block.run(seeds.map((a, _) => id(a)).toSet, compiled)
    val finding = request.goal.subject == "?" || request.goal.category == "?"
    val answers = if !finding then Vector.empty else atoms.filter(a =>
      !a.negative && a.relation == request.goal.relation && out.known(id(a)) &&
        a.subject != "?" && a.category != "?" &&
        (request.goal.subject == "?" || a.subject == request.goal.subject) &&
        (request.goal.category == "?" || a.category == request.goal.category))
    val yes = if finding then answers.nonEmpty else out.known(id(request.goal))
    val no = if finding then answers.exists(a => atoms.contains(a.opposite) && out.known(id(a.opposite)))
      else out.known(id(request.goal.opposite))
    val status =
      if yes && no then Status.Conflict
      else if yes then Status.Proven
      else if no then Status.Refuted
      else Status.Unknown
    val targets = if finding then answers.flatMap(a => Vector(a, a.opposite))
      .filter(a => atoms.contains(a) && out.known(id(a)))
      else Vector(request.goal, request.goal.opposite).filter(a => out.known(id(a)))
    val proof = Vector.newBuilder[Line]
    var seen = Set.empty[Atom]
    val derived = out.steps.map(s => s.atom -> s).toMap
    def visit(a: Atom): Unit = if !seen(a) then
      seen += a
      seeds.find(_._1 == a) match
        case Some((_, q)) => proof += Line(a, Vector.empty, q)
        case None         =>
          derived.get(id(a)).foreach { d =>
            val (ps, _, q) = rules(d.rule.toInt)
            ps.foreach(visit)
            proof += Line(a, ps, q)
          }
    targets.foreach(visit)
    Right(Result(request.goal, status, proof.result(), readings, answers))
