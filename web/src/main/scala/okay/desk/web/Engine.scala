package okay.desk.web

import okay.desk.*
import okay.desk.skill.{OnQwen, Read, Readers as Skill}
import okay.desk.ui.Model
import okay.llm.compute.{OnCpu, OnWebGl2, OnWebGpu}
import okay.llm.qwen.{Qwen3Compute, Qwen3Config, Safetensors, Tokenizer}
import okay.scala3.*
import okay.scala3.std.Async
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/**
 * THE MODEL IN THE PAGE: `Model` answered by futures — the readers on the cut model (okay-llm's Qwen3 forward on
 * WebGPU, else WebGL2, else the CPU; the skill file), or a model that generates (WebLLM on WebGPU). The program waits
 * through okay-scala's `Async`; its continuation is a value of the machine.
 */
object Engine:
  var on: OnQwen = null
  var skill: Skill = null
  var engine: js.Dynamic = null
  var status: String => Unit = _ => ()

  private def fetch(name: String): Future[ArrayBuffer] =
    dom.fetch(name).toFuture.flatMap { r =>
      if !r.ok then Future.failed(IllegalStateException(s"$name: HTTP ${r.status}")) else r.arrayBuffer().toFuture
    }
  private def text(ab: ArrayBuffer): String = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(ab).asInstanceOf[String]
  /** the weights: one file, or — where a host caps a file's size (GitHub Pages: 100 MB) — the parts a manifest.json
   * lists, fetched and joined */
  private def weights(dir: String): Future[ArrayBuffer] =
    dom.fetch(s"$dir/manifest.json").toFuture.flatMap { r =>
      if !r.ok then fetch(s"$dir/model.safetensors")
      else r.text().toFuture.flatMap { t =>
        val parts = okay.effects.json.Json.fromText(t) match
          case okay.effects.json.Json.Obj(fs) => fs.collectFirst { case ("parts", okay.effects.json.Json.Arr(xs)) =>
            xs.collect { case okay.effects.json.Json.Str(s) => s } }.getOrElse(Vector.empty)
          case _ => Vector.empty
        parts.zipWithIndex.foldLeft(Future.successful(Vector.empty[ArrayBuffer])) { (acc, p) => acc.flatMap { got =>
          status(s"the weights: part ${p._2 + 1} of ${parts.size}…"); fetch(s"$dir/${p._1}").map(got :+ _) } }
          .map { bufs =>
            val all = new Int8Array(bufs.map(_.byteLength).sum); var at = 0
            for b <- bufs do { all.set(new Int8Array(b), at); at += b.byteLength }
            all.buffer
          }
      }
    }

  /** the readers: the model's files from `dir` (config.json, tokenizer.json, the weights, readers.skill), the device
   * chosen — WebGPU, else WebGL2, else the CPU */
  def loadReaders(dir: String, layer: Int): Future[Either[String, String]] =
    (for
      config <- fetch(s"$dir/config.json").map(text).map(Qwen3Config.parse)
      tok <- fetch(s"$dir/tokenizer.json").map(t => Tokenizer.fromJson(okay.effects.json.Json.fromText(text(t))))
      bytes <- weights(dir)
      skillBytes <- fetch(s"$dir/readers.skill")
      gpu <- OnWebGpu.create()
      device = gpu.left.flatMap(why => OnWebGl2.create().left.map(more => s"$why; $more"))
      c = device.fold(_ => OnCpu(), identity)
      _ = status(s"the weights to ${c.api}…")
      model = Qwen3Compute(c, Safetensors(new Int8Array(bytes).toArray), config)
    yield
      on = OnQwen(model, tok, layer)
      status("")
      skill = Skill(); skill.load(new Int8Array(skillBytes).toArray)
      engine = null
      Right(s"the cut model (${config.layers} layers, ${bytes.byteLength / 1048576} MB) and the readers on ${c.api}" +
        device.fold(why => s" ($why)", _ => "")))
      .recover { case t => Left(s"cannot load the readers: ${t.getMessage}") }

  /** a model that generates: WebLLM's engine, cached by the browser after the first load */
  def loadWebLlm(id: String): Future[Either[String, String]] =
    val webllm = js.Dynamic.global.window.webllm
    if js.isUndefined(webllm) then Future.successful(Left("WebLLM did not load (no network for the first load?)"))
    else
      val opts = js.Dynamic.literal(initProgressCallback = ((p: js.Dynamic) => status(p.text.asInstanceOf[String])): js.Function1[js.Dynamic, Unit])
      webllm.CreateMLCEngine(id, opts).asInstanceOf[js.Promise[js.Dynamic]].toFuture
        .map { e => engine = e; skill = null; on = null; status(""); Right(s"$id ready (generates)") }
        .recover { case t => Left(s"cannot load $id: ${t.getMessage}") }

  def complete(messages: Vector[(String, String)], max: Int): Future[String] =
    val req = js.Dynamic.literal(
      messages = js.Array(messages.map((r, c) => js.Dynamic.literal(role = r, content = c))*),
      temperature = 0, max_tokens = max, extra_body = js.Dynamic.literal(enable_thinking = false))
    engine.chat.completions.create(req).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .map(r => r.choices.asInstanceOf[js.Array[js.Dynamic]].head.message.content.asInstanceOf[String])

  /** the program read by the readers (no token generated), or written by the model */
  def program(tables: Map[String, Table], question: String, retry: Option[(String, String)]): Future[Either[String, String]] =
    if skill != null then Read.future(skill.program[Async + Read](tables, question), on.handler)
    else if engine == null then Future.successful(Left("load the model first"))
    else
      val sys = Prompt.system(tables)
      val messages = Vector("system" -> sys, "user" -> question) ++ retry.toVector.flatMap((answer, problem) =>
        Vector("assistant" -> answer, "user" -> s"That program failed: $problem. Reply with the corrected JSON only."))
      complete(messages, 600).map(Prompt.program)

  def summary(question: String, q: Query, result: Table): Future[Option[String]] =
    if engine == null then Future.successful(None)
    else complete(Vector("user" -> Prompt.summarize(question, q, result)), 200)
      .map(s => Some(s.replaceAll("(?s)<think>.*?</think>", "").trim))

  /** `Model` answered here, each by its future */
  def model[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Model, Rest, A, A](a => pure(a))([X] => (op: Model[X], k: X => A ! Rest) =>
      val f: Future[X] = op match
        case Model.Load(name) => if name == "readers" then loadReaders("model", 19) else loadWebLlm(name)
        case Model.Program(tables, question, retry) => program(tables, question, retry)
        case Model.Summary(question, q, result) => summary(question, q, result)
      Async.fromFuture[X, Rest](f).flatMap(k)))
