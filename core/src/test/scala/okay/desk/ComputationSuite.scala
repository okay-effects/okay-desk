package okay.desk

import okay.desk.exec.{Computation, ComputePlan, Wire}
import okay.desk.know.{Arithmetic, Deduction, Knowledge}
import okay.desk.puzzle.{Puzzle, Solver}
import scala.concurrent.{ExecutionContext, Future, Promise}

class ComputationSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.parasitic
  val q = "Moby is a whale. Every whale is an animal. Is Moby an animal?"

  test("worker messages round-trip all tool types, exact proof, typed grid keys and numeric-looking text"):
    val typed =
      Table("data", Vector("id", "number", "text"), Vector(Vector(Value.Str("12"), Value.Num(12), Value.Empty)))
    val jobs = Vector(
      Computation.Job.Calculate("0.1 + 0.2", "0.1 + 0.2"),
      Computation.Job.Deduce(q, Vector.empty, Deduction.plan(q, Vector.empty).get),
      Computation.Job.Solve(Puzzle.classic, 2),
      Computation.Job.QueryData(
        Map("data" -> typed),
        Query.Relational("data", None, Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, None)
      )
    )
    for (job, i) <- jobs.zipWithIndex do
      val request = Computation.Request(i, job)
      assertEquals(Wire.decode[Computation.Request](Wire.encode(request)), Right(request))
      val reply = Computation.Reply(i, Computation.run(job), 1.25)
      assertEquals(Wire.decode[Computation.Reply](Wire.encode(reply)), Right(reply))
    val solved = Computation.run(jobs(2)).toOption.get.asInstanceOf[Computation.Output.Solved].value
    assert(solved.isInstanceOf[Solver.Outcome.One])

  test("invalid protocol shapes and invented arithmetic inputs cannot execute"):
    for text <- Vector("{}", "{\"id\":1,\"job\":{\"case\":\"eval\",\"value\":\"alert(1)\"}}", "{\"id\":1.5,\"job\":{}}")
    do assert(Wire.decode[Computation.Request](text).isLeft)
    assert(Computation.run(Computation.Job.Calculate("2 + 2", "2 + 10")).isLeft)
    assert(Arithmetic.run("2 2 + 3", "2 2 + 3").isLeft)

  test("independent calls start before another finishes; dependants wait and a diamond runs each node once"):
    val first = Promise[Either[String, Computation.Output]]()
    val started = scala.collection.mutable.ArrayBuffer.empty[String]
    val a = Computation.Job.Calculate("1+1", "1+1")
    val b = Computation.Job.Calculate("2+2", "2+2")
    val nodes = Vector(
      ComputePlan.Node("a", a),
      ComputePlan.Node("b", b),
      ComputePlan.Node("c", a, Vector("a", "b")),
      ComputePlan.Node("d", b, Vector("a", "c"))
    )
    var calls = 0
    val result = ComputePlan.run(nodes) { job =>
      calls += 1
      started += (if job == a then "a" else "b")
      if calls == 1 then first.future else Future.successful(Computation.run(job))
    }
    assertEquals(started.toVector, Vector("a", "b"))
    assert(!result.isCompleted)
    first.success(Computation.run(a))
    result.map { completed =>
      assert(completed.exists(_.forall(_.result.isRight)))
      assertEquals(calls, 4)
    }

  test("cycles are refused before submission; a failure skips only dependent work"):
    val bad = Computation.Job.Calculate("2 + 2", "2 + 10")
    val good = Computation.Job.Calculate("2 + 2", "2 + 2")
    val cycle = Vector(ComputePlan.Node("a", good, Vector("b")), ComputePlan.Node("b", good, Vector("a")))
    assert(ComputePlan.validate(cycle).isLeft)
    var calls = 0
    val nodes =
      Vector(ComputePlan.Node("a", bad), ComputePlan.Node("b", good), ComputePlan.Node("c", good, Vector("a")))
    ComputePlan
      .run(nodes) { job =>
        calls += 1; Future.successful(Computation.run(job))
      }
      .map { rs =>
        assertEquals(calls, 2)
        assert(rs.exists(_.find(_.id == "b").get.result.isRight))
        assert(rs.exists(_.find(_.id == "c").get.result.isLeft))
      }

  test("the model can request a bounded typed compute plan, never executable JavaScript"):
    val text = """{"compute":[{"id":"a","calculate":"2+2"},{"id":"b","calculate":"3*7","after":["a"]}]}"""
    assert(Knowledge.parse(text).exists(_.isInstanceOf[Knowledge.Step.Compute]))
    assert(Knowledge.parse("""{"compute":[{"id":"a","eval":"alert(1)"}]}""").isLeft)
    assert(Knowledge.parse("""{"compute":[{"id":"a","compute":[]}]}""").isLeft)
    assert(Knowledge.baseline("2 + 2\n3 * 7", Vector.empty).isInstanceOf[Knowledge.Step.Compute])
