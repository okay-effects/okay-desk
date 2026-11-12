package okay.desk

import okay.desk.exec.AsyncLane
import scala.concurrent.{ExecutionContext, Future, Promise}

class AsyncLaneSuite extends munit.FunSuite:
  private given ExecutionContext = ExecutionContext.parasitic

  test("a pending model/GPU operation does not acquire a CPU lane"):
    val model = AsyncLane(1)
    val cpu = AsyncLane(2)
    val pending = Promise[Int]()
    val first = model.submit(pending.future)
    var modelSecondStarted = false
    val second = model.submit { modelSecondStarted = true; Future.successful(2) }
    val other = cpu.submit(Future.successful(3))
    assert(other.isCompleted)
    assert(!modelSecondStarted && !first.isCompleted && !second.isCompleted)
    pending.success(1)
    second.map { n =>
      assertEquals(n, 2); assert(modelSecondStarted)
    }

  test("bounded queues reject overload and a failed task releases the resource"):
    val lane = AsyncLane(1, maxQueued = 1)
    val pending = Promise[Int]()
    val first = lane.submit(pending.future)
    val second = lane.submit(Future.successful(2))
    val refused = lane.submit(Future.successful(3))
    assert(refused.isCompleted && lane.active == 1 && lane.waiting == 1)
    pending.failure(IllegalStateException("failed"))
    first.failed.flatMap(_ => second).map(n => assertEquals(n, 2))
