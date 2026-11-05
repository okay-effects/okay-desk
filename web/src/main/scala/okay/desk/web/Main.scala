package okay.desk.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.business.scala3.*
import okay.business.web.InThePage
import okay.desk.ui.{App, Model}
import okay.http.Fetch
import okay.scala3.std.Async
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * THE PAGE, in one line: the desk's program (okay-ui) drawn by okay-ui's DOM face, its effects answered by this
 * browser — `Fetch` by its `fetch`, `Model` by the engine on its GPU. `?open=<url>` (repeated) opens files as it
 * starts; `?selftest` runs the sample's questions through the readers and writes the score. examples.html runs the
 * examples' screen instead.
 */
object Main:
  type Row[X] = (Async + Ui + Fetch + Model)[X]
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    val search = new dom.URLSearchParams(dom.window.location.search)
    if dom.document.getElementById("examples") != null then
      val _ = Async.start(Dom.run(root, Look.skin)(Examples.program[Async + Ui]).map(urls =>
        dom.window.location.href = Examples.link(urls)))
    else
      Engine.status = s => Option(dom.document.getElementById("engine-status")).foreach(_.textContent = s)
      // a failure nobody caught is written where the person can read it (a phone shows no console)
      dom.window.addEventListener("error", (e: dom.ErrorEvent) => Engine.status(s"error: ${e.message}"))
      dom.window.addEventListener("unhandledrejection", (e: dom.Event) =>
        Engine.status("error: " + e.asInstanceOf[js.Dynamic].reason.toString))
      val phone = dom.window.innerWidth < 700 || dom.window.navigator.asInstanceOf[js.Dynamic].maxTouchPoints.asInstanceOf[Int] > 1
      val _ = Async.start(Dom.run(root, Look.skin, follow = true)(InThePage.fetching(Engine.model(
        App.main[Row](search.getAll("open").toList, phone)))))
      if search.has("selftest") then selftest()

  /** THE PAGE'S SELF-TEST (`?selftest`): the readers loaded on the page's device, the sample's questions in both
   * languages read into programs and run by the exact engine, each against the gold's result — the JVM bench's
   * measure, in the browser; written into the page (#selftest) */
  def selftest(): Unit =
    import okay.desk.*
    val out = dom.document.createElement("pre"); out.id = "selftest"
    dom.document.body.appendChild(out)
    def say(s: String): Unit = { out.textContent = out.textContent + s + "\n"; println(s) }
    val now = () => js.Dynamic.global.performance.now().asInstanceOf[Double]
    val ts = Sample.tables
    val golds = Sample.questions.map(q => Sample.run(Query.parse(q.gold).toOption.get).toOption.get)
    val t0 = now()
    Engine.loadReaders("model", 19).flatMap {
      case Left(why) => Future.successful(Left(why))
      case Right(what) =>
        say(s"$what (${((now() - t0) / 1000).toInt} s)")
        val asks = for (q, i) <- Sample.questions.zipWithIndex; (lang, text) <- Seq("en" -> q.en, "ru" -> q.ru) yield (i, lang, text)
        asks.foldLeft(Future.successful(Map("en" -> 0, "ru" -> 0))) { (acc, a) => acc.flatMap { right =>
          val (i, lang, text) = a
          val t1 = now()
          Engine.program(ts, text, None).map { got =>
            val result = got.flatMap(Query.parse).flatMap(Sample.run)
            val ok = result.exists(Sample.same(_, golds(i)))
            say(f"[$lang ${i + 1}] ${if ok then "ok" else "MISS"} ${(now() - t1) / 1000}%.1f s: ${got.fold(e => e, identity)}")
            if ok then right.updated(lang, right(lang) + 1) else right
          }
        } }.map(right => Right(s"en: ${right("en")}/${Sample.questions.size} | ru: ${right("ru")}/${Sample.questions.size}"))
    }.map {
      case Right(line) => say(s"readers in the page on ${Engine.on.api}: $line"); say("selftest: done")
      case Left(why) => say(s"selftest: failed: $why")
    }.recover { case t => say(s"selftest: failed: $t") }

/** the look: okay-ui's calm skin, and the marks of the desk — the top bar, the tables, the conversation's lines */
object Look:
  def marks: String =
    """|body .okay-face { padding: 0 14px 80px; max-width: 1000px; margin: 0 auto; }
       |body .okay-face .okay-heading-1 { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
       |body .okay-face .okay-mark-top { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12px; padding: 14px 0 6px; }
       |body .okay-face .okay-mark-brand { font-weight: 700; font-size: 1.15rem; letter-spacing: .01em; }
       |body .okay-face .okay-mark-small { font-size: .88rem; opacity: .8; }
       |body .okay-face .okay-mark-table { padding: 8px 12px; border-radius: 12px; margin: 6px 0;
       |  background: color-mix(in srgb, var(--okay-surface) 94%, var(--okay-accent) 6%); }
       |body .okay-face .okay-mark-table .okay-mark-code { max-height: 9rem; overflow: auto; font-size: .8rem; }
       |body .okay-face .okay-mark-table .okay-mark-slot { float: right; }
       |body .okay-face .okay-mark-user { padding: 8px 12px; border-radius: 12px; margin: 10px 0 4px;
       |  background: color-mix(in srgb, var(--okay-accent) 12%, var(--okay-surface)); }
       |body .okay-face .okay-mark-model { padding: 8px 12px; border-radius: 12px; margin: 4px 0;
       |  background: color-mix(in srgb, #2e8b57 10%, var(--okay-surface)); }
       |body .okay-face .okay-mark-system { padding: 6px 12px; border-radius: 12px; margin: 4px 0; opacity: .85;
       |  background: color-mix(in srgb, #c27c0e 10%, var(--okay-surface)); }
       |body .okay-face .okay-mark-result { padding: 8px 12px; border-radius: 12px; margin: 4px 0;
       |  background: color-mix(in srgb, var(--okay-surface) 92%, currentColor 8%); }
       |body .okay-face .okay-mark-example { padding: 8px 12px; border-radius: 12px; margin: 8px 0;
       |  background: color-mix(in srgb, var(--okay-surface) 94%, var(--okay-accent) 6%); }
       |body .okay-face pre, body .okay-face .okay-mark-code { overflow: auto; font-size: .85rem; }
       |""".stripMargin
  val skin: Skin =
    val calm = Skin.calm(accent = "#2b5bd7", deep = "#1e2a5a")
    Skin(calm.look, calm.css + marks)
