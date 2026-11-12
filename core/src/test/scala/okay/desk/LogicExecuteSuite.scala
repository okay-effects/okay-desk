package okay.desk

import okay.datalog.{Derivation, Eval, Fact, Parse, Program, Step}
import okay.desk.logic.{Compile, Corpus, Execute, LogicGrammar}
import okay.desk.skill.Extract

/** Execution/proof laws with gold readings; these are not measurements of neural comprehension. */
class LogicExecuteSuite extends munit.FunSuite:
  def program(text: String): Program = Parse.program(text).fold(fail(_), identity)

  test("a typed reading executes on all four corpus families and every answer carries a replayable proof"):
    for item <- Corpus.items(Corpus.Split.Test, 4, seed = 19) do
      val p = program(item.rules)
      val goal = Parse.atom(item.goal).fold(fail(_), identity)
      val term = Compile.ofProgram(p, goal, item.ontology).fold(fail(_), identity)
      val grammar = LogicGrammar(item.ontology)
      val reading = Extract.gold(grammar, item.text, term).fold(fail(_), identity)
      Execute.run(grammar, reading, item.tables).fold(fail(_), identity) match
        case Execute.Outcome.Open(_) => fail("a gold definition must be closed")
        case Execute.Outcome.Complete(definition, answers) =>
          val expected = Eval.naive(p, Logic.facts(item.tables)).toOption.get.query(goal)
          val expectedFacts = expected.map { bindings =>
            Fact(goal.pred, goal.args.map {
              case okay.datalog.Term.Const(value) => value
              case okay.datalog.Term.Var(name) => bindings(name)
            })
          }.toSet
          assertEquals(answers.map(_.fact).toSet, expectedFacts)
          answers.foreach { answer =>
            assertEquals(Execute.verify(definition.program, Logic.facts(item.tables), answer.fact, answer.proof),
              Right(()))
            answer.inlay.foreach(result => assert(result.known(answer.fact.toString)))
          }

  test("an unfinished reading stays an open typed hole and never executes a guessed definition"):
    val grammar = LogicGrammar(Corpus.plan)
    val reading = Extract.Reading("What is reachable?", Vector(Extract.Choice("Program")))
    val result = Execute.run(grammar, reading).fold(fail(_), identity)
    assert(result.isInstanceOf[Execute.Outcome.Open])
    assert(Execute.run(grammar, reading.copy(choices = Vector(Extract.Choice("madeUp")))).isLeft)

  test("positive reachability is also proved by compiled inlay, without asking the model for the answer"):
    val grammar = LogicGrammar(Corpus.plan)
    val p = program("initial(s0). edge(s0, s1). edge(s1, s2). " +
      "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y).")
    val goal = Parse.atom("reachable(X)").toOption.get
    val term = Compile.ofProgram(p, goal, Corpus.plan).toOption.get
    val reading = Extract.gold(grammar, "From s0 via s1 to s2, which states are reachable?", term).toOption.get
    Execute.run(grammar, reading).toOption.get match
      case Execute.Outcome.Complete(_, answers) =>
        assertEquals(answers.map(_.fact.args).toSet, Set(Vector("s0"), Vector("s1"), Vector("s2")))
        assert(answers.forall(a => a.inlay.exists(_.known(a.fact.toString))))
      case _ => fail("the supplied definition is complete")

  test("proof replay rejects invented givens, substituted rules, omitted premises and wrong order"):
    val p = program("edge(a, b). edge(b, c). reach(X, Y) :- edge(X, Y). " +
      "reach(X, Z) :- reach(X, Y), edge(Y, Z).")
    val goal = Fact("reach", Vector("a", "c"))
    val proof = Eval.run(p, proofs = true).toOption.get.proof(goal).get
    assertEquals(Execute.verify(p, Vector.empty, goal, proof), Right(()))
    assert(Execute.verify(p, Vector.empty, goal, Vector(Step(goal, None))).isLeft)
    assert(Execute.verify(p, Vector.empty, goal, proof.reverse).isLeft)
    assert(Execute.verify(p, Vector.empty, goal, proof.tail).isLeft)
    val forgedRule = program("reach(a, c).").rules.head
    val forged = proof.updated(proof.size - 1, Step(goal, Some(Derivation(forgedRule, Vector.empty, Vector.empty))))
    assert(Execute.verify(p, Vector.empty, goal, forged).isLeft)

  test("copied data values must cite a real cell before a definition can execute"):
    val grammar = LogicGrammar(Corpus.plan)
    val p = program("initial(s0).")
    val goal = Parse.atom("initial(s0)").toOption.get
    val term = Compile.ofProgram(p, goal, Corpus.plan).toOption.get
    val original = Extract.gold(grammar, "Start at s0.", term).toOption.get
    val index = original.choices.indexWhere(_.con == "s0")
    val sourced = original.copy(choices = original.choices.updated(index,
      Extract.Choice("s0", Some(Extract.Evidence.Data("states.id")))))
    assert(Execute.run(grammar, sourced).left.toOption.get.contains("source cell"))
    val table = Table("states", Vector("id"), Vector(Vector(Value.of("s0"))))
    assert(Execute.run(grammar, sourced, Map("states" -> table)).isRight)

  test("negative premises use the full closure, including facts omitted from the answer's proof"):
    val p = program("person(ann). person(bob). parent(ann, bob). " +
      "hasChild(X) :- parent(X, Y). childless(X) :- person(X), not hasChild(X).")
    val bob = Fact("childless", Vector("bob"))
    val proof = Eval.run(p, proofs = true).toOption.get.proof(bob).get
    assertEquals(Execute.verify(p, Vector.empty, bob, proof), Right(()))
    val rule = p.rules.last
    val ann = Fact("childless", Vector("ann"))
    val fake = Vector(Step(Fact("person", Vector("ann")), None),
      Step(ann, Some(Derivation(rule, Vector(Fact("person", Vector("ann"))),
        Vector(Fact("hasChild", Vector("ann")))))))
    assert(Execute.verify(p, Vector.empty, ann, fake).isLeft)
