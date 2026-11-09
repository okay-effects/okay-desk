package okay.desk.logic

import okay.desk.{Logic, Query, Table}
import okay.desk.calc.{Term, Ty}
import okay.desk.calc.Term.*
import okay.datalog.{Atom, Literal, Program, Rule}

/**
 * THE DENOTATION OF A LOGIC TERM (specs/logic.md §4): a closed term is a Datalog program and its goal, built as
 * data (no text, so no quoting bug); the exact handler `Logic` runs it. The inverse reads a parsed program back into
 * a term — the gold programs of the corpus are points of the grammar, which is the law, not an assumption. The
 * normal form (variables renamed in order of appearance, facts and literals sorted) makes two programs comparable up
 * to the names and the order Datalog does not care about.
 */
object Compile:
  final case class Compiled(program: Program, goal: Atom):
    /** the program as the page shows it, and as `Logic.run` takes it */
    def query: Query.Logical = Query.Logical(program.toString, goal.toString)

  def program(term: Term): Either[String, Compiled] = term match
    case Con(Ty.LogicT, "Program", Vector(goal, facts, rules)) =>
      for
        g <- goalOf(goal)
        fs <- factsOf(facts)
        rs <- rulesOf(rules)
      yield Compiled(Program(fs ++ rs), g)
    case other => Left(s"not a logic program: ${other.show}")

  /** a closed term's program run on the tables; the facts are the program's own, the tables added beside them */
  def run(tables: Map[String, Table], term: Term): Either[String, (Query.Logical, Table)] =
    for
      c <- program(term)
      r <- Logic.run(tables, c.program, c.goal)
    yield (c.query, r)

  private def goalOf(t: Term): Either[String, Atom] = t match
    case Con(Ty.GoalT, pred, args) => argsOf(args).map(Atom(pred, _))
    case other => Left(s"not a goal: ${other.show}")

  private def atomOf(t: Term): Either[String, Atom] = t match
    case Con(Ty.AtomT(_, _, _), pred, args) => argsOf(args).map(Atom(pred, _))
    case other => Left(s"not an atom: ${other.show}")

  private def argsOf(ts: Vector[Term]): Either[String, Vector[okay.datalog.Term]] =
    ts.foldLeft[Either[String, Vector[okay.datalog.Term]]](Right(Vector.empty)) { (acc, t) =>
      acc.flatMap(xs => t match
        case Leaf(_, v) =>
          Right(xs :+ (if LogicGrammar.isVar(v) then okay.datalog.Term.Var(v) else okay.datalog.Term.Const(v)))
        case other => Left(s"an argument is not a leaf: ${other.show}"))
    }

  private def factsOf(t: Term): Either[String, Vector[Rule]] = t match
    case Leaf(Ty.FactsT, "nil") => Right(Vector.empty)
    case Con(Ty.FactsT, "cons", Vector(atom, rest)) =>
      for
        a <- atomOf(atom)
        _ <- if a.vars.isEmpty then Right(()) else Left(s"a fact with a variable: $a")
        rs <- factsOf(rest)
      yield Rule(a, Vector.empty) +: rs
    case other => Left(s"not a fact list: ${other.show}")

  private def rulesOf(t: Term): Either[String, Vector[Rule]] = t match
    case Leaf(Ty.RulesT(_), "nil") => Right(Vector.empty)
    case Con(Ty.RulesT(_), "cons", Vector(rule, rest)) =>
      for r <- ruleOf(rule); rs <- rulesOf(rest) yield r +: rs
    case other => Left(s"not a rule list: ${other.show}")

  private def ruleOf(t: Term): Either[String, Rule] = t match
    case Con(Ty.RuleT(_), "rule", Vector(head, body)) =>
      for h <- atomOf(head); b <- bodyOf(body) yield Rule(h, b)
    case other => Left(s"not a rule: ${other.show}")

  private def bodyOf(t: Term): Either[String, Vector[Literal]] = t match
    case Leaf(Ty.BodyT(_, _, _, _), "nil") => Right(Vector.empty)
    case Con(Ty.BodyT(_, _, _, _), "cons", Vector(lit, rest)) =>
      for l <- literalOf(lit); r <- bodyOf(rest) yield l +: r
    case other => Left(s"not a body: ${other.show}")

  private def literalOf(t: Term): Either[String, Literal] = t match
    case Con(Ty.LitT(_, _, _, _), "pos", Vector(atom)) => atomOf(atom).map(Literal.Pos.apply)
    case Con(Ty.LitT(_, _, _, _), "neg", Vector(atom)) => atomOf(atom).map(Literal.Neg.apply)
    case Con(Ty.LitT(_, _, _, _), "cmp", Vector(op, left, right)) =>
      for
        o <- opOf(op)
        l <- operand(left)
        r <- operand(right)
      yield Literal.Cmp(o, l, r)
    case other => Left(s"not a literal: ${other.show}")

  private def opOf(t: Term): Either[String, String] = t match
    case Leaf(Ty.CmpOpT, op) if LogicGrammar.ops.contains(op) => Right(op)
    case other => Left(s"not a comparison: ${other.show}")

  private def operand(t: Term): Either[String, okay.datalog.Term] = t match
    case Leaf(Ty.TermT(_), v) =>
      Right(if LogicGrammar.isVar(v) then okay.datalog.Term.Var(v) else okay.datalog.Term.Const(v))
    case other => Left(s"not a comparison's operand: ${other.show}")

  /** THE INVERSE: a parsed program and its goal as a term — the gold corpus' way into the grammar. Variables are
   * rule-local in Datalog, so each rule (and the goal) is renamed into the pool on its own */
  def ofProgram(program: Program, goal: Atom, ontology: Ontology): Either[String, Term] =
    val goalVars = goal.vars.distinct
    if goalVars.size > LogicGrammar.pool.size then Left(s"too many variables in the goal: ${goalVars.mkString(", ")}")
    else
      val goalRename = goalVars.zip(LogicGrammar.pool).toMap
      val (facts, rules) = program.rules.partition(_.body.isEmpty)
      val factLevels = facts.map(_.head.pred).distinct.map(_ -> 0).toMap
      for
        g <- goalTerm(goal, goalRename, ontology)
        fs <- facts.foldLeft[Either[String, Vector[Term]]](Right(Vector.empty))((acc, r) =>
          acc.flatMap(xs => factTerm(r.head, ontology).map(xs :+ _)))
        rs <- rulesTerm(rules, factLevels, ontology)
      yield Con(Ty.LogicT, "Program", Vector(g, list(Ty.FactsT, fs), rs))

  private def goalTerm(a: Atom, rename: Map[String, String], ontology: Ontology): Either[String, Term] =
    for
      _ <- known(a, ontology)
      args <- a.args.zipWithIndex.foldLeft[Either[String, Vector[Term]]](Right(Vector.empty)) { (acc, pair) =>
        val (arg, i) = pair
        acc.flatMap(xs => argTerm(arg, Ty.ArgT(a.pred, i, Vector.empty, fresh = true), rename, ontology).map(xs :+ _))
      }
    yield Con(Ty.GoalT, a.pred, args)

  private def factTerm(a: Atom, ontology: Ontology): Either[String, Term] =
    if a.vars.nonEmpty then Left(s"a fact with a variable: $a")
    else atomTerm(a, Vector.empty, fresh = false, except = Vector.empty, Map.empty, ontology)

  private def atomTerm(a: Atom, bound: Vector[String], fresh: Boolean, except: Vector[String],
      rename: Map[String, String], ontology: Ontology): Either[String, Term] =
    for
      _ <- known(a, ontology)
      args <- a.args.zipWithIndex.foldLeft[Either[String, Vector[Term]]](Right(Vector.empty)) { (acc, pair) =>
        val (arg, i) = pair
        acc.flatMap(xs => argTerm(arg, Ty.ArgT(a.pred, i, bound, fresh), rename, ontology).map(xs :+ _))
      }
    yield Con(Ty.AtomT(bound, fresh, except), a.pred, args)

  private def argTerm(t: okay.datalog.Term, ty: Ty, rename: Map[String, String],
      ontology: Ontology): Either[String, Term] = t match
    case okay.datalog.Term.Var(n) => rename.get(n).toRight(s"an uncollected variable: $n").map(Leaf(ty, _))
    case okay.datalog.Term.Const(c) =>
      if LogicGrammar.isVar(c) then Left(s"a constant named like a variable: $c")
      else
        val allowed = ty match
          case Ty.ArgT(pred, index, _, _) => ontology.constantsOf(pred, index)
          case Ty.TermT(_) => ontology.allConstants
          case _ => Vector.empty
        if allowed.contains(c) then Right(Leaf(ty, c)) else Left(s"unknown constant: $c")

  private def ruleTerm(r: Rule, levels: Map[String, Int], ontology: Ontology): Either[String, Term] =
    val vars = (r.head.vars ++ r.body.flatMap(_.vars)).distinct
    if vars.size > LogicGrammar.pool.size then Left(s"too many variables in a rule: ${vars.mkString(", ")}")
    else if ontology.inputs.contains(r.head.pred) then Left(s"${r.head.pred} is an input, not a derived predicate")
    else
      val rename = vars.zip(LogicGrammar.pool).toMap
      for
        head <- atomTerm(r.head, Vector.empty, fresh = true, except = ontology.inputPredicates, rename, ontology)
        body <- bodyTerm(r.body, r.head.pred, LogicGrammar.varsOf(head), Vector.empty, levels, rename, ontology)
      yield Con(Ty.RuleT(levels), "rule", Vector(head, body))

  private def bodyTerm(lits: Vector[Literal], head: String, vars: Vector[String], bound: Vector[String],
      levels: Map[String, Int], rename: Map[String, String], ontology: Ontology): Either[String, Term] =
    lits match
      case Vector() =>
        if vars.forall(bound.contains) then Right(Leaf(Ty.BodyT(head, vars, bound, levels), "nil"))
        else Left(s"unsafe rule: ${vars.filterNot(bound.contains).mkString(", ")} not bound by a positive atom")
      case l +: rest =>
        for
          lit <- literalTerm(l, head, vars, bound, levels, rename, ontology)
          next <- bodyTerm(rest, head, vars, bound ++ LogicGrammar.varsOf(lit), levels, rename, ontology)
        yield Con(Ty.BodyT(head, vars, bound, levels), "cons", Vector(lit, next))
      case _ => Left(s"not a body: ${lits.mkString(", ")}")

  private def literalTerm(l: Literal, head: String, vars: Vector[String], bound: Vector[String],
      levels: Map[String, Int], rename: Map[String, String], ontology: Ontology): Either[String, Term] =
    val inputs = ontology.inputPredicates
    l match
      case Literal.Pos(a) =>
        val allowed = LogicGrammar.positiveAllowed(inputs, levels, head)
        if !allowed.contains(a.pred) then Left(s"${a.pred} is not available positively here")
        else
          val except = allPredicates(ontology).filterNot(allowed.contains)
          atomTerm(a, bound, fresh = true, except, rename, ontology)
            .map(t => Con(Ty.LitT(head, vars, bound, levels), "pos", Vector(t)))
      case Literal.Neg(a) =>
        val allowed = LogicGrammar.negativeAllowed(inputs, levels, head)
        val missing = a.vars.map(rename).distinct.filterNot(bound.contains)
        if !allowed.contains(a.pred) then Left(s"${a.pred} may not be negated here")
        else if missing.nonEmpty then Left(s"unsafe negation: ${missing.mkString(", ")} not bound")
        else
          val except = allPredicates(ontology).filterNot(allowed.contains)
          atomTerm(a, bound, fresh = false, except, rename, ontology)
            .map(t => Con(Ty.LitT(head, vars, bound, levels), "neg", Vector(t)))
      case Literal.Cmp(op, x, y) =>
        val missing = l.vars.map(rename).distinct.filterNot(bound.contains)
        if !LogicGrammar.ops.contains(op) then Left(s"not a comparison operator: $op")
        else if missing.nonEmpty then Left(s"unsafe comparison: ${missing.mkString(", ")} not bound")
        else
          for
            a <- operandTerm(x, bound, rename, ontology)
            b <- operandTerm(y, bound, rename, ontology)
          yield Con(Ty.LitT(head, vars, bound, levels), "cmp", Vector(Leaf(Ty.CmpOpT, op), a, b))

  private def operandTerm(t: okay.datalog.Term, bound: Vector[String], rename: Map[String, String],
      ontology: Ontology): Either[String, Term] =
    argTerm(t, Ty.TermT(bound), rename, ontology)

  private def rulesTerm(rules: Vector[Rule], levels: Map[String, Int],
      ontology: Ontology): Either[String, Term] =
    rules match
      case Vector() => Right(Leaf(Ty.RulesT(levels), "nil"))
      case r +: rest =>
        for
          rt <- ruleTerm(r, levels, ontology)
          next <- rulesTerm(rest, LogicGrammar.levelsAfter(rt, levels), ontology)
        yield Con(Ty.RulesT(levels), "cons", Vector(rt, next))
      case _ => Left(s"not a rule list: ${rules.mkString(", ")}")

  private def known(a: Atom, ontology: Ontology): Either[String, Unit] =
    ontology.predicate(a.pred) match
      case None => Left(s"unknown predicate: ${a.pred}")
      case Some(p) if p.arity != a.args.size =>
        Left(s"${a.pred} expects ${p.arity} arguments, got ${a.args.size}")
      case Some(_) => Right(())

  private def allPredicates(ontology: Ontology): Vector[String] = ontology.predicates.keys.toVector.sorted

  private def list(ty: Ty, items: Vector[Term]): Term =
    items.foldRight(Leaf(ty, "nil"): Term)((item, rest) => Con(ty, "cons", Vector(item, rest)))

  /** THE NORMAL FORM: variables renamed in order of appearance (per rule), literals, rules and facts sorted */
  def normal(p: Program): Program = Program(p.rules.map(normalRule).sortBy(_.toString))

  private def normalRule(r: Rule): Rule =
    val vars = (r.head.vars ++ r.body.flatMap(_.vars)).distinct
    val rename = vars.zipWithIndex.map { case (v, i) => v -> s"V${i + 1}" }.toMap
    def t(x: okay.datalog.Term): okay.datalog.Term = x match
      case okay.datalog.Term.Var(n) => okay.datalog.Term.Var(rename(n))
      case c => c
    def a(x: Atom): Atom = Atom(x.pred, x.args.map(t))
    def l(x: Literal): Literal = x match
      case Literal.Pos(x) => Literal.Pos(a(x))
      case Literal.Neg(x) => Literal.Neg(a(x))
      case Literal.Cmp(op, x, y) => Literal.Cmp(op, t(x), t(y))
    Rule(a(r.head), r.body.map(l).sortBy(_.toString))

  def equivalent(a: Program, b: Program): Boolean = normal(a) == normal(b)
