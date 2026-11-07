package okay.desk.web

import okay.desk.*
import okay.desk.skill.{OnQwen, Read, Readers as Skill, Words}
import okay.desk.calc.{Context, Den, Fill, Grammar, Language, Strategy, Term, Ty, Dialogue}
import okay.desk.ui.Model
import okay.llm.compute.{OnCpu, OnWebGl2, OnWebGpu}
import okay.llm.qwen.{Qwen3Compute, Qwen3Config, Safetensors, Tensors, Tokenizer}
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

  /** THE DISK'S CACHE (the browser's Cache API: its storage on disk): a file of the model fetched once and kept, so the
   * next visit reads it from the device; a quota refused is no failure — the file is fetched again next time */
  private def caches: js.Dynamic = js.Dynamic.global.caches
  private def cache(name: String): Future[Option[js.Dynamic]] =
    if js.isUndefined(caches) then Future.successful(None)
    else caches.open(name).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map(Some(_)).recover { case _ => None }
  private def fetch(name: String): Future[ArrayBuffer] =
    cache("okay-desk-model").flatMap { c =>
      val hit = c.fold(Future.successful(null: js.Dynamic))(cc => cc.`match`(name).asInstanceOf[js.Promise[js.Dynamic]].toFuture)
      hit.flatMap { r =>
        if r != null && !js.isUndefined(r) then r.arrayBuffer().asInstanceOf[js.Promise[ArrayBuffer]].toFuture
        else dom.fetch(name).toFuture.flatMap { r =>
          if !r.ok then Future.failed(IllegalStateException(s"$name: HTTP ${r.status}"))
          else
            val kept = c.fold(Future.unit)(cc => cc.put(name, r.clone()).asInstanceOf[js.Promise[Unit]].toFuture.recover { case _ => () })
            kept.flatMap(_ => r.arrayBuffer().toFuture)
        }
      }
    }
  private def text(ab: ArrayBuffer): String = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(ab).asInstanceOf[String]
  /** THE EMBEDDING IN BLOCKS BY LANGUAGE (okay-llm `Vocab`): the manifest names a file a block and an index (a byte a
   * token: its block); the page loads the blocks of its languages — common, latin-en, cyrillic — and fetches another
   * when a token of it appears (`ensure`, before a forward; the head made again). Where the manifest has no
   * blocks, the embedding is in the weights' files as before */
  private var blocksOf: Option[(String, Map[String, String], Array[Byte], okay.llm.qwen.Rows.Blocks)] = None
  private val wanted = Vector("common", "latin-en", "cyrillic")
  private def loadBlock(dir: String, name: String): Future[Unit] = blocksOf match
    case Some((_, files, _, rows)) if files.contains(name) =>
      stage(s"the embedding: the $name block…")
      fetch(s"$dir/${files(name)}").map { b =>
        val (ids, q) = okay.llm.qwen.Vocab.readBlock(new Safetensors(TypedArrayBuffer.wrap(b))); rows.add(ids, q) }
    case _ => Future.unit
  /** the blocks of every token of `ids` loaded (fetched if not yet), the head made again when any was */
  def ensure(ids: Array[Int]): Future[Unit] = blocksOf match
    case Some((dir, _, index, rows)) =>
      val missing = ids.filter(id => id < index.length && !rows.has(id)).map(id => okay.llm.qwen.Vocab.blocks(index(id).toInt)).distinct
      if missing.isEmpty then Future.unit
      else missing.foldLeft(Future.unit)((acc, b) => acc.flatMap(_ => loadBlock(dir, b))).map { _ =>
        Option(on).foreach(_.rehead()); idle() }
    case None => Future.unit
  /** the weights: the files manifest.json lists (a few layers each, `Tensors.Many` reads them as one — a page never
   * holds the whole of them twice; GitHub Pages caps a file at 100 MB), else the one model.safetensors; and the
   * embedding: in blocks when the manifest has them */
  private def weights(dir: String, vocab: Int): Future[(Tensors, Option[okay.llm.qwen.Rows])] =
    dom.fetch(s"$dir/manifest.json").toFuture.flatMap { r =>
      if !r.ok then fetch(s"$dir/model.safetensors").map(b => (new Safetensors(TypedArrayBuffer.wrap(b)), None))
      else r.text().toFuture.flatMap { t =>
        val m = okay.effects.json.Json.fromText(t) match
          case okay.effects.json.Json.Obj(fs) => fs.toMap
          case _ => Map.empty[String, okay.effects.json.Json]
        def strings(k: String) = m.get(k) match
          case Some(okay.effects.json.Json.Arr(xs)) => xs.collect { case okay.effects.json.Json.Str(s) => s }
          case _ => Vector.empty
        val files = strings("files")
        val blocks = m.get("embedding") match
          case Some(okay.effects.json.Json.Obj(fs)) => fs.collect { case (k, okay.effects.json.Json.Str(v)) => k -> v }.toMap
          case _ => Map.empty[String, String]
        val layers = files.zipWithIndex.foldLeft(Future.successful(Vector.empty[Tensors])) { (acc, f) => acc.flatMap { got =>
          stage(s"the weights: file ${f._2 + 1} of ${files.size}…")
          fetch(s"$dir/${f._1}").map(b => got :+ (new Safetensors(TypedArrayBuffer.wrap(b)): Tensors)) } }
        val embedding: Future[Option[okay.llm.qwen.Rows]] =
          if blocks.isEmpty then Future.successful(None)
          else
            val indexFile = m.get("index") match { case Some(okay.effects.json.Json.Str(s)) => s; case _ => "embed-index.bin" }
            fetch(s"$dir/$indexFile").flatMap { ib =>
              val rows = okay.llm.qwen.Rows.Blocks(vocab, 1024)
              blocksOf = Some((dir, blocks, new Int8Array(ib).toArray, rows))
              wanted.filter(blocks.contains).foldLeft(Future.unit)((acc, b) => acc.flatMap(_ => loadBlock(dir, b))).map(_ => Some(rows))
            }
        for ls <- layers; e <- embedding yield (Tensors.Many(ls), e)
      }
    }
  /** THE STAGE, KEPT (local storage): a phone that kills the tab reloads it with no word — the next visit says where
   * the last one ended */
  def stage(s: String): Unit = { status(s); dom.window.localStorage.setItem("okay-desk.stage", s) }
  def lastStage: Option[String] = Option(dom.window.localStorage.getItem("okay-desk.stage")).filter(_.nonEmpty)
  def idle(): Unit = { status(""); dom.window.localStorage.setItem("okay-desk.stage", "") }

  /** the readers: the model's files from `dir` (config.json, tokenizer.json, the weights, readers.skill), the device
   * chosen — WebGPU, else WebGL2, else the CPU */
  /** `phone`: a small screen — WebGL2 keeps a small pool of textures and the head stays on the CPU (the embedding's
   * 78 MB not sent: a phone's browser takes the GPU's memory back at a few hundred MB) */
  var phone = false
  def loadReaders(dir: String, layer: Int): Future[Either[String, String]] =
    (for
      config <- fetch(s"$dir/config.json").map(text).map(Qwen3Config.parse)
      tok <- fetch(s"$dir/tokenizer.json").map(t => Tokenizer.fromJson(okay.effects.json.Json.fromText(text(t))))
      skillBytes <- fetch(s"$dir/readers.skill")
      gpu <- OnWebGpu.create()
      device = gpu.left.flatMap(why => OnWebGl2.create(if phone then 24L * 1048576 else 96L * 1048576, headsAtOnce = if phone then 2 else 16).left.map(more => s"$why; $more"))
      c = device.fold(_ => OnCpu(), identity)
      // the weights last, read straight from the fetched buffer (no copy) onto the device, and not kept: a phone's
      // tab has no room for a second copy of 244 MB
      model <- weights(dir, config.vocab).map { (st, embed) =>
        stage(s"the weights to ${c.api}…")
        Qwen3Compute(c, st, config, headOnCpu = phone && c.api != "WebGPU", embedGiven = embed) }
    yield
      on = OnQwen(model, tok, layer, ensure)
      idle()
      skill = Skill(); skill.load(new Int8Array(skillBytes).toArray)
      engine = null
      Right(s"${if dir == "model" then "the cut model" else "the student"} (${config.layers} layers) and the readers on ${c.api}" +
        (if phone && c.api != "WebGPU" then ", the head on the CPU" else "") + device.fold(why => s" ($why)", _ => "")))
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
    if skill != null then Read.future(if assembler then skill.program[Async + Read](tables, question) else readByCalculus(tables, question, skill.words, Vector(Language.En.language, Language.Ru.language), Vector.empty), on.handler)
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

  // THE LOADING, FOLLOWED: begun in the background, its words kept as it goes; `Progress` answers a moment later
  private var loading: Option[Future[Either[String, String]]] = None
  private var progressWords = ""
  private def begin(name: String): Unit =
    progressWords = s"loading ${Model.choices.toMap.getOrElse(name, name)}…"
    status = s => { progressWords = s; dom.window.localStorage.setItem("okay-desk.stage", s) }   // (not `stage`: it calls `status`)
    loading = Some(Model.readers.get(name).fold(loadWebLlm(name))((dir, layer) => loadReaders(dir, layer)))
  private def progress(): Future[Either[String, Either[String, String]]] =
    val p = scala.concurrent.Promise[Either[String, Either[String, String]]]()
    js.timers.setTimeout(250) {
      loading match
        case Some(f) if f.isCompleted => loading = None; p.success(Right(f.value.get.toOption.getOrElse(Left("the loading failed"))))
        case Some(_) => p.success(Left(progressWords))
        case None => p.success(Right(Left("no loading begun")))
    }
    p.future

  // A QUESTION FOLLOWED: begun in the background, its steps kept as the readers say them (`Read.Note`); `Thought`
  // answers a moment later with the steps so far — the last with its seconds — or the program
  private var thinking: Option[Future[Either[String, String]]] = None
  // THE DATA'S OWN WORDS (specs/data.md §29): words/<table>.json beside the page when the data has them (the examples'),
  // fetched once a table; none else
  private val wordsOf = scala.collection.mutable.Map.empty[String, Future[Words]]
  private def wordsFor(name: String): Future[Words] = wordsOf.getOrElseUpdate(name,
    dom.fetch(s"words/$name.json").toFuture.flatMap { r =>
      if !r.ok then Future.successful(Words.none) else r.text().toFuture.map(t => Words.parse(t).getOrElse(Words.none))
    }.recover { case _ => Words.none })
  // A LANGUAGE WHEN A QUESTION ASKS FOR IT (calculus.md §18): lang/<code>.json beside the page, fetched once
  private val langOf = scala.collection.mutable.Map.empty[String, Future[Option[Language]]]
  private def langFor(code: String): Future[Option[Language]] = Language.builtIn.get(code).fold(langOf.getOrElseUpdate(code,
    dom.fetch(s"lang/$code.json").toFuture.flatMap(r => if !r.ok then Future.successful(None) else r.text().toFuture.map(t => Language.parse(t).toOption)).recover { case _ => None }))(l => Future.successful(Some(l)))
  /** THE CALCULUS ON THE PAGE (docs/calculus.md): the data first, the readers' skill next, the model's own tokens last —
   * one reading program; an open term is "not sure" with the hole named. `?assembler` in the address keeps the old way */
  private val assembler = dom.window.location.search.contains("assembler")
  /** THE MARGIN (Okay!Chat's second gate, specs/pilot.md §2): two candidates closer than this in log-probability are
   * a question, not a choice — `?margin=<nats>` in the address; the sources that write (the data, the person) give
   * one candidate and are never gated */
  private val margin: Double = "margin=([0-9.]+)".r.findFirstMatchIn(dom.window.location.search).map(_.group(1).toDouble).getOrElse(0.0)
  // THE DIALOGUE (specs/pilot.md §3.2): the hole the last reading stopped at with its options, and the person's answers
  // to this question so far — a choice answers the pending hole; the reading runs again with the answers as its first
  // source and refines the same term; a different question starts afresh
  private var dialogue: (String, Vector[(Ty, String)]) = ("", Vector.empty)
  private var pending: Option[(Ty, Vector[String])] = None
  private def answersFor(question: String, choice: Option[String]): Vector[(Ty, String)] =
    val kept = if dialogue._1 == question then dialogue._2 else Vector.empty
    val answers = choice.zip(pending).filter((c, p) => p._2.contains(c) || p._1.isInstanceOf[Ty.ValueT]).map((c, p) => (p._1, c))
      .fold(kept)(a => kept :+ a)
    dialogue = (question, answers); pending = None
    answers
  /** THE ASK of an open hole, as the page's words: the question in the person's language, then the options (the
   * constructors, one line, `|` between) — `Clarified.readings` reads them back for the buttons */
  private def asked(ty: Ty, cs: Vector[String], languages: Vector[Language]): String =
    val lang = languages.headOption.getOrElse(Language.En.language)
    s"ask: ${Dialogue.question(ty, cs, lang)}\n${cs.mkString(" | ")}"
  private def readByCalculus(tables: Map[String, Table], question: String, words: Words, languages: Vector[Language],
      answers: Vector[(Ty, String)]): (Either[String, String] ! (Async + Read)) =
    val s = okay.desk.Space.Schema(tables); val g = new Grammar(s)
    type R[X] = (Async + Read)[X]
    type S[X] = Strategy.Rest[R][X]
    // each hole said as a step before it is weighed (the page shows the steps)
    def noting(f: Fill[S]): Fill[S] = new Fill[S]:
      def apply(ty: Ty, cs: Vector[String], ctx: Context) =
        Read.note[S](s"deciding ${ty.toString.takeWhile(_ != '(')} among ${cs.take(5).mkString(", ")}${if cs.size > 5 then "…" else ""}").flatMap(_ => f(ty, cs, ctx))
    // the person's answers first (the dialogue), then the data, the readers, the model's tokens
    val fill = noting(Fill.first[S](Dialogue.decided[S](answers), Fill.exact[S], skill.weigh[S], Fill.model[S](tables)))
    Dialogue.read[R](g, Context(s, question, Term.Hole(Ty.Program), words, languages), fill, margin).map {
      case Dialogue.Outcome.Closed(t) => Den.run(s, t).map((q, _) => Query.print(q))
      case Dialogue.Outcome.Open(ty, _, cs, _) =>
        pending = Some((ty, cs))
        Left(asked(ty, cs, languages))
    }
  private var said = Vector.empty[String]
  private var since = 0.0
  private def ask(tables: Map[String, Table], question: String, retry: Option[(String, String)], choice: Option[String]): Unit =
    since = js.Date.now()
    val answers = answersFor(question, choice)
    said = Vector(
      if skill != null then "the question goes to the model in the page…"
      else if retry.isDefined then s"the model is fixing the program (${retry.get._2})…"
      else "the model is writing the program…")
    stage("reading a question…")
    // each op after a turn of the browser's event loop: WebGL2 reads its results back synchronously, and a question's
    // forwards run one after another — without a turn between them the page is not drawn and no timer fires until
    // the answer (the steps never seen); a turn costs a few ms of a forward's hundreds
    def afterATurn[X](f: => Future[X]): Future[X] =
      val turn = scala.concurrent.Promise[Unit]()
      js.timers.setTimeout(0)(turn.success(()))
      turn.future.flatMap(_ => f)
    // a step said: shown at once (the waiting `Thought` answered now, not at its next tick); where a forward blocks the
    // page (WebGL2, the CPU) the next op waits for the page to be drawn — two frames — or the step would show only
    // after the forward it announces
    def drawn(): Future[Unit] =
      val p = scala.concurrent.Promise[Unit]()
      dom.window.requestAnimationFrame(_ => dom.window.requestAnimationFrame(_ => p.success(())))
      p.future
    val noting: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => op match
      case Read.Note(words) =>
        said :+= words
        wake()
        (if on.api == "WebGPU" then afterATurn(Future.unit) else drawn()).asInstanceOf[Future[X]]
      // a turn before a forward only: the encodings are many and cheap, and a browser clamps nested zero timeouts to
      // 4 ms — a turn before each made a question 2.6 → 5.5 s
      case op @ (_: Read.States | _: Read.Logits) => afterATurn(on.handler(op))
      case other => on.handler(other)
    val f =
      if skill != null then Future.sequence(tables.keys.toVector.map(wordsFor)).flatMap { ws =>
        skill.words = ws.foldLeft(Words.none)(_ ++ _)
        if assembler then Read.future(skill.program[Async + Read](tables, question), noting)
        else Future.sequence(Language.detect(question).map(langFor)).flatMap(ls => Read.future(readByCalculus(tables, question, skill.words, ls.flatten, answers), noting))
      }
      else program(tables, question, retry)
    shown = 0
    thinking = Some(f.map { r => idle(); r }.recover { case t => idle(); Left(s"the model failed: $t") })
    // the answer shown as soon as it is there
    thinking.foreach(_.onComplete(_ => js.timers.setTimeout(0)(wake())))
  // the `Thought` waiting for its tick, and how many steps the page has seen
  private var waiting: Option[scala.concurrent.Promise[Either[Vector[String], Either[String, String]]]] = None
  private var shown = 0
  private def now(): Either[Vector[String], Either[String, String]] = thinking match
    case Some(f) if f.isCompleted => thinking = None; Right(f.value.get.toOption.getOrElse(Left("the question failed")))
    case Some(_) =>
      shown = said.size
      val secs = (js.Date.now() - since) / 1000
      Left(said.init :+ f"${said.last} — ${secs}%.1f s")
    case None => Right(Left("no question asked"))
  /** a new step: the waiting `Thought` answered now */
  private def wake(): Unit = waiting.foreach { p => waiting = None; p.trySuccess(now()) }
  private def thought(): Future[Either[Vector[String], Either[String, String]]] =
    val p = scala.concurrent.Promise[Either[Vector[String], Either[String, String]]]()
    if said.size > shown then p.success(now())
    else
      waiting = Some(p)
      js.timers.setTimeout(250)(if waiting.contains(p) then { waiting = None; p.trySuccess(now()) })
    p.future

  /** `Model` answered here, each by its future */
  def model[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Model, Rest, A, A](a => pure(a))([X] => (op: Model[X], k: X => A ! Rest) =>
      val f: Future[X] = op match
        case Model.Begin(name) => begin(name); Future.unit
        case Model.Progress() => progress()
        case Model.Program(tables, question, retry) =>
          stage("reading a question…")
          program(tables, question, retry).map { r => idle(); r }.recover { case t => idle(); Left(s"the model failed: $t") }
        case Model.Ask(tables, question, retry, choice) => ask(tables, question, retry, choice); Future.unit
        case Model.Thought() => thought()
        case Model.Summary(question, q, result) => summary(question, q, result).recover { case _ => None }
      Async.fromFuture[X, Rest](f).flatMap(k)))

