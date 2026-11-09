package okay.desk

import okay.desk.calc.{Term, Ty}
import okay.desk.logic.{Compile, LogicGrammar, Ontology, Predicate}
import okay.datalog.{Atom, Check, Eval, Fact, Parse, Program}

/**
 * M2 OF THE LOGIC FRAGMENT (specs/logic.md §8): a hand ontology over twenty tasks — five over data, five planning,
 * five family puzzles, five comparisons — each a gold Datalog program and a hand-computed answer. The law is that
 * every gold program is a point of the grammar, the exact engine returns exactly the hand's answer, and every answer
 * has a derivation. This checks the language before any neural head exists; it does not claim a reading of prose.
 */
class LogicTasksSuite extends munit.FunSuite:
  final case class Task(family: String, name: String, ontology: Ontology, tables: Map[String, Table],
      rules: String, goal: String, expected: Set[Vector[String]])

  val family: Ontology = Ontology(
    predicates = Map(
      "parent" -> Predicate("parent", Vector("person", "person")),
      "male" -> Predicate("male", Vector("person")),
      "grandparent" -> Predicate("grandparent", Vector("person", "person")),
      "sibling" -> Predicate("sibling", Vector("person", "person")),
      "uncle" -> Predicate("uncle", Vector("person", "person")),
      "ancestor" -> Predicate("ancestor", Vector("person", "person")),
      "person" -> Predicate("person", Vector("person")),
      "noChild" -> Predicate("noChild", Vector("person"))),
    constants = Map("person" -> Vector("alice", "bob", "carol", "dave", "eve")),
    inputs = Set("parent", "male"))

  val numbers: Ontology = Ontology(
    predicates = Map(
      "age" -> Predicate("age", Vector("person", "number")),
      "adult" -> Predicate("adult", Vector("person")),
      "minor" -> Predicate("minor", Vector("person")),
      "between" -> Predicate("between", Vector("person")),
      "older" -> Predicate("older", Vector("person", "person")),
      "sameAge" -> Predicate("sameAge", Vector("person", "person"))),
    constants = Map(
      "person" -> Vector("alice", "bob", "carol", "dave", "eve"),
      "number" -> Vector("17", "18", "21", "30", "45")),
    inputs = Set("age"))

  val plan: Ontology = Ontology(
    predicates = Map(
      "initial" -> Predicate("initial", Vector("state")),
      "edge" -> Predicate("edge", Vector("state", "state")),
      "reachable" -> Predicate("reachable", Vector("state"))),
    constants = Map("state" -> Vector("s0", "s1", "s2", "s3")),
    inputs = Set("initial", "edge"))

  val data: Ontology =
    val base = Ontology.ofTables(Sample.tables)
    base.copy(predicates = base.predicates ++ Map(
      "reach" -> Predicate("reach", Vector("edges.from", "edges.to")),
      "unpaidUa" -> Predicate("unpaidUa", Vector("orders.id")),
      "has" -> Predicate("has", Vector("customers.name")),
      "noOrders" -> Predicate("noOrders", Vector("customers.name"))))

  val reach = "reach(X, Y) :- edges(X, Y). reach(X, Z) :- edges(X, Y), reach(Y, Z)."
  val closure = "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y)."

  val tasks: Vector[Task] = Vector(
    Task("data", "what web depends on", data, Sample.tables, reach, "reach(web, X)",
      Set(Vector("checkout"), Vector("payments"), Vector("orders-db"), Vector("stripe"))),
    Task("data", "what reaches orders-db", data, Sample.tables, reach, "reach(X, \"orders-db\")",
      Set(Vector("web"), Vector("checkout"), Vector("search"), Vector("catalog"))),
    Task("data", "direct dependents of orders-db", data, Sample.tables, "", "edges(X, \"orders-db\")",
      Set(Vector("checkout"), Vector("catalog"))),
    Task("data", "unpaid orders of UA customers", data, Sample.tables,
      "unpaidUa(I) :- orders(I, C, A, unpaid, D, City), customers(C, \"UA\", Since).", "unpaidUa(I)",
      Set(Vector("4"), Vector("6"), Vector("7"))),
    Task("data", "customers with no orders", data, Sample.tables,
      """has(C) :- orders(I, C, A, S, D, City).
        |noOrders(C) :- customers(C, Country, Since), not has(C).""".stripMargin, "noOrders(C)",
      Set(Vector("fay"))),
    Task("planning", "a chain", plan, Map.empty,
      s"""initial(s0). edge(s0, s1). edge(s1, s2). edge(s2, s3). $closure""", "reachable(X)",
      Set(Vector("s0"), Vector("s1"), Vector("s2"), Vector("s3"))),
    Task("planning", "a diamond", plan, Map.empty,
      s"""initial(s0). edge(s0, s1). edge(s0, s2). edge(s1, s3). edge(s2, s3). $closure""", "reachable(X)",
      Set(Vector("s0"), Vector("s1"), Vector("s2"), Vector("s3"))),
    Task("planning", "a cycle", plan, Map.empty,
      s"""initial(s0). edge(s0, s1). edge(s1, s2). edge(s2, s0). $closure""", "reachable(X)",
      Set(Vector("s0"), Vector("s1"), Vector("s2"))),
    Task("planning", "a disconnected graph", plan, Map.empty,
      s"""initial(s0). edge(s0, s1). edge(s2, s3). $closure""", "reachable(X)",
      Set(Vector("s0"), Vector("s1"))),
    Task("planning", "a chain with a back edge", plan, Map.empty,
      s"""initial(s0). edge(s0, s1). edge(s1, s2). edge(s2, s3). edge(s3, s1). $closure""", "reachable(X)",
      Set(Vector("s0"), Vector("s1"), Vector("s2"), Vector("s3"))),
    Task("puzzle", "grandparent", family, Map.empty,
      """parent(alice, bob). parent(bob, carol). parent(bob, dave).
        |grandparent(X, Z) :- parent(X, Y), parent(Y, Z).""".stripMargin, "grandparent(alice, X)",
      Set(Vector("carol"), Vector("dave"))),
    Task("puzzle", "sibling", family, Map.empty,
      """parent(alice, bob). parent(alice, carol). parent(bob, carol).
        |sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.""".stripMargin, "sibling(bob, X)",
      Set(Vector("carol"))),
    Task("puzzle", "uncle", family, Map.empty,
      """parent(alice, bob). parent(alice, carol). parent(bob, carol). parent(carol, dave). male(bob).
        |sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.
        |uncle(X, Y) :- sibling(X, P), parent(P, Y), male(X).""".stripMargin, "uncle(X, dave)",
      Set(Vector("bob"))),
    Task("puzzle", "ancestor", family, Map.empty,
      """parent(alice, bob). parent(bob, carol). parent(carol, dave).
        |ancestor(X, Y) :- parent(X, Y).
        |ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).""".stripMargin, "ancestor(alice, X)",
      Set(Vector("bob"), Vector("carol"), Vector("dave"))),
    Task("puzzle", "no child", family, Map.empty,
      """parent(alice, bob). parent(bob, carol).
        |person(X) :- parent(X, Y).
        |person(X) :- parent(Y, X).
        |noChild(X) :- person(X), not parent(X, carol).""".stripMargin, "noChild(X)",
      Set(Vector("alice"), Vector("carol"))),
    Task("arithmetic", "adult", numbers, Map.empty,
      """age(alice, 30). age(bob, 17). age(carol, 21). age(dave, 17). age(eve, 45).
        |adult(X) :- age(X, N), N >= 18.""".stripMargin, "adult(X)",
      Set(Vector("alice"), Vector("carol"), Vector("eve"))),
    Task("arithmetic", "minor", numbers, Map.empty,
      """age(alice, 30). age(bob, 17). age(carol, 21). age(dave, 17). age(eve, 45).
        |minor(X) :- age(X, N), N < 18.""".stripMargin, "minor(X)",
      Set(Vector("bob"), Vector("dave"))),
    Task("arithmetic", "between", numbers, Map.empty,
      """age(alice, 30). age(bob, 17). age(carol, 21). age(dave, 17). age(eve, 45).
        |between(X) :- age(X, N), N >= 18, N <= 30.""".stripMargin, "between(X)",
      Set(Vector("alice"), Vector("carol"))),
    Task("arithmetic", "older", numbers, Map.empty,
      """age(alice, 30). age(bob, 17). age(carol, 21). age(dave, 17). age(eve, 45).
        |older(X, Y) :- age(X, N), age(Y, M), N > M.""".stripMargin, "older(X, bob)",
      Set(Vector("alice"), Vector("carol"), Vector("eve"))),
    Task("arithmetic", "same age", numbers, Map.empty,
      """age(alice, 30). age(bob, 17). age(carol, 21). age(dave, 17). age(eve, 45).
        |sameAge(X, Y) :- age(X, N), age(Y, N), X != Y.""".stripMargin, "sameAge(X, dave)",
      Set(Vector("bob"))))

  def program(text: String): Program = Parse.program(text).fold(fail(_), identity)
  def atom(text: String): Atom = Parse.atom(text).fold(fail(_), identity)
  def term(t: Task): Term = Compile.ofProgram(program(t.rules), atom(t.goal), t.ontology).fold(fail(_), identity)

  def leaves(t: Term): Vector[String] = t match
    case Term.Leaf(Ty.ArgT(_, _, _, _), v) => Vector(v)
    case Term.Leaf(Ty.TermT(_), v) => Vector(v)
    case Term.Con(_, _, args) => args.flatMap(leaves)
    case _ => Vector.empty

  test("twenty tasks, four families of five, every gold program a point of the grammar"):
    assertEquals(tasks.size, 20)
    assertEquals(tasks.groupBy(_.family).view.mapValues(_.size).toMap,
      Map("data" -> 5, "planning" -> 5, "puzzle" -> 5, "arithmetic" -> 5))
    for t <- tasks do
      val tm = term(t)
      assert(LogicGrammar(t.ontology).accepts(tm), s"${t.name}: not a point of the grammar: ${tm.show}")
      val c = Compile.program(tm).fold(fail(_), identity)
      assert(Compile.equivalent(c.program, program(t.rules)), s"${t.name}: the program changed")
      assert(Check.strata(c.program).isRight, s"${t.name}: ${Check.strata(c.program)}")

  test("no invented constants: every leaf is a variable or a known constant"):
    for t <- tasks do
      for v <- leaves(term(t)) do
        assert(LogicGrammar.isVar(v) || t.ontology.allConstants.contains(v), s"${t.name}: unknown leaf $v")

  test("the answers: each task exactly as computed by hand"):
    for t <- tasks do
      val (_, result) = Compile.run(t.tables, term(t)).fold(fail(_), identity)
      assertEquals(Sample.rows(result), t.expected, t.name)

  test("every expected answer has a proof"):
    for t <- tasks do
      val c = Compile.program(term(t)).fold(fail(_), identity)
      val result = Eval.run(c.program, Logic.facts(t.tables), proofs = true).fold(fail(_), identity)
      val vars = c.goal.vars.distinct.sorted
      for row <- t.expected do
        val env = vars.zip(row).toMap
        val args = c.goal.args.map {
          case okay.datalog.Term.Const(v) => v
          case okay.datalog.Term.Var(n) => env(n)
        }
        val fact = Fact(c.goal.pred, args)
        assert(result.proof(fact).exists(_.nonEmpty), s"${t.name}: no proof of $fact")
