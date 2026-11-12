package okay.desk.logic

import okay.datalog.{Atom, Eval, Fact, Literal, Program, Step, Term as DTerm}
import okay.desk.{Logic, Table}
import okay.desk.skill.Extract
import okay.inlay.Horn

/** Exact boundary after neural reading. An open reading never becomes an executable guess. */
object Execute:
  final case class Answer(bindings: Map[String, String], fact: Fact, proof: Vector[Step],
      inlay: Option[Horn.Result])
  enum Outcome:
    case Open(hole: Extract.Ask)
    case Complete(definition: Compile.Compiled, answers: Vector[Answer])

  def run(grammar: LogicGrammar, reading: Extract.Reading,
      tables: Map[String, Table] = Map.empty): Either[String, Outcome] =
    for
      term <- Extract.replay(grammar, reading)
      result <- Extract.next(grammar, term) match
        case Some(hole) => Right(Outcome.Open(hole))
        case None =>
          val badSource = reading.choices.exists(c => c.evidence.exists {
            case Extract.Evidence.Data(source) => !Extract.dataHolds(tables, source, c.con)
            case _ => false
          })
          if badSource then Left("a copied value has no matching source cell")
          else Compile.program(term).flatMap(definition(tables, _))
    yield result

  /** Existing model-authored query programs use the same proof boundary, without fabricating a neural reading. */
  def definition(tables: Map[String, Table], definition: Compile.Compiled): Either[String, Outcome.Complete] =
    val input = Logic.facts(tables)
    for
      evaluated <- Eval.run(definition.program, input, proofs = true)
      reference <- Eval.naive(definition.program, input)
      _ <- Either.cond(evaluated.predicates == reference.predicates && evaluated.predicates.forall(p =>
        evaluated.relation(p).toSet == reference.relation(p).toSet), (), "Datalog handlers disagree")
      answers <- evaluated.query(definition.goal).foldLeft[Either[String, Vector[Answer]]](Right(Vector.empty)) {
        (acc, bindings) => acc.flatMap { previous =>
          val fact = ground(definition.goal, bindings)
          for
            proof <- evaluated.proof(fact).toRight(s"no proof for $fact")
            _ <- verifyAgainst(definition.program, input, fact, proof, reference)
            compiled <- horn(fact, proof)
          yield previous :+ Answer(bindings, fact, proof, compiled)
        }
      }
    yield Outcome.Complete(definition, answers)

  private def ground(atom: Atom, bindings: Map[String, String]): Fact =
    Fact(atom.pred, atom.args.map {
      case DTerm.Const(value) => value
      case DTerm.Var(name) => bindings(name)
    })

  /** Check each rule instance, provenance and order. Absence is checked in the complete stratified closure,
    * never against a partial proof where a missing premise could be mistaken for a negative fact.
    */
  def verify(program: Program, input: Vector[Fact], goal: Fact, proof: Vector[Step]): Either[String, Unit] =
    Eval.naive(program, input).flatMap(verifyAgainst(program, input, goal, proof, _))

  private def verifyAgainst(program: Program, input: Vector[Fact], goal: Fact, proof: Vector[Step],
      closure: okay.datalog.Result): Either[String, Unit] =
      val givenFacts = input.toSet ++ program.rules.filter(_.body.isEmpty).map(r => ground(r.head, Map.empty))
      var earlier = Set.empty[Fact]
      def valid(step: Step): Boolean = step.by match
        case None => givenFacts(step.fact)
        case Some(derivation) =>
          val rule = derivation.rule
          val positive = rule.body.collect { case Literal.Pos(atom) => atom }
          var bindings = Map.empty[String, String]
          def unify(atom: Atom, fact: Fact): Boolean =
            atom.pred == fact.pred && atom.args.size == fact.args.size && atom.args.zip(fact.args).forall {
              case (DTerm.Const(value), actual) => value == actual
              case (DTerm.Var(name), actual) =>
                val agrees = bindings.get(name).forall(_ == actual)
                bindings = bindings.updated(name, actual)
                agrees
            }
          def value(term: DTerm): String = term match
            case DTerm.Const(v) => v
            case DTerm.Var(n) => bindings(n)
          def compare(op: String, left: DTerm, right: DTerm): Boolean =
            val a = value(left)
            val b = value(right)
            val order = (a.toDoubleOption, b.toDoubleOption) match
              case (Some(x), Some(y)) => java.lang.Double.compare(x, y)
              case _ => a.compareTo(b)
            op match
              case "=" => a == b
              case "!=" => a != b
              case "<" => order < 0
              case "<=" => order <= 0
              case ">" => order > 0
              case ">=" => order >= 0
              case _ => false
          program.rules.contains(rule) && rule.body.nonEmpty &&
            positive.size == derivation.premises.size && derivation.premises.forall(earlier) &&
            positive.zip(derivation.premises).forall(unify) && unify(rule.head, step.fact) && {
              val needed = rule.body.flatMap(_.vars).distinct ++ rule.head.vars
              needed.forall(bindings.contains) &&
                rule.body.collect { case Literal.Neg(a) => ground(a, bindings) } == derivation.absent &&
                derivation.absent.forall(f => !closure.contains(f)) &&
                rule.body.forall {
                  case Literal.Cmp(op, a, b) => compare(op, a, b)
                  case _ => true
                }
            }
      val validProof = proof.forall { step =>
        val ok = !earlier(step.fact) && valid(step)
        earlier += step.fact
        ok
      }
      Either.cond(validProof && proof.lastOption.exists(_.fact == goal) && closure.contains(goal), (),
        "the answer's proof does not replay against its definition and source facts")

  /** Independently run the checked positive ground witness through compiled inlay weights. No claim of
    * inlay support for negation, comparisons or graphs outside its declared bounds is made.
    */
  private def horn(goal: Fact, proof: Vector[Step]): Either[String, Option[Horn.Result]] =
    val atoms = proof.map(_.fact.toString).distinct
    val derived = proof.flatMap(_.by)
    if atoms.isEmpty || atoms.size > Horn.MaxAtoms || derived.size > Horn.MaxRules || derived.exists(d =>
        d.premises.isEmpty || d.premises.distinct.size > 8 || d.rule.body.exists {
          case Literal.Pos(_) => false
          case _ => true
        }) then Right(None)
    else
      val rules = proof.zipWithIndex.flatMap { (step, i) =>
        step.by.map(d => Horn.Rule(s"proof $i", d.premises.map(_.toString).distinct, step.fact.toString))
      }
      val seeds = proof.filter(_.by.isEmpty).map(_.fact.toString).toSet
      val result = Horn.Block(atoms, rules).run(seeds)
      Either.cond(result.known == atoms.toSet && result.known(goal.toString), Some(result),
        "compiled inlay does not reproduce the checked positive proof")
