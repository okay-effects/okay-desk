package okay.desk

import okay.datalog.Parse
import okay.desk.exec.{Computation, Wire}
import okay.desk.logic.{Compile, Corpus, LogicGrammar, Protocol}
import okay.desk.skill.Extract

class LogicProtocolSuite extends munit.FunSuite:
  val question = "From s0 go to s1, then s2. Which states can be reached?"
  val ontology = Corpus.plan
  val rules = "initial(s0). edge(s0, s1). edge(s1, s2). " +
    "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y)."
  def proposal: Protocol.Proposal =
    val term = Compile.ofProgram(Parse.program(rules).toOption.get,
      Parse.atom("reachable(X)").toOption.get, ontology).toOption.get
    val reading = Extract.gold(LogicGrammar(ontology), question, term).fold(fail(_), identity)
    Protocol.proposal(ontology, reading)

  test("typed proposals and checked answers cross the actual Worker algebra without losing source spans"):
    val job = Computation.Job.LogicModel(question, proposal, Map.empty)
    val request = Computation.Request(7, job)
    assertEquals(Wire.decode[Computation.Request](Wire.encode(request)), Right(request))
    val output = Computation.run(job).toOption.get
    assertEquals(Wire.decode[Computation.Output](Wire.encode(output)), Right(output))
    val result = output.asInstanceOf[Computation.Output.LogicResult].value
    assertEquals(result.answers.map(_.fact).toSet, Set("reachable(s0)", "reachable(s1)", "reachable(s2)"))
    assert(result.hole.isEmpty && result.rules.nonEmpty && result.goal.nonEmpty)
    assert(result.answers.forall(a => a.proof.nonEmpty && a.inlayProof.nonEmpty))

  test("a partial proposal returns its typed options and no program, answer or proof"):
    val partial = proposal.copy(decisions = proposal.decisions.take(1))
    val result = Protocol.run(question, partial, Map.empty).toOption.get
    assertEquals(result.hole.get.kind, "GoalT")
    assert(result.hole.get.options.contains("reachable"))
    assert(result.rules.isEmpty && result.goal.isEmpty && result.answers.isEmpty)

  test("malformed vocabularies and out-of-question evidence cannot reach the solver"):
    val p = proposal
    val v = p.vocabulary
    val duplicate = p.copy(vocabulary = v.copy(relations = v.relations ++ v.relations))
    assert(Protocol.run(question, duplicate, Map.empty).isLeft)
    assert(Protocol.run(question, p.copy(vocabulary = v.copy(inputs = Vector("invented"))), Map.empty).isLeft)
    val i = p.decisions.indexWhere(_.source.nonEmpty)
    val bad = p.decisions.updated(i, p.decisions(i).copy(source = Some(Protocol.Source.Text(0, question.length + 1))))
    assert(Protocol.run(question, p.copy(decisions = bad), Map.empty).isLeft)
