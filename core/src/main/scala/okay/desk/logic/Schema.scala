package okay.desk.logic

import okay.desk.calc.{Term, Ty}
import okay.desk.calc.Term.*

/**
 * THE SCHEMA OF A PROGRAM (specs/logic.md §5.6): the form a task asks for with the names it asks about taken out. A
 * schema is the program's term with every constant replaced by a hole, so a schema is a partial point of the grammar
 * — the same grammar, the same safety and stratification — and instantiating it is filling its holes in order.
 *
 * The point of the split is the decision budget: a program spelled constructor by constructor costs about 34
 * decisions (specs/logic.md §5.5), while a schema library makes one decision for the form and one pointer read per
 * constant. The word is the schema's identity: variables are already alpha-canonical, so two tasks share a schema
 * exactly when their words agree with the constants abstracted — no renaming, no normal form.
 *
 * A slot is not free to fill: a constant is offered only where the ontology's sort allows it, and the filled term is
 * checked to be a point of the grammar.
 */
final case class Schema(term: Term, slots: Vector[Ty], word: Vector[String]):
  def size: Int = slots.size

object Schema:
  /** the constants of a term, in the order the slots take them */
  def values(t: Term): Vector[String] = t match
    case Leaf(ty @ (Ty.ArgT(_, _, _, _) | Ty.TermT(_)), v) if !LogicGrammar.isVar(v) && v.nonEmpty => Vector(v)
    case Con(_, _, args) => args.flatMap(values)
    case _ => Vector.empty

  /** the constructor word with the constants abstracted to `#`: what two tasks of the same schema share */
  def word(t: Term): Vector[String] = t match
    case Leaf(ty @ (Ty.ArgT(_, _, _, _) | Ty.TermT(_)), v) if !LogicGrammar.isVar(v) && v.nonEmpty => Vector("#")
    case Leaf(_, v) => Vector(v)
    case Con(_, name, args) => name +: args.flatMap(word)
    case Hole(_) => Vector("#")

  /** the schema of a program: every constant becomes a hole of its own type, in preorder */
  def of(t: Term): Schema =
    def go(x: Term, acc: Vector[Ty]): (Term, Vector[Ty]) = x match
      case Leaf(ty @ (Ty.ArgT(_, _, _, _) | Ty.TermT(_)), v) if !LogicGrammar.isVar(v) && v.nonEmpty =>
        (Hole(ty), acc :+ ty)
      case Con(ty, name, args) =>
        val (as, slots) = args.foldLeft((Vector.empty[Term], acc)) { case ((xs, s), a) =>
          val (b, s2) = go(a, s)
          (xs :+ b, s2)
        }
        (Con(ty, name, as), slots)
      case other => (other, acc)
    val (term, slots) = go(t, Vector.empty)
    Schema(term, slots, word(t))

  /** the schema library of a set of programs, in the order the schemas are first seen */
  def library(ts: Vector[Term]): Vector[Schema] =
    ts.map(of).foldLeft(Vector.empty[Schema]) { (lib, s) =>
      if lib.exists(_.word == s.word) then lib else lib :+ s
    }

  /** whether the library holds the schema */
  def covered(lib: Vector[Schema], s: Schema): Boolean = lib.exists(_.word == s.word)

  /** the schema's name, for a report and for a classifier's label: its word */
  def name(s: Schema): String = s.word.mkString(" ")

  /** fill the schema's slots with constants, leftmost first: a constant the grammar does not offer at the hole is
   * refused, so an instantiated schema is a point of the grammar or nothing */
  def instantiate(g: LogicGrammar, s: Schema, cons: Vector[String]): Either[String, Term] =
    if cons.size != s.slots.size then Left(s"${cons.size} constants for ${s.slots.size} slots")
    else
      cons.foldLeft[Either[String, Term]](Right(s.term)) { (acc, c) =>
        acc.flatMap { t =>
          g.nextHole(t) match
            case None => Left(s"no hole left for $c")
            case Some((ty, _, put)) =>
              if g.constructors(ty).contains(c) then Right(put(g.apply(ty, c)))
              else Left(s"$c is not offered at $ty")
        }
      }
