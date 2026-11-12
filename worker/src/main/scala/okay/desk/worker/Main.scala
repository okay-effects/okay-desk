package okay.desk.worker

import okay.desk.exec.{Computation, Wire}
import scala.scalajs.js

/** Dedicated CPU Worker. Only the closed computation algebra is executable; the page never sends JavaScript. */
object Main:
  def main(args: Array[String]): Unit =
    val self = js.Dynamic.global.self
    self.onmessage = ((event: js.Dynamic) =>
      val text = event.data.asInstanceOf[String]
      Wire.decode[Computation.Request](text) match
        case Left(problem)  => self.postMessage(Wire.encode(Computation.Reply(-1, Left(problem), 0)))
        case Right(request) =>
          val start = js.Date.now()
          self.postMessage(s"running:${request.id}:$start")
          val result = Computation.run(request.job)
          self.postMessage(Wire.encode(Computation.Reply(request.id, result, js.Date.now() - start, start)))
    ): js.Function1[js.Dynamic, Unit]
    self.postMessage("ready")
