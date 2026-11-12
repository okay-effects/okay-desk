package okay.desk.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.business.scala3.*
import okay.business.web.InThePage
import okay.desk.ui.{App, Disk, Model}
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
  type Row[X] = (Async + Ui + Fetch + Disk + Model + Clipboard)[X]
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    val search = new dom.URLSearchParams(dom.window.location.search)
    locally:
      Engine.status = s => Option(dom.document.getElementById("engine-status")).foreach(_.textContent = s)
      // the last visit ended somewhere (a phone killed the tab): said, then forgotten
      Engine.lastStage.foreach { st => Engine.status(s"the last visit ended at: $st"); dom.window.localStorage.setItem("okay-desk.stage", "") }
      // a failure nobody caught is written where the person can read it (a phone shows no console)
      dom.window.addEventListener("error", (e: dom.ErrorEvent) => Engine.status(s"error: ${e.message}"))
      dom.window.addEventListener("unhandledrejection", (e: dom.Event) =>
        Engine.status("error: " + e.asInstanceOf[js.Dynamic].reason.toString))
      val phone = dom.window.innerWidth < 700 || dom.window.navigator.asInstanceOf[js.Dynamic].maxTouchPoints.asInstanceOf[Int] > 1
      Engine.phone = phone
      Execution.cpu.prepare().failed.foreach(_ => ())
      val _ = Async.start(Dom.run(root, Look.skin, follow = true)(InThePage.fetching(OnDisk.disk(Engine.model(OnClipboard.inPage(
        App.main[Row](search.getAll("open").toList, phone, search.has("understanding"),
          Engine.recoveredQuestion,
          // ?logic: the screen of the logic tasks the model reads, in the browser's language
          Option.when(search.has("logic"))(dom.window.navigator.language.toLowerCase.startsWith("ru")),
          // the model is chosen and loaded by itself and the chat says which; ?models (and the experimental
          // ?understanding, and the self-test) keep the form a person chooses a model by
          chooser = search.has("models") || search.has("understanding") || search.has("selftest")))))))) 
      // WHERE THE PAGE STANDS. A screen of tasks begins at its top: when task cards appear where there were none,
      // the page goes to the top. A conversation shows what is new in it: when an entry appears (the question, a
      // step, the model loading, an answer), the page goes to the beginning of the first new one — to the end when
      // it is short — so a long task is read from its start, the progress under it is seen as it comes, and an
      // answer from its first words. (The face draws a screen with other buttons from its top: on a chat that hid
      // the answer just given.)
      var tasksShown = false
      val seen = scala.collection.mutable.Set.empty[String]
      val chat = ".okay-mark-user, .okay-mark-model, .okay-mark-system, .okay-mark-result, .okay-mark-check, " +
        ".okay-mark-proof, .okay-mark-thinking"
      def place(): Unit =
        val shown = dom.document.querySelector(".okay-mark-task") != null
        val entries = (0 until dom.document.querySelectorAll(chat).length).toVector
          .map(k => dom.document.querySelectorAll(chat)(k).asInstanceOf[dom.html.Element])
        def key(e: dom.html.Element): String = e.className + "|" + e.textContent.take(120)
        // what is new in the last turn: from the last question on (the model's line moving to the top is not news)
        val turn = entries.drop(math.max(0, entries.lastIndexWhere(_.className.contains("okay-mark-user"))))
        val fresh = turn.filterNot(e => seen(key(e)))
        // after the face has placed the page: now, and once more when the layout has settled
        def go(y: => Double): Unit = for wait <- List(0, 200, 600) do
          val _ = js.timers.setTimeout(wait)(dom.window.scrollTo(0, y.toInt))
        if shown && !tasksShown then go(0)
        else if !shown then fresh.headOption.foreach { first =>
          def y: Double = math.min(first.getBoundingClientRect().top + dom.window.scrollY - 12,
            (dom.document.documentElement.scrollHeight - dom.window.innerHeight).toDouble)
          go(y) }
        if entries.isEmpty then seen.clear()
        seen ++= entries.map(key)
        tasksShown = shown
      new dom.MutationObserver((_, _) => place())
        .observe(root, new dom.MutationObserverInit { childList = true; subtree = true })
      place()
      // THE COLLECTION MADE WHILE NOTHING HAPPENS: a task of it is drawn, and its puzzle solved to check that it has
      // one answer, the first time it is asked for — all 233 at once on "Examples" (a second on a computer, several
      // on a phone). They are made a few at a time in the page's idle moments instead, the first page first.
      val entries = okay.desk.tasks.Tasks.entries
      var made = 0
      lazy val warm: js.Function1[js.Dynamic, Unit] = deadline =>
        def left: Double = if js.isUndefined(deadline) then 0.0 else deadline.timeRemaining().asInstanceOf[Double]
        var first = true
        while made < entries.size && (first || left > 4) do
          val _ = entries(made).task
          made += 1
          first = false
        if made < entries.size then later()
      def later(): Unit =
        if js.typeOf(dom.window.asInstanceOf[js.Dynamic].requestIdleCallback) == "function" then
          val _ = dom.window.asInstanceOf[js.Dynamic].requestIdleCallback(warm, js.Dynamic.literal(timeout = 2000))
        else { val _ = js.timers.setTimeout(50)(warm(js.undefined.asInstanceOf[js.Dynamic])) }
      later()
      if search.has("selftest") then selftest()
      if search.has("cache-test") then GpuCacheTest.run()
      if search.has("execution-test-model") then
        val (dir, layer) = Model.readers(Model.default)
        Engine.loadReaders(dir, layer).foreach {
          case Right(_) => ExecutionTest.run(withModel = true)
          case Left(error) => Engine.status("execution-test: failed: " + error)
        }
      else if search.has("execution-test") then ExecutionTest.run()

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
    // Only the production student is exposed; an old comparison-model URL falls back to it.
    val chosen = Option(new dom.URLSearchParams(dom.window.location.search).get("model")).filter(Model.reads)
      .getOrElse(Model.default)
    val (dir, layer) = Model.readers(chosen)
    Engine.loadReaders(dir, layer).flatMap {
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
    }.recover { case t => say(s"selftest: failed: $t\n" + t.getStackTrace.take(14).mkString("\n")) }

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
       |body .okay-face .okay-mark-system .okay-text { white-space: pre-wrap; overflow-wrap: anywhere; }
       |body .okay-face .okay-mark-proof { padding: 8px 12px; border-radius: 12px; margin: 4px 0;
       |  border: 1px solid var(--okay-border); }
       |body .okay-face .okay-mark-proof .okay-text { white-space: pre-wrap; overflow-wrap: anywhere; }
       |body .okay-face .okay-mark-result { padding: 8px 12px; border-radius: 12px; margin: 4px 0;
       |  background: color-mix(in srgb, var(--okay-surface) 92%, currentColor 8%); }
       |body .okay-face .okay-mark-example, body .okay-face .okay-mark-task {
       |  padding: 8px 12px; border-radius: 12px; margin: 8px 0;
       |  background: color-mix(in srgb, var(--okay-surface) 94%, var(--okay-accent) 6%); }
       |body .okay-face pre, body .okay-face .okay-mark-code { overflow: auto; font-size: .85rem; }
       |body .okay-face .okay-mark-user .okay-mark-code .okay-text,
       |body .okay-face .okay-mark-model .okay-mark-code .okay-text,
       |body .okay-face .okay-mark-system .okay-mark-code .okay-text {
       |  white-space: pre-wrap; overflow-wrap: anywhere; }
       |body .okay-face .okay-mark-tables .okay-mark-slot .okay-group, body .okay-face .okay-mark-models .okay-mark-slot .okay-group {
       |  display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0; }
       |body .okay-face textarea[id^="f-ask"] { min-height: 5.5em; }
       |/* THE ORDER OF THE PAGE (okay-faces draws every form after the parts, with its button): the page is one flex
       |   column, the view's groups and the actions' bar dissolved into it, so the forms of the files and the address
       |   stand before the conversation and the question's form after it */
       |body .okay-face > div { display: flex; flex-direction: column; gap: 12px; }
       |body .okay-face > div > .okay-view, body .okay-face > div > .okay-view > .okay-group,
       |body .okay-face > div > nav.okay-actions { display: contents; }
       |body .okay-face .okay-mark-top { order: -3; }
       |body .okay-face > div > nav.okay-actions > form:has(#f-the-model) { order: -2; }
       |body .okay-face .okay-mark-loading { order: -2; }
       |body .okay-face .okay-mark-model-note, body .okay-face .okay-mark-data-note { order: -1; }
       |body .okay-face .okay-mark-data-note .okay-mark-slot .okay-group, body .okay-face .okay-mark-back .okay-mark-slot .okay-group,
       |body .okay-face .okay-mark-chat-actions .okay-mark-slot .okay-group,
       |body .okay-face .okay-mark-choice .okay-mark-slot .okay-group {
       |  display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0; }
       |body .okay-face .okay-mark-example .okay-mark-slot, body .okay-face .okay-mark-task .okay-mark-slot { float: right; }
       |body .okay-face > div > nav.okay-actions > form { order: 1; }
       |body .okay-face .okay-mark-user, body .okay-face .okay-mark-model, body .okay-face .okay-mark-system,
       |body .okay-face .okay-mark-result, body .okay-face .okay-mark-source, body .okay-face .okay-mark-proof,
       |body .okay-face .okay-mark-clarify, body .okay-face .okay-mark-check { order: 2; }
       |/* the check of an answer to a task of the collection: a person's under the form, the desk's in the chat */
       |body .okay-face .okay-mark-check { padding: 8px 12px; border-radius: 12px; margin: 4px 0; font-weight: 600;
       |  border: 1px solid var(--okay-accent); }
       |body .okay-face .okay-mark-source { padding: 8px 12px; border-left: 2px solid var(--okay-border); }
       |body .okay-face .okay-mark-chat-actions { order: 3; }
       |body .okay-face > div > nav.okay-actions > form:has([id^="f-ask"]) { order: 4; }
       |/* a question's steps where its answer will be: after the conversation, in the question form's place */
       |body .okay-face .okay-mark-thinking { order: 3; padding: 8px 12px; border-radius: 12px; margin: 4px 0;
       |  border: 1px dashed rgba(127, 127, 127, .45); }
       |/* the data screen: the back button, the tables, the forms of the files and the address, then the examples */
       |body .okay-face .okay-group:has(.okay-mark-example) { order: 2; }
       |""".stripMargin
  val skin: Skin =
    val calm = Skin.calm(accent = "#2b5bd7", deep = "#1e2a5a")
    Skin(calm.look, calm.css + marks)
