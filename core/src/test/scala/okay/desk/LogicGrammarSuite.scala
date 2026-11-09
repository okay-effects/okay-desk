package okay.desk

import okay.desk.calc.{Encode, Term, Ty}
import okay.desk.logic.{Compile, LogicGrammar, Ontology, Predicate}
import okay.datalog.{Atom, Check, Eval, Parse, Program, Result}

/**
 * THE LAWS OF THE LOGIC FRAGMENT (specs/logic.md §5): every gold program of the corpus is a point of the grammar
 * (goal first, safe by construction); the word round-trips; the denotation runs on the exact handler and agrees with
 * the hand-computed answers; every term the grammar enumerates is well-formed and stratified, and the engine's two
 * evaluations agree on it; an unsafe rule or an unstratifiable negation is refused, and its end is never offered.
 * Variables are alpha-canonical: a fresh variable is the first unused name, a consistent rename is not a reading,
 * while reusing a bound variable stays a semantic choice.
 */
class LogicGrammarSuite extends munit.FunSuite:
  val ontology: Ontology = Ontology(
    predicates = Map(
      "parent" -> Predicate("parent", Vector("person", "person")),
      "ancestor" -> Predicate("ancestor", Vector("person", "person")),
      "person" -> Predicate("person", Vector("person")),
      "noChild" -> Predicate("noChild", Vector("person")),
      "age" -> Predicate("age", Vector("person", "number")),
      "adult" -> Predicate("adult", Vector("person")),
      "sibling" -> Predicate("sibling", Vector("person", "person")),
      "uncle" -> Predicate("uncle", Vector("person", "person")),
      "male" -> Predicate("male", Vector("person")),
      "knows" -> Predicate("knows", Vector("person", "person")),
      "stranger" -> Predicate("stranger", Vector("person"))),
    constants = Map(
      "person" -> Vector("alice", "bob", "carol", "dave", "eve"),
      "number" -> Vector("17", "18", "21", "30")),
    inputs = Set("parent", "age", "male", "knows"))
  val g = LogicGrammar(ontology)

  final case class Task(name: String, rules: String, goal: String, expected: Set[Vector[String]])

  val tasks: Vector[Task] = Vector(
    Task("facts", "parent(alice, bob). parent(bob, carol).", "parent(alice, X)", Set(Vector("bob"))),
    Task("transitive",
      """ancestor(X, Y) :- parent(X, Y).
        |ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).
        |parent(alice, bob). parent(bob, carol). parent(carol, dave).""".stripMargin,
      "ancestor(alice, X)", Set(Vector("bob"), Vector("carol"), Vector("dave"))),
    Task("negation",
      """person(X) :- parent(X, Y).
        |person(X) :- parent(Y, X).
        |noChild(X) :- person(X), not parent(X, carol).
        |parent(alice, bob). parent(bob, carol).""".stripMargin,
      "noChild(X)", Set(Vector("alice"), Vector("carol"))),
    Task("comparison",
      """adult(X) :- age(X, N), N >= 18.
        |age(alice, 30). age(bob, 17). age(carol, 21).""".stripMargin,
      "adult(X)", Set(Vector("alice"), Vector("carol"))),
    Task("join and difference",
      """sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.
        |uncle(X, Y) :- sibling(X, P), parent(P, Y), male(X).
        |parent(alice, bob). parent(alice, carol). parent(bob, carol). parent(carol, dave).
        |male(bob). male(dave).""".stripMargin,
      "uncle(X, dave)", Set(Vector("bob"))),
    Task("stratified negation",
      """stranger(X) :- person(X), not knows(X, bob).
        |knows(alice, bob). person(alice). person(bob). person(carol).""".stripMargin,
      "stranger(X)", Set(Vector("bob"), Vector("carol"))))

  def program(text: String): Program = Parse.program(text).fold(fail(_), identity)
  def atom(text: String): Atom = Parse.atom(text).fold(fail(_), identity)
  def term(t: Task): Term = Compile.ofProgram(program(t.rules), atom(t.goal), ontology).fold(fail(_), identity)

  test("every gold program is a point of the grammar: goal first, safe by construction"):
    for t <- tasks do
      val tm = term(t)
      assert(g.accepts(tm), s"${t.name}: not a point of the grammar: ${tm.show}")
      val back = Compile.program(tm).fold(fail(_), identity)
      assert(Compile.equivalent(back.program, program(t.rules)), s"${t.name}: the program changed")
      val letters = Encode.word(tm)
      val goal = back.goal.pred +: back.goal.args.map {
        case okay.datalog.Term.Var(n) => n
        case okay.datalog.Term.Const(c) => c
      }
      assertEquals(letters.take(goal.size + 1), "Program" +: goal, s"${t.name}: the goal is not first")

  test("the word round-trips: a term, its constructors, the same term"):
    for t <- tasks do
      val tm = term(t)
      assertEquals(Encode.tree(g, Encode.word(tm), Ty.LogicT), tm, t.name)

  test("the denotation: each goal exactly as computed by hand, on the program's own facts"):
    for t <- tasks do
      val (query, result) = Compile.run(Map.empty, term(t)).fold(fail(_), identity)
      assertEquals(result.rows.map(_.map(_.text)).toSet, t.expected, s"${t.name}: ${query.rules}")

  test("a variable in a fact, an unbound head and an unbound negation are refused"):
    val small = Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s")), "q" -> Predicate("q", Vector("s"))),
      constants = Map("s" -> Vector("a", "b")),
      inputs = Set("q"))
    val sg = LogicGrammar(small)
    assert(Compile.ofProgram(program("p(X)."), atom("p(a)"), small).isLeft)
    val unsafe = program("p(X) :- q(Y).")
    assert(Compile.ofProgram(unsafe, atom("p(X)"), small).isLeft)
    assert(Check.safety(unsafe).isLeft)
    assert(Compile.ofProgram(program("p(X) :- q(X), not q(Y)."), atom("p(X)"), small).isLeft)
    assert(!sg.constructors(Ty.BodyT("p", Vector("X"), Vector.empty, Map.empty)).contains("nil"))
    assert(!sg.constructors(Ty.ArgT("p", 0, Vector.empty, fresh = false)).exists(LogicGrammar.isVar))
    val noInputs = LogicGrammar(Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set.empty))
    assert(!noInputs.constructors(Ty.LitT("p", Vector.empty, Vector.empty, Map.empty)).contains("neg"))

  test("a negation that would close a cycle is refused, and the grammar never offers it"):
    val derived = Ontology(
      predicates = Map(
        "p" -> Predicate("p", Vector("s")),
        "q" -> Predicate("q", Vector("s")),
        "r" -> Predicate("r", Vector("s")),
        "s" -> Predicate("s", Vector("s"))),
      constants = Map("s" -> Vector("a", "b")),
      inputs = Set("r", "s"))
    val dg = LogicGrammar(derived)
    // p depends on q negatively before q is defined: no level can order them
    assert(Compile.ofProgram(program("p(X) :- s(X), not q(X). q(X) :- p(X)."), atom("p(X)"), derived).isLeft)
    // a later rule for p would need a higher level than p already has
    val redefined = program("p(X) :- r(X). q(X) :- p(X). p(X) :- q(X), not q(X).")
    assert(Compile.ofProgram(redefined, atom("p(X)"), derived).isLeft)
    // the stratifiable form is accepted: p negates the input r, q depends on p
    val ok = program("p(X) :- s(X), not r(X). q(X) :- p(X).")
    val okTerm = Compile.ofProgram(ok, atom("p(X)"), derived).fold(fail(_), identity)
    assert(dg.accepts(okTerm))
    assert(Check.strata(Compile.program(okTerm).fold(fail(_), identity).program).isRight)

  test("every term the grammar enumerates is well-formed and stratified"):
    val small = LogicGrammar(Ontology(
      predicates = Map(
        "p" -> Predicate("p", Vector("s")),
        "q" -> Predicate("q", Vector("s")),
        "r" -> Predicate("r", Vector("s"))),
      constants = Map("s" -> Vector("a", "b")),
      inputs = Set("r")))
    val terms = small.enumerate(28).take(150).toVector
    assert(terms.nonEmpty)
    for tm <- terms do
      val c = Compile.program(tm).fold(e => fail(s"${tm.show}: $e"), identity)
      assert(Check.strata(c.program).isRight, s"${tm.show}: ${Check.strata(c.program)}")

  test("the engine's two evaluations agree on every enumerated program"):
    def relations(r: Result): Map[String, Set[Vector[String]]] =
      r.predicates.map(p => p -> r.relation(p).toSet).toMap
    val small = LogicGrammar(Ontology(
      predicates = Map(
        "p" -> Predicate("p", Vector("s")),
        "q" -> Predicate("q", Vector("s")),
        "r" -> Predicate("r", Vector("s"))),
      constants = Map("s" -> Vector("a", "b")),
      inputs = Set("r")))
    for tm <- small.enumerate(26).take(60) do
      val c = Compile.program(tm).fold(e => fail(s"${tm.show}: $e"), identity)
      val fast = Eval.run(c.program).fold(e => fail(s"${tm.show}: $e"), identity)
      val slow = Eval.naive(c.program).fold(e => fail(s"${tm.show}: $e"), identity)
      assertEquals(relations(fast), relations(slow), tm.show)

  test("the grammar is deterministic"):
    val small = LogicGrammar(Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set.empty))
    assertEquals(small.enumerate(18).take(20).toVector, small.enumerate(18).take(20).toVector)
    assertEquals(small.constructors(Ty.ArgT("p", 0, Vector.empty, fresh = true)),
      small.constructors(Ty.ArgT("p", 0, Vector.empty, fresh = true)))

  test("alpha: a fresh variable is the first unused name; reusing a bound one is still offered"):
    val small = LogicGrammar(Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s", "s", "s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set.empty))
    def vars(bound: Vector[String]): Vector[String] =
      small.constructors(Ty.ArgT("p", bound.size, bound, fresh = true)).filter(LogicGrammar.isVar)
    assertEquals(vars(Vector.empty), Vector("X"))
    assertEquals(vars(Vector("X")), Vector("X", "Y"))
    assertEquals(vars(Vector("X", "Y")), Vector("X", "Y", "Z"))

  test("alpha: a consistent rename is refused by the grammar, but a repeated variable is a reading"):
    val small = LogicGrammar(Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s", "s")), "q" -> Predicate("q", Vector("s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set("q")))
    def point(rules: String, goal: String): Term =
      Compile.ofProgram(program(rules), atom(goal), small.ontology).fold(fail(_), identity)
    val canonical = point("p(X, Y) :- q(X), q(Y).", "p(X, Y)")
    assert(small.accepts(canonical))
    // the same definition with the fresh variables renamed: X is offered, Y is not
    val renamed = Encode.tree(small, Encode.word(canonical).map {
      case "X" => "Y"
      case "Y" => "X"
      case w   => w
    }, Ty.LogicT)
    assert(!small.accepts(renamed), s"a consistent rename is not a reading: ${renamed.show}")
    // reusing the first variable is a semantic choice the grammar offers: p(X, X) differs from p(X, Y)
    val repeated = point("p(X, X) :- q(X).", "p(X, X)")
    assert(small.accepts(repeated))
    val a = Compile.program(canonical).fold(fail(_), identity)
    val b = Compile.program(repeated).fold(fail(_), identity)
    assert(!Compile.same(a, b), "p(X, X) is not p(X, Y)")

  test("alpha: a consistent rename is the same formal definition, a changed premise is not"):
    val small = Ontology(
      predicates = Map("p" -> Predicate("p", Vector("s")), "q" -> Predicate("q", Vector("s")),
        "r" -> Predicate("r", Vector("s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set("q", "r"))
    def compiled(rules: String, goal: String): Compile.Compiled =
      Compile.program(Compile.ofProgram(program(rules), atom(goal), small).fold(fail(_), identity))
        .fold(fail(_), identity)
    val renamed = compiled("p(X) :- q(X).", "p(X)")
    val same = compiled("p(Y) :- q(Y).", "p(Y)")
    assert(Compile.same(renamed, same), "a consistent rename is the same definition")
    val other = compiled("p(X) :- r(X).", "p(X)")
    assert(!Compile.same(renamed, other), "a changed premise is not the same definition")

  test("alpha: a head variable is in scope from the first positive atom, the Codex closure"):
    val graph = Ontology(
      predicates = Map(
        "initial" -> Predicate("initial", Vector("s")),
        "edge" -> Predicate("edge", Vector("s", "s")),
        "reachable" -> Predicate("reachable", Vector("s"))),
      constants = Map("s" -> Vector("s0", "s1", "s2")),
      inputs = Set("initial", "edge"))
    val gg = LogicGrammar(graph)
    // the second rule's head variable `Y` is in the body's canonical scope before any positive atom binds it
    val p = program("initial(s0). edge(s0, s1). edge(s1, s2). " +
      "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y).")
    val tm = Compile.ofProgram(p, atom("reachable(X)"), graph).fold(fail(_), identity)
    assert(gg.accepts(tm), tm.show)
    assertEquals(Encode.tree(gg, Encode.word(tm), Ty.LogicT), tm)
    val (_, result) = Compile.run(Map.empty, tm).fold(fail(_), identity)
    assertEquals(result.rows.map(_.map(_.text)).toSet, Set(Vector("s0"), Vector("s1"), Vector("s2")))

  test("alpha: a recursive join and a repeated goal variable round-trip and denote"):
    val graph = Ontology(
      predicates = Map(
        "edge" -> Predicate("edge", Vector("s", "s")),
        "reach" -> Predicate("reach", Vector("s", "s"))),
      constants = Map("s" -> Vector("a", "b", "c")),
      inputs = Set("edge"))
    val gg = LogicGrammar(graph)
    val p = program("reach(X, Y) :- edge(X, Y). reach(X, Z) :- reach(X, Y), edge(Y, Z). " +
      "edge(a, b). edge(b, a). edge(b, c).")
    val tm = Compile.ofProgram(p, atom("reach(X, X)"), graph).fold(fail(_), identity)
    assert(gg.accepts(tm), tm.show)
    assertEquals(Encode.tree(gg, Encode.word(tm), Ty.LogicT), tm)
    val c = Compile.program(tm).fold(fail(_), identity)
    assertEquals(c.goal, atom("reach(X, X)"))
    val (_, result) = Compile.run(Map.empty, tm).fold(fail(_), identity)
    assertEquals(result.rows.map(_.map(_.text)).toSet, Set(Vector("a"), Vector("b")))

  test("alpha: the head's variables are in scope in the body, but safety still binds"):
    val small = Ontology(
      predicates = Map(
        "p" -> Predicate("p", Vector("s")),
        "q" -> Predicate("q", Vector("s")),
        "r" -> Predicate("r", Vector("s"))),
      constants = Map("s" -> Vector("a")),
      inputs = Set("q", "r"))
    val sg = LogicGrammar(small)
    // the head's `X` is in the canonical scope of the first positive atom (reusing it is the join)
    assert(sg.constructors(Ty.AtomT(Vector("X"), fresh = true, except = Vector.empty)).contains("q"))
    // a head variable that no positive atom binds is still unsafe
    assert(Compile.ofProgram(program("p(X) :- r(a)."), atom("p(X)"), small).isLeft)
    // a negation may not mention an unbound variable
    assert(Compile.ofProgram(program("p(X) :- q(X), not q(Y)."), atom("p(X)"), small).isLeft)
    // the safe rule that uses the head variable first is accepted
    assert(Compile.ofProgram(program("p(X) :- q(X)."), atom("p(X)"), small).isRight)

  /** a term with its variable leaves consistently renamed, for the law that the grammar refuses a rename */
  def renameVars(t: Term, rename: Map[String, String]): Term = t match
    case Term.Hole(ty) => Term.Hole(ty)
    case Term.Leaf(ty, v) => Term.Leaf(ty, rename.getOrElse(v, v))
    case Term.Con(ty, name, args) => Term.Con(ty, name, args.map(renameVars(_, rename)))
