package okay.desk.web

import okay.llm.compute.OnWebGl2
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** Actual WebGL2 checks for compact prefix storage and refusal before an oversized texture allocation. */
object GpuCacheTest:
  def run(): Unit =
    val out = dom.document.createElement("pre")
    out.id = "gpu-cache-test"
    out.textContent = "running"
    dom.document.body.appendChild(out)
    try
      val c = OnWebGl2.create(0L, 2, 1048576L, true).fold(e => throw IllegalStateException(e), identity)
      val values = Array.tabulate(128)(i => (i - 64).toFloat / 31)
      val x = c.tensor(2, 64, values)
      val before = c.bytes
      val compact = c.cache(x)
      require(c.bytes < before, "compaction did not reduce live storage")
      val restored = c.slice(compact, 0, 2)
      c.rows(restored, Seq(0, 1)).map { rows =>
        val error = rows.flatten.zip(values).map((a, b) => math.abs(a - b)).max
        require(error < 0.002f, s"FP16 prefix error $error")
        c.release(restored); c.release(compact)
        require(c.bytes == 0, "prefix tensors were not released")
        var refused = false
        try c.tensor(8192, 64, new Array[Float](8192 * 64))
        catch case e: IllegalArgumentException => refused = e.getMessage.contains("memory budget reached")
        require(refused && c.peakBytes <= 1048576L, "oversized allocation exceeded its budget")
        c.close()
        out.textContent = s"passed: FP16 prefix error $error; oversized texture refused before allocation"
      }.recover { case e => c.close(); out.textContent = "failed: " + e.getMessage }
    catch case e: Exception => out.textContent = "failed: " + e.getMessage
