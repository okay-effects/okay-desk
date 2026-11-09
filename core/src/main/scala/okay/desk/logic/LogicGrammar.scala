package okay.desk.logic

import okay.desk.calc.{Encode, Rules, Term, Ty}
import okay.desk.calc.Term.*

/**
 * THE GRAMMAR OF THE LOGIC FRAGMENT (specs/logic.md §3): the typed term language whose closed terms are Datalog
 * programs — a goal, facts, and rules with a head and a body of positive atoms, negated atoms and comparisons. The
 * order is GOAL FIRST: a program's first holes are the question's predicate and its arguments, then the facts, then
 * the rules — the goal directs the reading instead of a post-hoc check.
 *
 * Safety is by construction: a body closes only when every variable of its head is bound by a positive atom, and a
 * negated atom or a comparison offers only variables already bound. A fact offers constants only: a variable in a
 * fact is not a fact.
 *
 * Stratification is by construction too, and it needs more than the set of defined predicates: a predicate may be
 * defined by several rules, and a later rule for an earlier predicate could otherwise close a cycle through negation.
 * So every defined predicate carries a LEVEL (an input is 0). A rule's head must sit at or above each positive
 * dependency and strictly above each negative one; a head already defined keeps its level, so a rule that would need
 * a higher one is never offered. All levels only ever go up as the program is written, which makes the final program
 * stratified — the level assignment is the certificate.
 */
final class LogicGrammar(val ontology: Ontology) extends Rules:
  import LogicGrammar.*
  import Ty.*

  private def allPredicates: Vector[String] = ontology.predicates.keys.toVector.sorted
  private def inputs: Vector[String] = ontology.inputPredicates
  private def positive(head: String, levels: Map[String, Int]): Vector[String] =
    positiveAllowed(inputs, levels, head)
  private def negative(head: String, levels: Map[String, Int]): Vector[String] =
    negativeAllowed(inputs, levels, head)

  def constructors(ty: Ty): Vector[String] = ty match
    case LogicT => Vector("Program")
    case GoalT => allPredicates
    case FactsT => Vector("nil", "cons")
    case RulesT(_) => Vector("nil", "cons")
    case RuleT(_) => if ontology.derivedPredicates.nonEmpty then Vector("rule") else Vector.empty
    // (a body closes only when the head is bound: the range restriction, by construction)
    case BodyT(_, vars, bound, _) => if vars.forall(bound.contains) then Vector("nil", "cons") else Vector("cons")
    case LitT(head, _, bound, levels) =>
      Vector.empty ++ (if positive(head, levels).nonEmpty then Vector("pos") else Vector.empty) ++
        (if negative(head, levels).nonEmpty then Vector("neg") else Vector.empty) ++
        (if bound.nonEmpty then Vector("cmp") else Vector.empty)
    case AtomT(_, _, except) => allPredicates.filterNot(except.contains)
    case ArgT(pred, index, bound, fresh) => (if fresh then pool else bound) ++ constantsAt(pred, index)
    case TermT(bound) => bound ++ allConstants
    case CmpOpT => ops
    case _ => Vector.empty

  private def constantsAt(pred: String, index: Int): Vector[String] =
    ontology.constantsOf(pred, index).filterNot(pool.contains)
  private def allConstants: Vector[String] = ontology.allConstants.filterNot(pool.contains)

  /** whether a constructor opens arguments (a node) or is a leaf */
  def opens(ty: Ty, con: String): Boolean = (ty, con) match
    case (LogicT, "Program") | (FactsT, "cons") | (RulesT(_), "cons") | (RuleT(_), "rule") => true
    case (BodyT(_, _, _, _), "cons") | (LitT(_, _, _, _), "pos") | (LitT(_, _, _, _), "neg") |
         (LitT(_, _, _, _), "cmp") => true
    case (AtomT(_, _, _), _) | (GoalT, _) => true
    case _ => false

  /** the type of the next argument of `con` (of type `ty`) given the arguments so far; None when complete */
  def next(ty: Ty, con: String, args: Vector[Term]): Option[Ty] = (ty, con) match
    case (LogicT, "Program") => args.size match
      case 0 => Some(GoalT)
      case 1 => Some(FactsT)
      // the fact predicates are relations given at level 0, like the inputs (a rule may use them positively)
      case 2 => Some(RulesT(factLevels(args(1))))
      case _ => None
    case (GoalT, pred) => ontology.predicate(pred).flatMap { p =>
      if args.size < p.arity then Some(ArgT(pred, args.size, Vector.empty, fresh = true)) else None }
    case (FactsT, "cons") => args.size match
      case 0 => Some(AtomT(Vector.empty, fresh = false, except = Vector.empty))
      case 1 => Some(FactsT)
      case _ => None
    case (RulesT(levels), "cons") => args.size match
      case 0 => Some(RuleT(levels))
      case 1 => Some(RulesT(levelsAfter(args(0), levels)))
      case _ => None
    case (RuleT(levels), "rule") => args.size match
      // a rule defines a derived predicate, never an input
      case 0 => Some(AtomT(Vector.empty, fresh = true, except = inputs))
      case 1 => Some(BodyT(predOf(args(0)), varsOf(args(0)), Vector.empty, levels))
      case _ => None
    case (BodyT(head, vars, bound, levels), "cons") => args.size match
      case 0 => Some(LitT(head, vars, bound, levels))
      case 1 => Some(BodyT(head, vars, bound ++ varsOf(args(0)), levels))
      case _ => None
    case (LitT(head, _, bound, levels), "pos") =>
      if args.isEmpty then
        Some(AtomT(bound, fresh = true, except = allPredicates.filterNot(positive(head, levels).contains)))
      else None
    case (LitT(head, _, bound, levels), "neg") =>
      if args.isEmpty then
        Some(AtomT(bound, fresh = false, except = allPredicates.filterNot(negative(head, levels).contains)))
      else None
    case (LitT(_, _, bound, _), "cmp") => args.size match
      case 0 => Some(CmpOpT)
      case 1 | 2 => Some(TermT(bound))
      case _ => None
    case (AtomT(bound, fresh, _), pred) => ontology.predicate(pred).flatMap { p =>
      if args.size < p.arity then Some(ArgT(pred, args.size, bound, fresh)) else None }
    case _ => None

  /** whether a term is a point of this grammar: each constructor was allowed where chosen, and the term is complete */
  def accepts(term: Term): Boolean =
    var cur: Term = Hole(LogicT)
    var ok = true
    for letter <- Encode.word(term) do
      nextHole(cur) match
        case Some((ty, _, put)) if constructors(ty).contains(letter) => cur = put(apply(ty, letter))
        case _ => ok = false
    ok && nextHole(cur).isEmpty

  /** the space of programs up to `maxSteps` constructors, in the grammar's order: a lazy walk, not a held space */
  def enumerate(maxSteps: Int): Iterator[Term] =
    def walk(t: Term, n: Int): Iterator[Term] =
      nextHole(t) match
        case None => Iterator.single(t)
        case Some((ty, _, put)) =>
          if n >= maxSteps then Iterator.empty
          else constructors(ty).iterator.flatMap(c => walk(put(apply(ty, c)), n + 1))
    walk(Hole(LogicT), 0)