/** THE DISK: `Disk` by the browser's Cache API — every file opened kept under `data/<name>` (its text a response),
 * the names in local storage; the tables are there again at the next visit */
object OnDisk:
  import okay.desk.ui.Disk
  private def caches: js.Dynamic = js.Dynamic.global.caches
  private val index = "okay-desk.files"
  private def names: List[String] = Option(dom.window.localStorage.getItem(index)).filter(_.nonEmpty).map(_.split("\n").toList).getOrElse(Nil)
  private def setNames(ns: List[String]): Unit = dom.window.localStorage.setItem(index, ns.mkString("\n"))
  private def withCache[A](f: js.Dynamic => Future[A], orElse: => A): Future[A] =
    if js.isUndefined(caches) then Future.successful(orElse)
    else caches.open("okay-desk-data").asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap(f).recover { case _ => orElse }
  def answer[X](op: Disk[X]): Future[X] = op match
    case Disk.Put(name, text) =>
      withCache(c => c.put(s"data/$name", js.Dynamic.newInstance(js.Dynamic.global.Response)(text)).asInstanceOf[js.Promise[Unit]].toFuture
        .map(_ => setNames((names :+ name).distinct)), ())
    case Disk.Get(name) =>
      withCache(c => c.`match`(s"data/$name").asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { r =>
        if r == null || js.isUndefined(r) then Future.successful(None) else r.text().asInstanceOf[js.Promise[String]].toFuture.map(Some(_)) }, None)
    case Disk.Names() => Future.successful(names)
    case Disk.Remove(name) =>
      setNames(names.filterNot(_ == name))
      withCache(c => c.delete(s"data/$name").asInstanceOf[js.Promise[Boolean]].toFuture.map(_ => ()), ())
  def disk[R[_], Rest[_], A](p: A ! R)(using Split[Disk, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Disk, Rest, A, A](a => pure(a))([X] => (op: Disk[X], k: X => A ! Rest) =>
      Async.fromFuture[X, Rest](answer(op)).flatMap(k)))
