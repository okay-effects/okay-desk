package okay.desk.web

import com.raquo.laminar.api.L.*
import okay.desk.*
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/**
 * THE PAGE (specs/data.md): files opened from the machine (File System Access, or an input for the other browsers),
 * the model in the page (WebLLM on WebGPU, cached after the first load), the chat. A question → the model writes a
 * program → an exact handler runs it → the result and the program shown; the model's one-sentence summary only when
 * every number in it is in the result. Nothing leaves the browser.
 */
object Main:
  val tables: Var[Map[String, Table]] = Var(Map.empty)
  val status: Var[String] = Var("the model is not loaded")
  val busy: Var[Boolean] = Var(false)
  val log: Var[Vector[HtmlElement]] = Var(Vector.empty)
  var engine: js.Dynamic = null

  // ---- the model
  private def webllm: js.Dynamic = js.Dynamic.global.window.webllm
  def loadModel(id: String): Future[Unit] =
    if js.isUndefined(webllm) then { status.set("WebLLM did not load (no network for the first load?)"); Future.unit }
    else
      busy.set(true); status.set(s"loading $id…")
      val opts = js.Dynamic.literal(initProgressCallback = ((p: js.Dynamic) => status.set(p.text.asInstanceOf[String])): js.Function1[js.Dynamic, Unit])
      webllm.CreateMLCEngine(id, opts).asInstanceOf[js.Promise[js.Dynamic]].toFuture
        .map { e => engine = e; status.set(s"$id ready"); busy.set(false) }
        .recover { case t => status.set(s"cannot load $id: ${t.getMessage}"); busy.set(false) }

  def complete(messages: Vector[(String, String)], max: Int): Future[String] =
    val req = js.Dynamic.literal(
      messages = messages.map((r, c) => js.Dynamic.literal(role = r, content = c)).toJSArray,
      temperature = 0, max_tokens = max,
      extra_body = js.Dynamic.literal(enable_thinking = false))
    engine.chat.completions.create(req).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .map(r => r.choices.asInstanceOf[js.Array[js.Dynamic]].head.message.content.asInstanceOf[String])

  // ---- the files
  def addFile(name: String, text: String): Unit =
    Table.parse(name, text) match
      case Right(t) => tables.update(_ + (t.name -> t)); say("system", s"opened $name as table ${t.name}: ${t.size} rows, ${t.columns.size} columns")
      case Left(e) => say("system", s"$name not read: $e")

  private def readFile(file: dom.File): Future[Unit] =
    file.text().toFuture.map(text => addFile(file.name, text))

  def openFiles(): Unit =
    val w = js.Dynamic.global.window
    if !js.isUndefined(w.showOpenFilePicker) then
      w.showOpenFilePicker(js.Dynamic.literal(multiple = true)).asInstanceOf[js.Promise[js.Array[js.Dynamic]]].toFuture
        .flatMap(hs => Future.traverse(hs.toVector)(h => h.getFile().asInstanceOf[js.Promise[dom.File]].toFuture.flatMap(readFile)))
        .recover { case _ => () }
    else dom.document.getElementById("files").asInstanceOf[dom.html.Input].click()

  def openFolder(): Unit =
    val w = js.Dynamic.global.window
    if !js.isUndefined(w.showDirectoryPicker) then
      def walk(dir: js.Dynamic, depth: Int): Future[Unit] =
        val it = dir.values()
        def next(): Future[Unit] = it.next().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { r =>
          if r.done.asInstanceOf[Boolean] then Future.unit
          else
            val h = r.value
            val step: Future[Unit] =
              if h.kind.asInstanceOf[String] == "file" then
                val n = h.name.asInstanceOf[String].toLowerCase
                if n.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$") then h.getFile().asInstanceOf[js.Promise[dom.File]].toFuture.flatMap(readFile)
                else Future.unit
              else if depth < 3 then walk(h, depth + 1) else Future.unit
            step.flatMap(_ => next())
        }
        next()
      w.showDirectoryPicker().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap(walk(_, 0)).recover { case _ => () }
    else dom.document.getElementById("folder").asInstanceOf[dom.html.Input].click()

  private def fromInput(input: dom.html.Input): Unit =
    val fs = (0 until input.files.length).map(input.files(_)).toVector
    Future.traverse(fs.filter(_.name.toLowerCase.matches(".*\\.(csv|tsv|json|jsonl|ndjson)$")))(readFile)

  // ---- the chat
  def say(role: String, text: String): Unit = log.update(_ :+ div(cls := s"msg $role", pre(text)))

  def ask(question: String): Future[Unit] =
    if engine == null then { say("system", "load the model first"); Future.unit }
    else if tables.now().isEmpty then { say("system", "open a file first"); Future.unit }
    else
      busy.set(true); say("user", question)
      val ts = tables.now()
      val sys = Prompt.system(ts)
      def run(answer: String, retried: Boolean): Future[Unit] =
        Prompt.program(answer) match
          case Left(words) => say("model", words); Future.unit
          case Right(json) =>
            val ran = Query.parse(json).flatMap {
              case q: Query.Relational => Exact.run(ts, q).map(r => (q: Query, r))
              case q: Query.Logical => Weights.either(ts, q).map((r, by) => (q: Query, r.copy(name = by)))
            }
            ran match
              case Left(problem) if !retried =>
                say("system", s"the program failed: $problem — asking the model to fix it")
                complete(Vector("system" -> sys, "user" -> question, "assistant" -> answer,
                  "user" -> s"That program failed: $problem. Reply with the corrected JSON only."), 600).flatMap(run(_, true))
              case Left(problem) => say("system", s"the program failed again: $problem"); Future.unit
              case Right((q, result)) =>
                log.update(_ :+ div(cls := "msg result",
                  pre(result.render(50)),
                  if result.name == "weights" then p(cls := "by", "computed by the transformer compiled from the closure program (okay-inlay), in this page") else span(),
                  detailsTag(summaryTag(s"the program (${result.size} rows)"), pre(Query.print(q)))))
                complete(Vector("user" -> Prompt.summarize(question, q, result)), 200).map { s =>
                  val words = s.replaceAll("(?s)<think>.*?</think>", "").trim
                  if Prompt.faithful(words, result) then say("model", words)
                  else say("system", "the model's summary stated a number not in the result and was dropped")
                }
      complete(Vector("system" -> sys, "user" -> question), 600).flatMap(run(_, false))
        .recover { case t => say("system", s"error: ${t.getMessage}") }
        .map(_ => busy.set(false))

  def page: HtmlElement =
    val question = Var("")
    val model = Var("Qwen3-1.7B-q4f16_1-MLC")
    div(
      h1("Chat with your data — in the browser, offline"),
      p("Open CSV, TSV or JSON files from your machine; a small model in the page writes the query, an exact engine runs it. ",
        "Numbers come from the engine, never from the model. Nothing leaves the browser."),
      div(cls := "bar",
        select(value <-- model.signal, onChange.mapToValue --> model,
          option(value := "Qwen3-1.7B-q4f16_1-MLC", "Qwen3 1.7B (~1 GB)"),
          option(value := "Qwen3-0.6B-q4f16_1-MLC", "Qwen3 0.6B (~0.4 GB)"),
          option(value := "Qwen2.5-3B-Instruct-q4f16_1-MLC", "Qwen2.5 3B (~1.7 GB)")),
        " ", button("Load the model", disabled <-- busy.signal, onClick --> { _ => loadModel(model.now()); () }),
        " ", button("Open files", onClick --> { _ => openFiles() }),
        " ", button("Open a folder", onClick --> { _ => openFolder() }),
        input(idAttr := "files", tpe := "file", multiple := true, display := "none", onChange --> { e => fromInput(e.target.asInstanceOf[dom.html.Input]) }),
        input(idAttr := "folder", tpe := "file", htmlAttr("webkitdirectory", com.raquo.laminar.codecs.BooleanAsAttrPresenceCodec) := true, display := "none",
          onChange --> { e => fromInput(e.target.asInstanceOf[dom.html.Input]) }),
        span(cls := "status", child.text <-- status.signal)),
      div(cls := "tables", children <-- tables.signal.map(ts => ts.values.toVector.sortBy(_.name).map(t =>
        detailsTag(summaryTag(s"${t.name}: ${t.size} rows"), pre(t.describe))))),
      div(cls := "log", children <-- log.signal),
      form(onSubmit.preventDefault --> { _ => val q = question.now().trim; if q.nonEmpty then { question.set(""); ask(q) } },
        input(cls := "q", placeholder := "ask about the data: how many orders are unpaid? total by customer? …",
          controlled(value <-- question.signal, onInput.mapToValue --> question)),
        " ", button(tpe := "submit", "Ask", disabled <-- busy.signal)),
      footerTag(p("Built on ", a(href := "https://okay-effects.github.io/", "okay-effects"), ". Need more? ",
        a(href := "https://okay-effects.github.io/#author", "Get in touch"), "."))
    )

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), page)