object LogicGrammar:
  /** the variables the grammar offers; a constant with one of these names is refused (it would read as a variable) */
  val pool: Vector[String] = Vector("X", "Y", "Z", "U", "V", "W", "S", "T")
  val ops: Vector[String] = Vector("=", "!=", "<", "<=", ">", ">=")

  def isVar(value: String): Boolean = pool.contains(value)

  /** the stratum of a predicate: an input (or one not yet defined) is 0 */
  def levelOf(levels: Map[String, Int], pred: String): Int = levels.getOrElse(pred, 0)

  /** the predicates a positive body atom may name: the inputs, the defined ones at or below the head, and the head */
  def positiveAllowed(inputs: Vector[String], levels: Map[String, Int], head: String): Vector[String] =
    val known = (inputs ++ levels.keys).distinct
    levels.get(head) match
      case Some(l) => (known.filter(p => levelOf(levels, p) <= l) :+ head).distinct
      case None => (known :+ head).distinct

  /** the predicates a negated atom may name: the inputs and the defined ones strictly below the head (never itself) */
  def negativeAllowed(inputs: Vector[String], levels: Map[String, Int], head: String): Vector[String] =
    val known = (inputs ++ levels.keys).distinct
    levels.get(head) match
      case Some(l) => known.filter(p => levelOf(levels, p) < l)
      case None => known

  /** the level a rule's head needs: at or above every positive atom, strictly above every negative one */
  def needOf(rule: Term, levels: Map[String, Int]): Int = rule match
    case Con(Ty.RuleT(_), "rule", Vector(_, body)) => bodyNeed(body, levels)
    case _ => 0

  /** the predicates of the facts, as relations given at level 0 */
  def factPredicates(facts: Term): Vector[String] = facts match
    case Leaf(Ty.FactsT, "nil") => Vector.empty
    case Con(Ty.FactsT, "cons", Vector(atom, rest)) => predOf(atom) +: factPredicates(rest)
    case _ => Vector.empty

  def factLevels(facts: Term): Map[String, Int] =
    factPredicates(facts).filter(_.nonEmpty).distinct.map(_ -> 0).toMap

  /** the levels after a rule: a head seen for the first time takes the level it needs; a defined head keeps its own */
  def levelsAfter(rule: Term, levels: Map[String, Int]): Map[String, Int] =
    val head = predOf(headOfRule(rule))
    if head.isEmpty || levels.contains(head) then levels else levels + (head -> needOf(rule, levels))

  /** the head atom of a rule */
  def headOfRule(rule: Term): Term = rule match
    case Con(Ty.RuleT(_), "rule", Vector(head, _)) => head
    case other => other

  /** the predicate of an atom */
  def predOf(atom: Term): String = atom match
    case Con(Ty.AtomT(_, _, _), pred, _) => pred
    case _ => ""

  /** the variables of a term, in order of first appearance */
  def varsOf(t: Term): Vector[String] = t match
    case Leaf(Ty.ArgT(_, _, _, _), v) if isVar(v) => Vector(v)
    case Leaf(Ty.TermT(_), v) if isVar(v) => Vector(v)
    case Con(_, _, args) => args.flatMap(varsOf).distinct
    case _ => Vector.empty

  private def bodyNeed(body: Term, levels: Map[String, Int]): Int = body match
    case Con(_, "cons", Vector(lit, rest)) =>
      val here = lit match
        case Con(_, "pos", Vector(atom)) => levelOf(levels, predOf(atom))
        case Con(_, "neg", Vector(atom)) => levelOf(levels, predOf(atom)) + 1
        case _ => 0
      math.max(here, bodyNeed(rest, levels))
    case _ => 0
