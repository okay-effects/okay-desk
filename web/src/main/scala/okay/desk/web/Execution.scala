package okay.desk.web

import okay.desk.exec.{AsyncLane, Computation, Wire}
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import org.scalajs.dom

/** CPU tools have their own workers; model/KV-cache access has one independent lane. Awaiting one resource never holds
  * another. This is bounded placement, not a claim to an optimal scheduler or a GPU implementation of SAT.
  */
object Execution:
  val model = AsyncLane(1)
  private lazy val version: String =
    val script = dom.document.querySelector("script[src*=main]")
    Option(script).flatMap(s => Option(s.getAttribute("src"))).filter(_.contains("?"))
      .map(s => s.substring(s.indexOf("?"))).getOrElse("")
  def versioned(path: String): String = path + version
  private var pool: Option[CpuPool] = None
  def cpu: CpuPool = pool.getOrElse {
    val cores = dom.window.navigator
      .asInstanceOf[js.Dynamic]
      .hardwareConcurrency
      .asInstanceOf[js.UndefOr[Int]]
      .getOrElse(2)
    val p = CpuPool(math.max(1, math.min(2, cores - 2)), versioned("worker.js"))
    pool = Some(p)
    p
  }

  final case class Ticket(id: Int, result: Future[Either[String, Computation.Output]], cancel: () => Unit)
  final case class Event(id: Int, worker: Int, phase: String, at: Double, elapsedMs: Double = 0)

  final class CpuPool(val size: Int, workerUrl: String = "worker.js", timeoutMs: Int = 120000):
    require(size >= 1 && size <= 4 && timeoutMs > 0)
    private case class Pending(id: Int, data: String, promise: Promise[Either[String, Computation.Output]])
    private val waiting = mutable.Queue.empty[Pending]
    private val slots = Array.fill[Option[Slot]](size)(None)
    private var nextId = 0
    var events = Vector.empty[Event]
    private def record(e: Event): Unit = events = (events :+ e).takeRight(256)
    def active: Int = slots.count(_.exists(_.job.nonEmpty))
    def queued: Int = waiting.size

    // One bundle fetch per pool. New/replacement workers remain available after the page goes offline.
    private lazy val script: Future[String] = dom.fetch(workerUrl).toFuture.flatMap { response =>
      if !response.ok then Future.failed(IllegalStateException(s"CPU worker bundle: HTTP ${response.status}"))
      else response.text().toFuture
    }
    def prepare(): Future[Unit] = script.map(_ => ())

    private class Slot(val index: Int):
      var worker: Option[dom.Worker] = None
      var blobUrl: Option[String] = None
      var ready = false
      var job: Option[Pending] = None
      var timer: Option[js.timers.SetTimeoutHandle] = None
      def post(data: String): Unit = worker.foreach(_.postMessage(data))
      def dispose(): Unit =
        worker.foreach(_.terminate())
        blobUrl.foreach(dom.URL.revokeObjectURL)
        blobUrl = None
      script.foreach { code =>
        if slots(index).contains(this) then
          try
            val blob = js.Dynamic
              .newInstance(js.Dynamic.global.Blob)(js.Array(code), js.Dynamic.literal(`type` = "text/javascript"))
              .asInstanceOf[dom.Blob]
            val url = dom.URL.createObjectURL(blob)
            blobUrl = Some(url)
            val w = new dom.Worker(url)
            worker = Some(w)
            w.onmessage = (event: dom.MessageEvent) =>
              val data = event.data.asInstanceOf[String]
              if data == "ready" then
                ready = true
                blobUrl.foreach(dom.URL.revokeObjectURL); blobUrl = None
                job.foreach(p => post(p.data))
              else if job.exists(p => data.startsWith(s"running:${p.id}:")) then
                data.split(":").lastOption.flatMap(_.toDoubleOption).filter(_.isFinite).foreach { time =>
                  record(Event(job.get.id, index, "running", time))
                }
              else
                Wire.decode[Computation.Reply](data) match
                  case Right(reply) if job.exists(_.id == reply.id) =>
                    val p = job.get
                    timer.foreach(js.timers.clearTimeout); timer = None; job = None
                    record(Event(p.id, index, "finished", reply.startedAt + reply.elapsedMs, reply.elapsedMs))
                    p.promise.trySuccess(reply.result)
                    pump()
                  case _ => failed(this, "CPU worker returned an invalid result")
            w.onerror = (event: dom.ErrorEvent) =>
              event.preventDefault()
              failed(
                this,
                "CPU worker failed: " + Option(event.message).filter(_ != "undefined").getOrElse("script error")
              )
            w.addEventListener("messageerror", (_: dom.Event) => failed(this, "CPU worker message could not be read"))
          catch case e: Exception => failed(this, "cannot start CPU worker: " + e.getMessage)
      }
      script.failed.foreach(e => failed(this, "cannot load CPU worker: " + e.getMessage))

    def submit(job: Computation.Job): Ticket =
      nextId += 1
      val id = nextId
      val p = Promise[Either[String, Computation.Output]]()
      val encoded = scala.util.Try(Wire.encode(Computation.Request(id, job))).toEither
      encoded match
        case Left(error) => p.success(Left(s"cannot encode computation: ${error.getMessage}"))
        case Right(data) if data.length > 16 * 1024 * 1024 => p.success(Left("computation input exceeds 16 MB"))
        case Right(_) if waiting.size >= 64                => p.success(Left("CPU computation queue is full"))
        case Right(data)                                   => waiting.enqueue(Pending(id, data, p)); pump()
      Ticket(id, p.future, () => cancel(id))

    def execute(job: Computation.Job): Future[Either[String, Computation.Output]] = submit(job).result

    def cancel(id: Int): Unit =
      waiting.dequeueAll(_.id == id).foreach(_.promise.trySuccess(Left("computation cancelled")))
      slots.flatten.find(_.job.exists(_.id == id)).foreach(s => failed(s, "computation cancelled"))

    private def failed(slot: Slot, message: String): Unit =
      if slots(slot.index).contains(slot) then
        slot.timer.foreach(js.timers.clearTimeout)
        slot.dispose()
        slots(slot.index) = None
        slot.job.foreach { p =>
          record(Event(p.id, slot.index, "failed", js.Date.now()))
          p.promise.trySuccess(Left(message))
        }
        slot.job = None
        pump()

    private def pump(): Unit =
      for i <- slots.indices if waiting.nonEmpty && slots(i).forall(_.job.isEmpty) do
        val p = waiting.dequeue()
        try
          val slot = slots(i).getOrElse { val s = Slot(i); slots(i) = Some(s); s }
          slot.job = Some(p)
          record(Event(p.id, i, "started", js.Date.now()))
          slot.timer = Some(js.timers.setTimeout(timeoutMs)(failed(slot, "CPU computation timed out")))
          if slot.ready then slot.post(p.data)
        catch
          case e: Exception =>
            p.promise.trySuccess(Left("cannot start CPU worker: " + e.getMessage))
            js.timers.setTimeout(0)(pump())

    def close(): Unit =
      waiting.dequeueAll(_ => true).foreach(_.promise.trySuccess(Left("executor closed")))
      slots.flatten.foreach(s => failed(s, "executor closed"))
