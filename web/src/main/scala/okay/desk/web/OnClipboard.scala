package okay.desk.web

import okay.business.model.Clipboard
import okay.scala3.*
import okay.scala3.std.Async
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** Await the browser's permission/result; a rejected clipboard write never reports success. */
object OnClipboard:
  private def write(text: String): Future[Boolean] =
    try
      val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
      if js.isUndefined(clipboard) then Future.successful(false)
      else clipboard.writeText(text).asInstanceOf[js.Promise[Unit]].toFuture.map(_ => true)
        .recover { case _ => false }
    catch case _: Exception => Future.successful(false)

  def inPage[R[_], Rest[_], A](p: A ! R)(using Split[Clipboard, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Clipboard, Rest, A, A](a => pure(a))(
      [X] => (op: Clipboard[X], k: X => A ! Rest) =>
        val result: Future[X] = op match
          case Clipboard.Copy(text) => write(text)
        Async.fromFuture[X, Rest](result).flatMap(k)))
