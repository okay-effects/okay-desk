package okay.desk.exec

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

/** Independent bounded lanes: only a resource shared by jobs imposes ordering. Work starts on submission, not on
  * awaiting its result. A lane does not turn synchronous code into background work: CPU callers use real Workers.
  */
final class AsyncLane(val parallelism: Int, maxQueued: Int = 64)(using ExecutionContext):
  require(parallelism > 0 && maxQueued > 0)
  private val queue = mutable.Queue.empty[() => Unit]
  private var running = 0
  def active: Int = synchronized(running)
  def waiting: Int = synchronized(queue.size)

  def submit[A](work: => Future[A]): Future[A] = synchronized {
    if queue.size >= maxQueued then Future.failed(IllegalStateException("computation queue is full"))
    else
      val p = Promise[A]()
      queue.enqueue(() => {
        val started = Try(work).fold(Future.failed, identity)
        started.onComplete { result =>
          p.tryComplete(result)
          synchronized { running -= 1; pump() }
        }
      })
      pump()
      p.future
  }

  private def pump(): Unit =
    while running < parallelism && queue.nonEmpty do
      running += 1
      queue.dequeue()()
