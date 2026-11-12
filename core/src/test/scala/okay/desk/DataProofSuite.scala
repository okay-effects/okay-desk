package okay.desk

import okay.desk.exec.{Computation, Wire}
import okay.desk.logic.DataProof

class DataProofSuite extends munit.FunSuite:
  val questions = Sample.questions.flatMap(q => Query.parse(q.gold).toOption.collect { case l: Query.Logical => l })

  test("existing logical data queries cross the proof gate and preserve their hand-checked results"):
    assert(questions.size >= 3)
    for query <- questions do
      val result = Computation.run(Computation.Job.QueryData(Sample.tables, query)).toOption.get
      val proved = result.asInstanceOf[Computation.Output.ProvenRows]
      val expected = Logic.run(Sample.tables, query).toOption.get
      assertEquals(proved.table.rows.toSet, expected.rows.toSet)
      assert(proved.proof.hole.isEmpty && proved.proof.answers.forall(_.proof.nonEmpty))
      assertEquals(Wire.decode[Computation.Output](Wire.encode[Computation.Output](proved)), Right(proved))
    val closure = Computation.run(Computation.Job.QueryData(Sample.tables, questions.head)).toOption.get
      .asInstanceOf[Computation.Output.ProvenRows]
    assert(closure.proof.answers.forall(_.inlayProof.nonEmpty))

  test("a different computed result is refused rather than decorated with an unrelated certificate"):
    val query = questions.head
    val rows = Logic.run(Sample.tables, query).toOption.get
    val wrong = rows.copy(rows = Vector(Vector(Value.Str("invented-answer"))))
    assert(DataProof.check(Sample.tables, query, wrong).isLeft)

  test("an empty answer is an empty result, not a proof of an unstated negative claim"):
    val query = Query.Logical("reach(X, Y) :- edges(X, Y).", "reach(absent, X)")
    val result = Computation.run(Computation.Job.QueryData(Sample.tables, query)).toOption.get
      .asInstanceOf[Computation.Output.ProvenRows]
    assert(result.table.rows.isEmpty && result.proof.answers.isEmpty)

  test("large data results keep the exact handler without claiming an expanded certificate"):
    val table = Table("items", Vector("id"), Vector.tabulate(65)(i => Vector(Value.of(i.toString))))
    val query = Query.Logical("q(X) :- items(X).", "q(X)")
    val result = Computation.run(Computation.Job.QueryData(Map("items" -> table), query)).toOption.get
    assert(result.isInstanceOf[Computation.Output.Rows])
    assertEquals(result.asInstanceOf[Computation.Output.Rows].table.rows.toSet, table.rows.toSet)

  test("unrelated large open tables do not consume the proof budget of a small query"):
    val large = Table("unrelated", Vector("id"), Vector.tabulate(1000)(i => Vector(Value.of(i.toString))))
    val tables = Sample.tables + (large.name -> large)
    val query = questions.head
    val result = Computation.run(Computation.Job.QueryData(tables, query)).toOption.get
    assert(result.isInstanceOf[Computation.Output.ProvenRows])
