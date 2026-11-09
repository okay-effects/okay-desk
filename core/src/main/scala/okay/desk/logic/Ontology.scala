package okay.desk.logic

import okay.desk.Table

/**
 * THE ONTOLOGY of a formal model (specs/logic.md §2): the predicates the model may use, the sort of each argument,
 * the constants of each sort, and which predicates are inputs (relations given by the data or the text). Sorts type
 * the arguments — a constant belongs to one — while variables stay untyped, as Datalog has them; the grammar uses
 * the sorts to offer a constant only where it fits. A predicate not in `inputs` is derived: a rule may define it, and
 * negation may name only inputs or derived predicates whose stratum is already below the rule's head.
 */
final case class Predicate(name: String, args: Vector[String]):
  def arity: Int = args.size

final case class Ontology(predicates: Map[String, Predicate], constants: Map[String, Vector[String]],
    inputs: Set[String]):
  def predicate(name: String): Option[Predicate] = predicates.get(name)
  def sortOf(pred: String, index: Int): Option[String] =
    predicates.get(pred).flatMap(_.args.lift(index))
  /** the constants of an atom's argument, in their sort; empty when the argument has no sort or no constants */
  def constantsOf(pred: String, index: Int): Vector[String] =
    sortOf(pred, index).toVector.flatMap(s => constants.getOrElse(s, Vector.empty)).distinct

  /** the inputs as the grammar offers them (sorted, and only the predicates that exist) */
  def inputPredicates: Vector[String] = inputs.toVector.sorted.filter(predicates.contains)
  /** the predicates a rule may define (never an input: an input is a relation given, not derived) */
  def derivedPredicates: Vector[String] = predicates.keys.toVector.sorted.filterNot(inputs.contains)
  /** every constant of every sort, for a comparison's operands (a comparison is not typed by a predicate) */
  def allConstants: Vector[String] = constants.values.toVector.flatten.distinct

object Ontology:
  val empty: Ontology = Ontology(Map.empty, Map.empty, Set.empty)

  /** a table as an input predicate: its columns' sorts are `table.column`, a sort's constants its column's values */
  def ofTables(tables: Map[String, Table]): Ontology =
    val ps = tables.values.toVector.map(t => t.name -> Predicate(t.name, t.columns.map(c => s"${t.name}.$c")))
    val cs = tables.values.toVector.flatMap { t =>
      t.columns.zipWithIndex.map { case (c, i) => s"${t.name}.$c" -> t.rows.map(_(i).text).filter(_.nonEmpty).distinct }
    }
    Ontology(ps.toMap, cs.toMap, ps.map(_._1).toSet)
