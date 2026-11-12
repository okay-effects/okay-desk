package okay.desk.web

import okay.desk.exec.{Computation, ComputePlan}
import okay.desk.puzzle.{Constraint, Puzzle, Ref}
import okay.desk.skill.Read
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import org.scalajs.dom

/** Opt-in browser diagnostics: actual CPU work, concurrency, cancellation, recovery and a UI heartbeat. */
object ExecutionTest:
  def run(withModel: Boolean = false): Unit =
    val out = dom.document.createElement("pre")
    out.id = "execution-test"
    dom.document.body.appendChild(out)
    def say(s: String): Unit = out.textContent = out.textContent + s + "\n"
    val pool = Execution.CpuPool(2)
    val heartbeats = scala.collection.mutable.ArrayBuffer.empty[Double]
    val clock = js.timers.setInterval(5)(heartbeats += js.Date.now())
    val puzzle = Puzzle.classic.copy(constraints =
      Puzzle.classic.constraints :+
        Constraint.Same(Ref("nation", "Englishman"), Ref("colour", "green"))
    )
    val heavy = Computation.Job.Solve(puzzle, 10)
    val number = Computation.Job.Calculate("2 + 2", "2 + 2")
    def running(id: Int): Future[Unit] =
      if pool.events.exists(e => e.id == id && e.phase == "running") then Future.unit
      else
        val p = Promise[Unit]()
        js.timers.setTimeout(5)(running(id).onComplete(p.complete))
        p.future
    (for
      _ <- Future.sequence(Vector(pool.execute(number), pool.execute(number)))
      a = pool.submit(heavy)
      b = pool.submit(heavy)
      _ <- Future.sequence(Vector(running(a.id), running(b.id)))
      modelStart = js.Date.now()
      _ <-
        if !withModel then Future.unit
        else
          require(Engine.on != null && Engine.on.api != "CPU", "a GPU model path was not loaded")
          for
            ids <- Engine.on.handler(Read.Prompt("Read the relation.", "Moby is a whale."))
            states <- Execution.model.submit(Engine.on.handler(Read.States(ids, Vector(ids.length - 1))))
          yield require(states.nonEmpty && states.head.forall(_.isFinite), "invalid native model states")
      modelEnd = js.Date.now()
      results <- Future.sequence(Vector(a.result, b.result))
      _ = require(results.forall(_.isRight), results.toString)
      starts = pool.events.filter(e => Set(a.id, b.id)(e.id) && e.phase == "running")
      ends = pool.events.filter(e => Set(a.id, b.id)(e.id) && e.phase == "finished")
      _ = require(starts.size == 2 && starts.map(_.worker).distinct.size == 2, "not two real CPU workers")
      _ = require(starts.map(_.at).max < ends.map(_.at).min, "CPU computations did not overlap")
      _ = if withModel then
        require(modelStart < ends.map(_.at).max, "CPU jobs had ended before the model forward")
        say(s"native ${Engine.on.api} forward overlapped CPU work (${modelEnd - modelStart} ms)")
      beats = heartbeats.count(t => t > starts.map(_.at).min && t < ends.map(_.at).max)
      _ = require(beats >= 2, "the page did not keep ticking during CPU work")
      _ = say(s"two CPU workers overlapped; $beats page heartbeats during computation")
      cancelled = pool.submit(heavy)
      _ <- running(cancelled.id)
      _ = cancelled.cancel()
      stopped <- cancelled.result
      _ = require(stopped == Left("computation cancelled"), stopped.toString)
      recovered <- pool.execute(number)
      _ = require(recovered == Right(Computation.Output.Number("4")), recovered.toString)
      plan <- ComputePlan.run(
        Vector(
          ComputePlan.Node("one", number),
          ComputePlan.Node("two", number),
          ComputePlan.Node("three", number, Vector("one", "two"))
        )
      )(pool.execute)
      _ = require(plan.exists(_.forall(_.result.isRight)), plan.toString)
    yield
      say("cancellation, worker replacement and dependency plan passed")
      say("execution-test: done")
    ).recover { case e => say("execution-test: failed: " + e.toString) }.foreach { _ =>
      js.timers.clearInterval(clock)
      pool.close()
    }
