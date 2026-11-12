package okay.desk.logic

import okay.desk.Table
import okay.desk.exec.Wire
import okay.desk.skill.Extract

/** Portable neural-reading data. No executable code or natural-language parser crosses the Worker boundary. */
object Protocol:
  final case class Relation(name: String, sorts: Vector[String]) derives Wire
  final case class Vocabulary(relations: Vector[Relation], constants: Map[String, Vector[String]],
      inputs: Vector[String]) derives Wire:
    def ontology: Ontology = Ontology(relations.map(r => r.name -> Predicate(r.name, r.sorts)).toMap,
      constants, inputs.toSet)

  enum Source derives Wire:
    case Text(start: Int, end: Int)
    case Cell(address: String)
    case Known

  final case class Decision(constructor: String, source: Option[Source] = None) derives Wire
  final case class Proposal(vocabulary: Vocabulary, decisions: Vector[Decision]) derives Wire
  final case class Hole(kind: String, options: Vector[String]) derives Wire
  final case class Answer(bindings: Map[String, String], fact: String, proof: Vector[String],
      inlayProof: Option[Vector[String]]) derives Wire
  final case class Result(hole: Option[Hole], rules: Option[String], goal: Option[String],
      answers: Vector[Answer]) derives Wire

  def complete(definition: Compile.Compiled, answers: Vector[Execute.Answer]): Result =
    Result(None, Some(definition.program.toString), Some(definition.goal.toString), answers.map { a =>
      Answer(a.bindings, a.fact.toString, a.proof.map(_.toString), a.inlay.map(_.proof(a.fact.toString)
        .map(step => s"${step.atom} by ${step.rule}: ${step.premises.mkString(", ")}")))
    })

  def vocabulary(ontology: Ontology): Vocabulary =
    Vocabulary(ontology.predicates.values.toVector.sortBy(_.name).map(p => Relation(p.name, p.args)),
      ontology.constants, ontology.inputs.toVector.sorted)

  def proposal(ontology: Ontology, reading: Extract.Reading): Proposal =
    Proposal(vocabulary(ontology), reading.choices.map { c =>
      Decision(c.con, c.evidence.map {
        case Extract.Evidence.Text(span) => Source.Text(span.start, span.end)
        case Extract.Evidence.Data(address) => Source.Cell(address)
        case Extract.Evidence.Known => Source.Known
      })
    })

  def run(question: String, proposal: Proposal, tables: Map[String, Table]): Either[String, Result] =
    val v = proposal.vocabulary
    val names = v.relations.map(_.name)
    if question.length > 32000 || proposal.decisions.size > 512 || v.relations.size > 64 ||
        names.distinct != names || v.inputs.distinct != v.inputs || !v.inputs.forall(names.contains) ||
        v.relations.exists(r => r.name.isEmpty || r.name.length > 100 || r.sorts.size > 8 ||
          r.sorts.exists(s => !v.constants.contains(s))) || v.constants.size > 64 ||
        v.constants.valuesIterator.map(_.size).sum > 512 || v.constants.valuesIterator.flatten.exists(_.length > 200)
    then Left("invalid or oversized logic vocabulary/reading")
    else
      val decisions = proposal.decisions.foldLeft[Either[String, Vector[Extract.Choice]]](Right(Vector.empty)) {
        (acc, d) => acc.flatMap { choices =>
          val evidence: Either[String, Option[Extract.Evidence]] = d.source match
            case None => Right(None)
            case Some(Source.Known) => Right(Some(Extract.Evidence.Known))
            case Some(Source.Cell(address)) => Right(Some(Extract.Evidence.Data(address)))
            case Some(Source.Text(start, end)) =>
              if start < 0 || end <= start || end > question.length then Left("source span outside the question")
              else Right(Some(Extract.Evidence.Text(Extract.Span(start, end, question.substring(start, end)))))
          evidence.map(e => choices :+ Extract.Choice(d.constructor, e))
        }
      }
      decisions.flatMap { choices =>
        Execute.run(LogicGrammar(v.ontology), Extract.Reading(question, choices), tables).map {
          case Execute.Outcome.Open(hole) =>
            Result(Some(Hole(hole.ty.toString, hole.options)), None, None, Vector.empty)
          case Execute.Outcome.Complete(definition, answers) =>
            complete(definition, answers)
        }
      }
