package okay.desk.web

import okay.desk.*
import okay.desk.skill.{Adapted, NativeCompiler, OnQwen, Read, Readers as Skill, Words}
import okay.desk.calc.{Context, Den, Fill, Grammar, Language, Strategy, Term, Ty, Dialogue}
import okay.desk.ui.Model
import okay.desk.know.{FactReader, Knowledge}
import okay.desk.statement.{Reader, Span}
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
  private var factReader: Option[FactReader.Reader] = None
  private var relationReader: Option[FactReader.Reader] = None
  private var statementReader: Option[Reader.Trained] = None
  /** THE LOGIC ABILITY'S ADAPTER (specs/core-joint.md §6): the core trained through the statement reader's heads,
   * beside the unchanged base — on while a task is read, off for data questions and facts */
  private var logicAdapter: Option[Adapted] = None
  /** the reader over the plain base and the learnt gate (specs/core-joint.md §9), when the model's files have them */
  private var plainReader: Option[Reader.Trained] = None
  private var statementGate: Option[okay.desk.statement.Gate] = None
  private var authorQuestion = ""
  private var authorContext = ""
  private var authorHistory = Vector.empty[(String, String)]
  private var authorOutput: Option[NativeCompiler.Output] = None
  private var authorObserved = 0
  var engine: js.Dynamic = null
  /** the native generator: Qwen3 0.6B whole (28 layers, no adapter) on okay-llm's forward — WebGL2 or the CPU
   * where WebLLM has no WebGPU; its tokenizer, `on` its forward */
  private var generator: Option[Tokenizer] = None
  private def generates: Boolean = engine != null || generator.nonEmpty
  var status: String => Unit = _ => ()

  /** The native prefix cache is one mutable resource; independent CPU tools do not acquire this lane. */
  private def reading[X](op: Read[X]): Future[X] =
    val done = traced(op)
    (op match
      case _: Read.States | _: Read.Logits => Execution.model.submit(on.handler(op))
      case _ => chunked(op)).map { x => done(x); x }
  private def chunked[X](op: Read[X]): Future[X] = on.handler(op)
  /** THE LAST READ, KEPT (local storage, written before and after each): a phone that kills the tab mid-forward leaves
   * what it was doing — the op, its sizes, the GPU bytes — for the next visit to say (scripts/phonebench) */
  private var reads = 0
  private def traced(op: Read[?]): Any => Unit =
    reads += 1
    val what = op match
      case Read.Score(context, options) => s"score context ${context.length} options ${options.size} " +
        s"(${options.map(_.length).sum} tokens)"
      case Read.States(ids, at) => s"states ${ids.length} at ${at.size}"
      case Read.Logits(ids) => s"logits ${ids.length}"
      case other => other.productPrefix
    val (gpu, _) = memoryInfo()
    val t0 = js.Date.now()
    def put(s: String): Unit = try dom.window.localStorage.setItem("okay-desk.read", s) catch case _: Exception => ()
    put(s"read $reads: $what, GPU ${gpu / 1048576} MB — begun")
    (x: Any) =>
      // a weighing: its best three (the margin the doubt reads), to compare one device with another
      val top = x match
        case v: Vector[?] if v.headOption.exists(_.isInstanceOf[Double]) =>
          v.asInstanceOf[Vector[Double]].sorted.reverse.take(3).map(d => f"$d%.4f").mkString(" best ", " ", "")
        case _ => ""
      val s = s"read $reads: $what, GPU ${memoryInfo()._1 / 1048576} MB — done in ${(js.Date.now() - t0).toInt} ms$top"
      put(s)
      if js.Dynamic.global.window.okayTraceReads.asInstanceOf[js.UndefOr[Boolean]].contains(true) then
        println("model " + s)

  /** THE DISK'S CACHE (the browser's Cache API: its storage on disk): a file of the model fetched once and kept, so the
   * next visit reads it from the device; a quota refused is no failure — the file is fetched again next time */
  private def caches: js.Dynamic = js.Dynamic.global.caches
  private def cache(name: String): Future[Option[js.Dynamic]] =
    if js.isUndefined(caches) then Future.successful(None)
    else caches.open(name).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map(Some(_)).recover { case _ => None }
  /** A DOWNLOAD THAT COMES BACK: the body read chunk by chunk (each to `chunk`), the try aborted when no byte came for
   * 20 s, and the whole file tried again (`restart` first) up to five times — the iPhone's screen locked mid-file and
   * the page waited for ever for a download iOS had dropped. `onResponse`: the response, before its body is read */
  private def download(address: String, restart: () => Unit, chunk: Uint8Array => Unit,
      onResponse: js.Dynamic => Unit): Future[Unit] =
    def once(): Future[Unit] =
      val ctl = js.Dynamic.newInstance(js.Dynamic.global.AbortController)()
      var last = js.Date.now()
      val stalled = scala.concurrent.Promise[Unit]()
      val watch = js.timers.setInterval(2000) {
        if js.Date.now() - last > 20000 then
          stalled.tryFailure(IllegalStateException(s"$address: no byte for 20 s")); ctl.abort() }
      val read = dom.fetch(address, js.Dynamic.literal(signal = ctl.signal).asInstanceOf[dom.RequestInit]).toFuture
        .flatMap { r =>
          if !r.ok then Future.failed(IllegalStateException(s"$address: HTTP ${r.status}"))
          else
            onResponse(r.asInstanceOf[js.Dynamic])
            val reader = r.body.asInstanceOf[js.Dynamic].getReader()
            def loop(): Future[Unit] = reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { s =>
              last = js.Date.now()
              if s.done.asInstanceOf[Boolean] then Future.unit
              else { chunk(s.value.asInstanceOf[Uint8Array]); loop() } }
            loop() }
      Future.firstCompletedOf(Seq(read, stalled.future)).andThen(_ => js.timers.clearInterval(watch))
    def tries(left: Int): Future[Unit] =
      restart()
      once().recoverWith { case e if left > 1 && !e.getMessage.contains("HTTP 404") =>
        stage(s"downloading again: ${address.takeWhile(_ != '?')}…"); tries(left - 1) }
    tries(5)
  private def fetch(name: String): Future[ArrayBuffer] =
    val address = if name.startsWith("model-understanding/") && !name.contains("?") then
      Execution.versioned(name) else name
    cache("okay-desk-model").flatMap { c =>
      val hit = c.fold(Future.successful(null: js.Dynamic))(cc =>
        cc.`match`(address).asInstanceOf[js.Promise[js.Dynamic]].toFuture)
      hit.flatMap { r =>
        if r != null && !js.isUndefined(r) then r.arrayBuffer().asInstanceOf[js.Promise[ArrayBuffer]].toFuture
        else
          // the chunks gathered (a stalled download tried again, `download`), then one buffer of them
          var parts = js.Array[Uint8Array](); var size = 0
          download(address, () => { parts = js.Array(); size = 0 }, u => { parts.push(u); size += u.length },
            r => c.foreach(cc => cc.put(address, r.applyDynamic("clone")()).asInstanceOf[js.Promise[Unit]].toFuture
              .recover { case _ => () })).map { _ =>
            val out = new Uint8Array(size); var at = 0
            parts.foreach { p => out.set(p, at); at += p.length }
            out.buffer }
      }
    }
  /** A FILE READ INTO A BUFFER THE PAGE ALREADY HAS (`into`, reused file after file): its length. Every weight file as
   * a new 60 MB ArrayBuffer raised a phone's tab by the sum of them all — Safari does not give them back between
   * files (WebKit measured: +440 MB for 340 MB of files). The disk's cache is written beside the reading, not before
   * it (awaited first, the stream held a second whole copy of the file the while) */
  private def fetchInto(name: String, into: Uint8Array): Future[Int] =
    cache("okay-desk-model").flatMap { c =>
      val hit = c.fold(Future.successful(null: js.Dynamic))(cc =>
        cc.`match`(name).asInstanceOf[js.Promise[js.Dynamic]].toFuture)
      hit.flatMap { cached =>
        var at = 0
        def put(u: Uint8Array): Unit =
          require(at + u.length <= into.length, s"$name: larger than its buffer (${into.length} bytes)")
          into.set(u, at); at += u.length
        if cached == null || js.isUndefined(cached) then
          download(name, () => at = 0, put, r => c.foreach(cc => cc.put(name, r.applyDynamic("clone")())
            .asInstanceOf[js.Promise[Unit]].toFuture.recover { case _ => () })).map(_ => at)
        else Future.successful(cached).flatMap { r =>
          val reader = r.body.getReader()
          def loop(at: Int): Future[Int] = reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { s =>
            if s.done.asInstanceOf[Boolean] then Future.successful(at)
            else
              val chunk = s.value.asInstanceOf[Uint8Array]
              require(at + chunk.length <= into.length, s"$name: larger than its buffer (${into.length} bytes)")
              into.set(chunk, at)
              loop(at + chunk.length) }
          loop(0) } } }
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
  /** THE TOKENIZER, read by the browser's own JSON.parse: the file's 11 MB as a tree of Scala values was 340 MB of a
   * phone's tab (WebKit, measured by scripts/phonebench); the parts go to `Tokenizer.of` straight from the JS
   * objects */
  private def tokenizer(b: ArrayBuffer): Tokenizer =
    val j = js.JSON.parse(text(b))
    val model = j.model
    require(model.`type`.asInstanceOf[String] == "BPE", "a BPE model expected")
    val vocab = model.vocab.asInstanceOf[js.Dictionary[Int]]
    val merges = model.merges.asInstanceOf[js.Array[js.Any]]
    val added = j.added_tokens.asInstanceOf[js.Array[js.Dynamic]].toSeq.map(t =>
      (t.content.asInstanceOf[String], t.id.asInstanceOf[Int],
        t.special.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)))
    val nfc = j.normalizer match
      case n if n == null || js.isUndefined(n) => false
      case n if n.`type`.asInstanceOf[String] == "NFC" => true
      case n => sys.error(s"a normalizer not supported: ${js.JSON.stringify(n)}")
    Tokenizer.of(js.Object.keys(vocab.asInstanceOf[js.Object]).iterator.map(k => k -> vocab(k)),
      merges.iterator.map(m => if js.Array.isArray(m) then m.asInstanceOf[js.Array[String]].mkString(" ")
        else m.asInstanceOf[String]), added, nfc)
  /** the weights: the files manifest.json lists (a few layers each, `Tensors.Many` reads them as one — a page never
   * holds the whole of them twice; GitHub Pages caps a file at 100 MB), else the one model.safetensors; and the
   * embedding: in blocks when the manifest has them */
  /** the one buffer the weight files are read into, while a model loads */
  private var shared: Option[Uint8Array] = None
  /** A LINEAR CUT BETWEEN TWO WEIGHT FILES: its parts (copies, the file they came from let go) until all three came */
  private val split = scala.collection.mutable.HashMap.empty[String, scala.collection.mutable.HashMap[String, js.Any]]
  /** ONE WEIGHT FILE ONTO WEBGL2 FROM ITS OWN BYTES: each linear's words sent from a view of the fetched buffer, its
   * BF16 scales and biases made halves in one pass (OnWebGl2.quantizedFrom); the norms copied, the file let go after.
   * A weight on its way was three copies before (Scala array, typed array, padded): ~400 MB of the iPhone's tab */
  private def putFile(k: okay.llm.compute.Preloaded.Kept, pre: okay.llm.compute.Preloaded, gl: OnWebGl2,
      st: Safetensors, b: ArrayBuffer): Unit =
    val at = st.dataStart
    def bytes(e: st.Entry): ArrayBuffer = b.slice(at + e.from, at + e.until)
    def words(e: st.Entry, keep: Boolean): Uint32Array =
      val off = at + e.from
      if off % 4 == 0 && !keep then new Uint32Array(b, off, (e.until - e.from) / 4) else new Uint32Array(bytes(e))
    val byPrefix = st.entries.groupBy((name, _) => name.take(name.lastIndexOf('.')))
    for (prefix, es) <- byPrefix do
      for (name, e) <- es do k.keepShape(name, e.shape)
      val isLinear = es.exists((n, e) => n.endsWith(".weight") && e.dtype == "U32") ||
        es.keys.exists(n => n.endsWith(".scales") || n.endsWith(".biases"))
      if !isLinear then for (name, _) <- es do k.keepSmall(name, st.floats(name))
      else
        val whole = es.size == 3 && split.get(prefix).isEmpty
        val got = if whole then scala.collection.mutable.HashMap.empty[String, js.Any]
          else split.getOrElseUpdate(prefix, scala.collection.mutable.HashMap.empty)
        for (name, e) <- es do
          val part = name.drop(name.lastIndexOf('.') + 1)
          got(part) = if part == "weight" then words(e, keep = !whole) else new Uint16Array(bytes(e))
        if got.size == 3 then
          val shape = k.shape(prefix + ".weight")
          val w = gl.quantizedFrom(shape(0), shape(1) * 8, got("weight").asInstanceOf[Uint32Array],
            got("scales").asInstanceOf[Uint16Array], got("biases").asInstanceOf[Uint16Array])
          k.keepLinear(prefix, pre.putMade(shape(0), shape(1) * 8, w.asInstanceOf[pre.base.W]))
          split.remove(prefix)
  /** `onto`: the device the weights go to a file at a time (`Preloaded`): each file put there as it comes and let go,
   * so a phone's tab never holds the files together (the operator's iPhone, specs/phone.md §4.1) */
  private def weights(dir: String, vocab: Int, onto: Option[okay.llm.compute.Preloaded])
      : Future[(Tensors, Option[okay.llm.qwen.Rows])] =
    fetch(s"$dir/manifest.json").map(b => Option(text(b))).recover { case _ => None }.flatMap {
      case None => fetch(s"$dir/model.safetensors").map(b => (new Safetensors(TypedArrayBuffer.wrap(b)), None))
      case Some(t) =>
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
        val kept = onto.map(_ => okay.llm.compute.Preloaded.Kept())
        val layers = files.zipWithIndex.foldLeft(Future.successful(Vector.empty[Tensors])) { (acc, f) => acc.flatMap { got =>
          stage(s"the weights: file ${f._2 + 1} of ${files.size}…")
          (onto, kept) match
            // ON WEBGL2: the file read into the one buffer of the load and each weight sent from its bytes
            case (Some(device), Some(k)) if device.base.isInstanceOf[OnWebGl2] =>
              val into = shared.getOrElse { val u = new Uint8Array(64 * 1048576); shared = Some(u); u }
              fetchInto(s"$dir/${f._1}", into).map { n =>
                putFile(k, device, device.base.asInstanceOf[OnWebGl2],
                  new Safetensors(TypedArrayBuffer.wrap(into.buffer, 0, n)), into.buffer)
                got }
            case _ => fetch(s"$dir/${f._1}").map { b =>
              val st = new Safetensors(TypedArrayBuffer.wrap(b))
              (onto, kept) match
                case (Some(device), Some(k)) => k.add(device, st); got   // on the device now; the file let go
                case _ => got :+ (st: Tensors) } } }.map(ls => kept.fold(ls) { k =>
              require(k.complete && split.isEmpty, "the weight files left a linear without all its parts")
              shared = None   // the buffer of the load let go
              Vector(k) })
        val embedding: Future[Option[okay.llm.qwen.Rows]] =
          if blocks.isEmpty then Future.successful(None)
          else
            val indexFile = m.get("index") match { case Some(okay.effects.json.Json.Str(s)) => s; case _ => "embed-index.bin" }
            fetch(s"$dir/$indexFile").flatMap { ib =>
              val rows = okay.llm.qwen.Rows.Blocks(vocab, 1024)
              blocksOf = Some((dir, blocks, new Int8Array(ib).toArray, rows))
              wanted.filter(blocks.contains).foldLeft(Future.unit)((acc, b) => acc.flatMap(_ => loadBlock(dir, b))).map(_ => Some(rows))
            }
        for ls <- layers; e <- embedding yield (if ls.size == 1 then ls.head else Tensors.Many(ls), e)
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
  private val pendingQuestionKey = "okay-desk.pending-author-question"
  def recoveredQuestion: Option[String] =
    try
      val saved = Option(dom.window.localStorage.getItem(pendingQuestionKey))
      dom.window.localStorage.removeItem(pendingQuestionKey)
      saved.filter(q => q.nonEmpty && q.length <= 50000)
    catch case _: Exception => None
  private def rememberQuestion(question: String): Unit =
    try
      if question.length <= 50000 then dom.window.localStorage.setItem(pendingQuestionKey, question)
      else dom.window.localStorage.removeItem(pendingQuestionKey)
    catch case _: Exception => ()
  private def forgetQuestion(): Unit =
    try dom.window.localStorage.removeItem(pendingQuestionKey)
    catch case _: Exception => ()
  private var memoryInfo: () => (Long, Long) = () => (0L, 0L)
  // THE GPU BYTES, LIVE: `okayGpu()` — the bytes in textures now and the peak, for a driver (scripts/phonebench)
  dom.window.asInstanceOf[js.Dynamic].updateDynamic("okayGpu")(
    ((() => { val (n, p) = memoryInfo(); js.Array(n.toDouble, p.toDouble) }): js.Function0[js.Array[Double]]))
  private def memoryStage(words: String): Unit =
    val (bytes, peak) = memoryInfo()
    dom.window.asInstanceOf[js.Dynamic].updateDynamic("okayModelProgress")(js.Dynamic.literal(
      stage = words, gpuBytes = bytes.toDouble, peakGpuBytes = peak.toDouble))
  /** Release the previous device before loading another model: a phone holds one set of weights. */
  private def unload(): Future[Unit] =
    authorQuestion = ""; authorContext = ""; authorHistory = Vector.empty; authorOutput = None; authorObserved = 0
    Option(on).foreach(_.close())
    memoryInfo = () => (0L, 0L)
    on = null; skill = null; blocksOf = None; factReader = None; relationReader = None
    statementReader = None; generator = None; logicAdapter = None; plainReader = None; statementGate = None
    val old = engine
    engine = null
    if old == null then Future.unit
    else old.unload().asInstanceOf[js.Promise[Unit]].toFuture

  def loadReaders(requestedDir: String, layer: Int, generate: Boolean = false): Future[Either[String, String]] =
    val dir = requestedDir
    val understanding = dir == "model-understanding"
    // THE FRAME READER: the released model's weights (model-full) and its adapter beside them (model-frames, a small
    // file — a second reader is a second small file, not a second model)
    // ON A PHONE: the one reader merged into the weights (model-frames-merged, FrameMerge) — no adapter file, no planes
    // beside every projection: the iPhone's tab died building and running the base with its adapter beside it
    val merged = phone && dir == framesDir
    val source = if merged then "model-frames-merged" else if dir == framesDir then "model-full" else dir
    (for
      config <- fetch(s"$source/config.json").map(text).map(Qwen3Config.parse)
      tok <- fetch(s"$source/tokenizer.json").map(tokenizer)
      skillBytes <- if understanding || dir == framesDir then Future.successful(new ArrayBuffer(0))
        else fetch(s"$dir/readers.skill")
      adapter <- if understanding && !generate then
        fetch(Execution.versioned(s"$dir/semantic-adapter.safetensors")).map(Some(_))
        else if dir == framesDir && !merged then
          fetch(Execution.versioned(s"$dir/frame.adapter.safetensors")).map(Some(_))
        else Future.successful(None)
      // the second frame reader's adapter (agreement as the doubt, P4), if the page has it — not on a phone: its tab
      // has no room for a second adapter (the operator's iPhone, specs/phone.md §4.1); there one reader, its margin
      second <- if dir == framesDir && !phone then
          fetch(Execution.versioned(s"$dir/frame2.adapter.safetensors")).map(Some(_))
          .recover { case _ => None } else Future.successful(None)
      facts <- if understanding then Future.successful(None) else
        fetch(Execution.versioned(s"$dir/facts.skill")).map(b => FactReader.parse(text(b)).toOption)
          .recover { case _ => None }
      relations <- if understanding then Future.successful(None) else fetch(Execution.versioned(s"$dir/relations-v1.skill"))
        .map(b => okay.desk.know.Relations.parse(text(b)).toOption)
        .recover { case _ => None }
      // the statement reader (specs/formalization.md §12.1): heads over this model, the words they were taught
      stated <- if understanding then Future.successful(None) else
        fetch(Execution.versioned(s"$dir/statement.reader")).map(b => Reader.parse(new Int8Array(b).toArray).toOption)
          .recover { case _ => None }
      logic <- if understanding then Future.successful(None) else
        fetch(Execution.versioned(s"$dir/statement-adapter.safetensors")).map(Some(_)).recover { case _ => None }
      plain <- if understanding then Future.successful(None) else
        fetch(Execution.versioned(s"$dir/statement-plain.reader")).map(b => Reader.parse(new Int8Array(b).toArray).toOption)
          .recover { case _ => None }
      gated <- if understanding then Future.successful(None) else
        fetch(Execution.versioned(s"$dir/statement.gate")).map(b => okay.desk.statement.Gate.parse(text(b)).toOption)
          .recover { case _ => None }
      gpu <- OnWebGpu.create()
      device = gpu.left.flatMap(why => OnWebGl2.create(if phone then 64L * 1048576 else 96L * 1048576,
        headsAtOnce = if phone then 2 else 16, maximum = if phone then 768L * 1048576 else 1536L * 1048576,
        compactCache = phone)   // the prefix cache in halves on a phone, every model: half its memory (the iPhone)
        .left.map(more => s"$why; $more"))
      c = device.fold(_ => OnCpu(), identity)
      // the weights go to the device a file at a time; the model is made from what is already there
      pre = okay.llm.compute.Preloaded(c)
      // the weights last, read straight from the fetched buffer (no copy) onto the device, and not kept: a phone's
      // tab has no room for a second copy of 244 MB
      model <- weights(source, config.vocab, Some(pre)).map { (st, embed) =>
        stage(s"the weights to ${c.api}…")
        try
          adapter match
            case None => logic match
              case None => Qwen3Compute(pre, st, config, headOnCpu = phone && c.api != "WebGPU", embedGiven = embed)
              case Some(bytes) =>
                val adapted = Adapted(pre, st, new Safetensors(TypedArrayBuffer.wrap(bytes)))
                val loaded = Qwen3Compute(adapted, adapted.tensors, config,
                  headOnCpu = phone && c.api != "WebGPU", embedGiven = embed)
                adapted.releaseSources()
                adapted.active = false
                logicAdapter = Some(adapted)
                loaded
            case Some(bytes) =>
              val adapted = Adapted(pre, st, new Safetensors(TypedArrayBuffer.wrap(bytes)),
                second.toVector.map(b => new Safetensors(TypedArrayBuffer.wrap(b))))
              val loaded = Qwen3Compute(adapted, adapted.tensors, config,
                headOnCpu = phone && c.api != "WebGPU", embedGiven = embed)
              adapted.releaseSources()
              framesAdapted = Option.when(second.nonEmpty)(adapted)
              loaded
        catch case error: Exception =>
          c.close()
          throw error }
    yield
      memoryInfo = c match
        case gl: OnWebGl2 => () => (gl.bytes, gl.peakBytes)
        case _ => () => (0L, 0L)
      on = OnQwen(model, tok, if understanding then config.layers else layer, ensure)
      // a weighing in parts a forward (OnQwen.parts; the iPhone): 48 options of a trie are about the rows of 16 packed
      // whole (~110), the size the iPhone finished; fewer parts, fewer passes over the weights
      if phone then on.parts = 48
      on.checkTrie = dom.window.location.search.contains("checktree")
      statementReader = stated.filter(_.heads.d == config.hidden)
      plainReader = plain.filter(_.heads.d == config.hidden)
      statementGate = gated
      factReader = if adapter.nonEmpty then None else
        facts.filter(r => r.parameters.layer == layer && r.parameters.mean.size == config.hidden)
      relationReader = if adapter.nonEmpty then None else
        relations.filter(r => r.parameters.layer == layer && r.parameters.mean.size == config.hidden)
      idle()
      frames = dir == framesDir; paused = None
      skill = if adapter.nonEmpty || generate || frames then null else Skill()
      generator = if generate then Some(tok) else None
      if skill != null then skill.load(new Int8Array(skillBytes).toArray)
      engine = null
      val name = if generate then "Qwen3 0.6B" else if understanding then "Qwen3 0.6B task interpreter"
        else if dir == "model-full" then "Qwen3 0.6B as released"
        else if frames then "the frame reader" else "the student"
      Right(s"$name (${config.layers} layers) and ${if generate then "generation" else if understanding then "model program authoring" else "the readers"}" +
        s" on ${c.api}" +
        (if phone && c.api != "WebGPU" then ", the head on the CPU" else "") + device.fold(why => s" ($why)", _ => "")))
      .recover { case t => Left(s"cannot load ${if understanding then "the task interpreter" else "the readers"}: " +
        t.getMessage) }

  /** a logic task to its statement by the statement reader, the statement to the exact solver: one forward of the
   * task, no token generated; None where this model has no statement reader */
  /** THE CHAT DRAWN BEFORE THE READING: the screen with the task and its step is rendered, but a WebGL2 forward
   * blocks the page until the answer — with the model loaded already (a second task) nothing was drawn between the
   * press and the answer; two frames let the browser draw it first */
  def state(text: String): Future[Option[Reader.Reading]] =
    val p = scala.concurrent.Promise[Unit]()
    dom.window.requestAnimationFrame(_ => dom.window.requestAnimationFrame(_ => p.success(())))
    p.future.flatMap(_ => stateNow(text))
  private def stateNow(text: String): Future[Option[Reader.Reading]] = statementReader match
    case Some(trained) if on != null =>
      stage("reading the task…")
      // the forward of a task said twice keeps every layer's keys and values for reuse — some 100 MB for a long
      // task; nothing reuses them here, and a phone frees a texture late: they are let go before and after
      on.clearCache()
      // the states asked once, nothing kept, a layer's keys and values let go as the next is computed
      val once: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => op match
        case Read.States(ids, at) => Execution.model.submit(on.statesOnce(ids, at)).asInstanceOf[Future[X]]
        case other => reading(other)
      def readWith(r: Reader.Trained, adapted: Boolean): Future[Reader.Reading] =
        logicAdapter.foreach(_.active = adapted)
        Read.future(Reader.see[Async + Read](text, r.norm, r.echo).map(Reader.read(r, _)), once)
      // THE LEARNT GATE (specs/core-joint.md §9): with the plain reader and the gate beside the adapted one, the task
      // is read twice — over the base, then over the adapted core — and the gate decides what is shown
      val decided = (plainReader, statementGate, logicAdapter) match
        case (Some(plain), Some(gate), Some(_)) =>
          for
            b <- readWith(plain, adapted = false)
            a <- readWith(trained, adapted = true)
          yield
            val shown = gate.shows(a, trained.bars, b, plain.bars, Reader.russian(text))
            if shown then a.copy(doubt = None)
            else if a.result.isEmpty then a
            else a.copy(doubt = a.doubt.orElse(Some(Reader.Doubt.Unsure(Span(0, text.length)))))
        case _ => readWith(trained, adapted = true)
      decided
        .map(Option(_)).recover { case e =>
          // a failure on this device is said where the person sees it, not passed by in silence
          status(s"the statement reader failed on this device: ${e.getMessage}")
          dom.console.log(s"statement reader: $e")
          None }.map { r =>
          logicAdapter.foreach(_.active = false)
          on.clearCache()
          memoryStage("after the statement reader")
          if r.nonEmpty then idle()
          r }
    case _ => Future.successful(None)

  /** WebLLM's 0.6B, made by our forward where the browser has no WebGPU (an iPhone: WebGL2) */
  private val nativeGenerated = "Qwen3-0.6B-q4f16_1-MLC"
  private def webGpu: Future[Boolean] =
    val gpu = dom.window.navigator.asInstanceOf[js.Dynamic].gpu
    if js.isUndefined(gpu) || gpu == null then Future.successful(false)
    else gpu.requestAdapter().asInstanceOf[js.Promise[js.Any]].toFuture
      .map(a => a != null && !js.isUndefined(a)).recover { case _ => false }

  /** a model that generates: WebLLM's engine, cached by the browser after the first load */
  def loadWebLlm(id: String): Future[Either[String, String]] =
    val webllm = js.Dynamic.global.window.webllm
    if js.isUndefined(webllm) then Future.successful(Left("WebLLM did not load (no network for the first load?)"))
    else
      val opts = js.Dynamic.literal(initProgressCallback = ((p: js.Dynamic) => status(p.text.asInstanceOf[String])): js.Function1[js.Dynamic, Unit])
      webllm.CreateMLCEngine(id, opts).asInstanceOf[js.Promise[js.Dynamic]].toFuture
        .map { e => engine = e; skill = null; on = null; status(""); Right(s"$id ready (generates)") }
        .recover { case t => Left(s"cannot load $id: ${t.getMessage}") }

  def complete(messages: Vector[(String, String)], max: Int, json: Boolean = false,
      schema: Option[String] = None): Future[String] =
    generator match
      case Some(tok) if engine == null => generate(tok, messages, max)
      case _ => completeWebLlm(messages, max, json, schema)

  /** GREEDY GENERATION on the native forward: Qwen3's chat template (thinking off), a token at a time after the
   * prefix cache (one row a step), until a special token (<|im_end|>) or `max` */
  private def generate(tok: Tokenizer, messages: Vector[(String, String)], max: Int): Future[String] =
    val prompt = messages.map((r, c) => s"<|im_start|>$r\n$c<|im_end|>\n").mkString +
      "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    def step(ids: Array[Int], out: Vector[Int]): Future[Vector[Int]] =
      if out.size >= max then Future.successful(out)
      else reading(Read.Logits(ids)).flatMap { logits =>
        var best = 0
        var i = 1
        while i < logits.length do { if logits(i) > logits(best) then best = i; i += 1 }
        if tok.isSpecial(best) then Future.successful(out)
        else
          stage(s"generating: ${out.size + 1} tokens…")
          step(ids :+ best, out :+ best)
      }
    step(tok.encode(prompt), Vector.empty).map { out => idle(); tok.decode(out) }

  private def completeWebLlm(messages: Vector[(String, String)], max: Int, json: Boolean,
      schema: Option[String]): Future[String] =
    val req = js.Dynamic.literal(
      messages = js.Array(messages.map((r, c) => js.Dynamic.literal(role = r, content = c))*),
      temperature = 0, max_tokens = max, extra_body = js.Dynamic.literal(enable_thinking = false))
    if json || schema.nonEmpty then
      val format = js.Dynamic.literal(`type` = "json_object")
      schema.foreach(s => format.updateDynamic("schema")(s))
      req.updateDynamic("response_format")(format)
      req.updateDynamic("enable_thinking")(false)
    engine.chat.completions.create(req).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .map(r => r.choices.asInstanceOf[js.Array[js.Dynamic]].head.message.content.asInstanceOf[String])

  /** the program read by the readers (no token generated), or written by the model */
  def program(tables: Map[String, Table], question: String, retry: Option[(String, String)]): Future[Either[String, String]] =
    if skill != null then Read.future(if assembler then skill.program[Async + Read](tables, question) else readByCalculus(tables, question, skill.words, Vector(Language.En.language, Language.Ru.language), Vector.empty), [X] => (op: Read[X]) => reading(op))
    else if !generates then Future.successful(Left("load the model first"))
    else
      val sys = Prompt.system(tables)
      val messages = Vector("system" -> sys, "user" -> question) ++ retry.toVector.flatMap((answer, problem) =>
        Vector("assistant" -> answer, "user" -> s"That program failed: $problem. Reply with the corrected JSON only."))
      complete(messages, 600).map(Prompt.program)

  // THE LOADING, FOLLOWED: begun in the background, its words kept as it goes; `Progress` answers a moment later
  private var loading: Option[Future[Either[String, String]]] = None
  private var progressWords = ""
  private def begin(name: String): Unit =
    progressWords = s"loading ${Model.choices.toMap.getOrElse(name, name)}…"
    status = s => { progressWords = s; dom.window.localStorage.setItem("okay-desk.stage", s) }   // (not `stage`: it calls `status`)
    loading = Some(unload().flatMap { _ =>
      Model.nativeModels.get(name).fold(
        if name == nativeGenerated then webGpu.flatMap(gpu =>
          if gpu then loadWebLlm(name) else loadReaders("model-understanding", 28, generate = true))
        else loadWebLlm(name))((dir, layer) => loadReaders(dir, layer))
    }.recover { case t => Left(s"model loading failed: ${t.getMessage}") })
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
    fetch(Execution.versioned(s"words/$name.json")).map(b => Words.parse(text(b)).getOrElse(Words.none))
      .recover { case _ => Words.none })
  // A LANGUAGE WHEN A QUESTION ASKS FOR IT (calculus.md §18): lang/<code>.json beside the page, fetched once
  private val langOf = scala.collection.mutable.Map.empty[String, Future[Option[Language]]]
  private def langFor(code: String): Future[Option[Language]] = Language.builtIn.get(code).fold(langOf.getOrElseUpdate(code,
    fetch(Execution.versioned(s"lang/$code.json")).map(b => Language.parse(text(b)).toOption).recover { case _ => None }))(l => Future.successful(Some(l)))
  /** THE CALCULUS ON THE PAGE (docs/calculus.md): the data first, the readers' skill next, the model's own tokens last —
   * one reading program; an open term is "not sure" with the hole named. `?assembler` in the address keeps the old way */
  private val assembler = dom.window.location.search.contains("assembler")
  /** THE MARGIN (Okay!Chat's second gate, specs/pilot.md §2): two candidates closer than this in log-probability are
   * a question, not a choice — `?margin=<nats>` in the address; the sources that write (the data, the person) give
   * one candidate and are never gated */
  // (ln 3 by default: measured 2026-11-07 — the sample 13/13 13/13 and the GDP 19 unchanged at 0, ln 1.5, ln 2, ln 3;
  // "Show the first 5" a question at ln 3, a whole table at less)
  private val margin: Double = "margin=([0-9.]+)".r.findFirstMatchIn(dom.window.location.search).map(_.group(1).toDouble).getOrElse(math.log(3))
  // THE DIALOGUE (specs/pilot.md §3.2): the hole the last reading stopped at with its options, and the person's answers
  // to this question so far — a choice answers the pending hole; the reading runs again with the answers as its first
  // source and refines the same term; a different question starts afresh
  private var dialogue: (String, Vector[(Ty, String)]) = ("", Vector.empty)
  private var pending: Option[(Ty, Term, Vector[String])] = None
  // THE PERSON'S LESSONS (docs/learning.md, BACKLOG A 1): a choice at a hole is a lesson — the question's words → at
  // this path, this constructor; the lessons are an organ asked FIRST, kept in the browser's storage as an organ file
  private val lessonsKey = "okay-desk:lessons"
  private var lessons: Vector[okay.desk.learn.Lessons.Lesson] =
    Option(dom.window.localStorage.getItem(lessonsKey)).map(okay.desk.learn.Lessons.parse).getOrElse(Vector.empty)
  private var lessonsOrgan: Vector[okay.desk.organ.Organ] = Vector(okay.desk.learn.Lessons.organ(lessons))
  private def keepLessons(): Unit =
    lessonsOrgan = Vector(okay.desk.learn.Lessons.organ(lessons))
    try dom.window.localStorage.setItem(lessonsKey, okay.desk.learn.Lessons.json(lessons)) catch case _: Throwable => ()
  /** «не запоминай» / "forget that": the lessons of this question's words taken back */
  def forget(question: String): Int =
    val before = lessons.size
    lessons = okay.desk.learn.Lessons.forget(lessons, question, Language.detect(question).headOption.flatMap(Language.builtIn.get).getOrElse(Language.En.language))
    // (the dialogue's answers to that question go too: asked afresh next time)
    if dialogue._1 == question then dialogue = ("", Vector.empty)
    keepLessons(); before - lessons.size
  private def answersFor(question: String, choice: Option[String]): Vector[(Ty, String)] =
    val kept = if dialogue._1 == question then dialogue._2 else Vector.empty
    val answers = choice.zip(pending).filter((c, p) => p._3.contains(c) || p._1.isInstanceOf[Ty.ValueT]).map((c, p) => (p._1, c))
      .fold(kept)(a => kept :+ a)
    // the choice remembered as a lesson (a value hole is the data's: not a lesson)
    for c <- choice; (ty, at, _) <- pending if !ty.isInstanceOf[Ty.ValueT] do
      val lang = Language.detect(question).headOption.flatMap(Language.builtIn.get).getOrElse(Language.En.language)
      lessons = (lessons :+ okay.desk.learn.Lessons.of(question, lang, at, c)).distinct
      keepLessons()
    dialogue = (question, answers); pending = None
    answers
  /** THE ASK of an open hole, as the page's words: the question in the person's language, then the options (the
   * constructors, one line, `|` between) — `Clarified.readings` reads them back for the buttons */
  private def asked(ty: Ty, cs: Vector[String], languages: Vector[Language]): String =
    val lang = languages.headOption.getOrElse(Language.En.language)
    // each option with its words ("value=>words"): its button says the words, the choice carries the value
    if cs.isEmpty then Dialogue.question(ty, cs, lang)
    else s"ask: ${Dialogue.question(ty, cs, lang)}\n${cs.map(c => s"$c=>${Dialogue.name(ty, c, lang)}").mkString(" | ")}"
  private def readByCalculus(tables: Map[String, Table], question: String, words: Words, languages: Vector[Language],
      answers: Vector[(Ty, String)]): (Either[String, String] ! (Async + Read)) =
    val s = okay.desk.Space.Schema(tables); val g = new Grammar(s)
    type R[X] = (Async + Read)[X]
    type S[X] = Strategy.Rest[R][X]
    // each hole said as a step before it is weighed (the page shows the steps)
    def noting(f: Fill[S]): Fill[S] = new Fill[S]:
      def apply(ty: Ty, cs: Vector[String], ctx: Context) =
        Read.note[S](s"deciding ${ty.toString.takeWhile(_ != '(')} among ${cs.take(5).mkString(", ")}${if cs.size > 5 then "…" else ""}").flatMap(_ => f(ty, cs, ctx))
    // the person's answers first (the dialogue), the person's lessons, then the data, the readers, the model's tokens
    val fill = noting(Fill.first[S](Dialogue.decided[S](answers), okay.desk.organ.OrganSource.source[S](lessonsOrgan), Fill.exact[S], skill.weigh[S], Fill.model[S](tables)))
    Dialogue.read[R](g, Context(s, question, Term.Hole(Ty.Program), words, languages), fill, margin).map {
      case Dialogue.Outcome.Closed(t) => Den.run(s, t).map((q, _) => Query.print(q))
      case Dialogue.Outcome.Open(ty, at, cs, _) =>
        pending = Some((ty, at, cs))
        Left(asked(ty, cs, languages))
    }
  private var said = Vector.empty[String]
  private var since = 0.0
  private def ask(tables: Map[String, Table], question: String, retry: Option[(String, String)], choice: Option[String]): Unit =
    since = js.Date.now()
    if frames then return askFrames(tables, question, choice)
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
        memoryStage(words)
        said :+= words
        wake()
        (if on.api == "WebGPU" then afterATurn(Future.unit) else drawn()).asInstanceOf[Future[X]]
      // a turn before a forward only: the encodings are many and cheap, and a browser clamps nested zero timeouts to
      // 4 ms — a turn before each made a question 2.6 → 5.5 s
      case op @ (_: Read.States | _: Read.Logits) => afterATurn(reading(op))
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

  /** All interpretation is model-authored here. No baseline, data-reference gate or PuzzleText reader is consulted. */
  private def author(question: String, tables: Map[String, Table], sources: Vector[Knowledge.Source],
      observations: Vector[String]): Unit =
    rememberQuestion(question)
    def afterATurn[X](f: => Future[X]): Future[X] =
      val turn = scala.concurrent.Promise[Unit]()
      js.timers.setTimeout(0)(turn.success(()))
      turn.future.flatMap(_ => f)
    def drawn(): Future[Unit] =
      val p = scala.concurrent.Promise[Unit]()
      dom.window.requestAnimationFrame(_ => dom.window.requestAnimationFrame(_ => p.success(())))
      p.future
    said = Vector("language model understanding the task and its question…")
    since = js.Date.now()
    shown = 0
    val schema = if tables.isEmpty then "" else "\nOpened data tool: output {\"query\": QUERY_JSON}.\n" +
      Prompt.system(tables)
    val context = question + schema + sources.map(s => s.id + ":" + s.text.hashCode).mkString("|")
    val sameTask = authorQuestion == question && observations.nonEmpty && authorOutput.nonEmpty
    val feedback = if sameTask then observations.drop(authorObserved).mkString("\n") else ""
    val continuation = if sameTask && authorContext == context then
      authorOutput.filter(_.sequence.nonEmpty).map(o => o.sequence -> feedback) else None
    if sameTask then authorHistory :+= authorOutput.get.text -> feedback else authorHistory = Vector.empty
    authorQuestion = question
    authorContext = context
    val noting: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => op match
      case Read.Note(words) =>
        memoryStage(words)
        said :+= words
        wake()
        drawn().asInstanceOf[Future[X]]
      case value @ (_: Read.States | _: Read.Logits) => afterATurn(reading(value))
      case other => on.handler(other)
    val proposed =
      if on != null && generator.isEmpty then Read.future(NativeCompiler.compile[Async + Read](question, sources, observations,
        tableInstructions = schema, history = authorHistory, continuation = continuation,
        systemInstructions = Some(okay.desk.skill.PublishedAuthor.system + okay.desk.know.Planning.instructions),
        maxTokens = if phone then 384 else 1024, maxContext = if phone then 1536 else 3072), noting).map { result =>
        authorOutput = result.toOption
        authorObserved = observations.size
        result.map { output =>
          dom.window.asInstanceOf[js.Dynamic].updateDynamic("okayUnderstanding")(js.Dynamic.literal(
            question = question, program = output.text, tokens = output.tokens, api = on.api, layers = on.layers,
            programs = js.Array((authorHistory.map(_._1) :+ output.text)*),
            author = "native-language-model", parserFallback = false))
          output.text
        }
      }
      else if generates then complete(Vector("system" ->
        (okay.desk.know.Formal.instructions + NativeCompiler.actions + okay.desk.know.Planning.instructions + schema),
        "user" -> (question + "\nEvidence:\n" + sources.map(_.text).mkString("\n") +
          "\nTool feedback:\n" + observations.mkString("\n"))) ++
          authorHistory.flatMap((program, reply) => Vector("assistant" -> program, "user" -> reply)),
        1024, json = true).map { text =>
          authorOutput = Some(NativeCompiler.Output(text, 0))
          authorObserved = observations.size
          Right(text)
        }
      else Future.successful(Left("load a language model before interpreting the task"))
    thinking = Some(proposed.flatMap {
      case Left(error) =>
        memoryStage("Stopped: " + error)
        unload().map { _ => forgetQuestion(); Left(error) }
      case right => forgetQuestion(); Future.successful(right)
    }.recoverWith { case t =>
      memoryStage("Stopped: " + t.getMessage)
      unload().recover { case _ => () }.map { _ =>
        forgetQuestion()
        Left("Model stopped: " + t.getMessage + ". No answer was proved. The question is kept in the chat.")
      }
    })
    thinking.foreach(_.onComplete(_ => js.timers.setTimeout(0)(wake())))

  private def document(url: String, body: String, contentType: String): Either[String, Knowledge.Source] =
    if body.length > 1048576 then Left("The source document exceeds the 1 MB text limit.")
    else
      val decoded = contentType match
        case "text/plain" | "text/markdown" => Right(url -> body)
        case "text/html" | "application/xhtml+xml" =>
          val parsed = new dom.DOMParser().parseFromString(body, dom.MIMEType.`text/html`)
          val removed = parsed.querySelectorAll("script,style,noscript,nav,header,footer,form,iframe")
          (0 until removed.length).foreach(i => removed(i).parentNode.removeChild(removed(i)))
          val root = Option(parsed.querySelector("main,article")).getOrElse(parsed.querySelector("body"))
          val blocks = root.querySelectorAll("h1,h2,h3,p,li,pre,blockquote")
          val words = (0 until blocks.length).map(i => blocks(i).textContent.trim).filter(_.nonEmpty).distinct
          val title = Option(parsed.querySelector("title")).map(_.textContent).filter(_.nonEmpty).getOrElse(url)
          Right(title -> (if words.nonEmpty then words.mkString("\n") else root.textContent))
        case _ => Left("This source format is unsupported; use HTML, plain text or Markdown.")
      decoded.flatMap { (title, words) =>
        okay.desk.know.SourceDocument.create(url, title, words, new js.Date().toISOString().take(10))
      }

  def readFacts(quotes: Vector[Knowledge.Quote]): Future[Either[String, Vector[FactReader.Reading]]] =
    factReader match
      case None => Future.successful(Left("No trained fact reader is installed for this model."))
      case Some(_) if quotes.size > 24 => Future.successful(Left("Too many source sentences to read."))
      case Some(reader) =>
        Read.future(Read.each[Knowledge.Quote, Option[FactReader.Reading], Async + Read](quotes) { q =>
          FactReader.features[Async + Read](q.text).map(_.flatMap { f =>
            relationReader match
              case Some(primary) => FactReader.combined(primary, reader, q.source, q.text, f)
              case None => reader.read(q.source, q.text, f)
          })
        }, [X] => (op: Read[X]) => reading(op)).map(rs => Right(rs.flatten))
          .recover { case t => Left("Fact reading failed: " + t.getMessage) }

  /** `Model` answered here, each by its future */
  def model[R[_], Rest[_], A](p: A ! R)(using Split[Model, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[Model, Rest, A, A](a => pure(a))([X] => (op: Model[X], k: X => A ! Rest) =>
      val f: Future[X] = op match
        case Model.ReadFacts(quotes) => readFacts(quotes)
        case Model.Document(url, body, contentType) => Future.successful(document(url, body, contentType))
        case Model.Execute(job) => Execution.cpu.execute(job)
        case Model.ExecutePlan(nodes) => okay.desk.exec.ComputePlan.run(nodes)(Execution.cpu.execute)
        case Model.Begin(name) => begin(name); Future.unit
        case Model.Unload() => unload().map(_ => idle())
        case Model.Progress() => progress()
        case Model.Program(tables, question, retry) =>
          stage("reading a question…")
          program(tables, question, retry).map { r => idle(); r }.recover { case t => idle(); Left(s"the model failed: $t") }
        case Model.Ask(tables, question, retry, choice) => ask(tables, question, retry, choice); Future.unit
        case Model.Thought() => thought()
        case Model.Author(question, tables, sources, observations) =>
          author(question, tables, sources, observations)
          Future.unit
        case Model.Authored() => thought().map(_.map {
          case Left(error) => Left(Model.AuthorFailure.Generation(error))
          case Right(program) =>
            val parsed = Knowledge.authored(program)
              .left.map(error => Model.AuthorFailure.InvalidProgram(program, error))
            parsed.foreach {
              case _: Knowledge.Step.Eval => ()
              case _ => Option(on).foreach(_.clearCache())
            }
            parsed
        })
        case Model.Forget(question) => Future.successful(forget(question))
        case Model.State(text) => state(text)
        case Model.References(tables, question) =>
          for
            words <- Future.sequence(tables.keys.toVector.map(wordsFor))
            languages <- Future.sequence(Language.detect(question).map(langFor))
          yield okay.desk.calc.DataReference.matches(tables, question,
            words.foldLeft(Words.none)(_ ++ _), languages.flatten)
        case Model.Reason(question, sources, observations, learned) =>
          import okay.desk.know.Knowledge
          val direct = Knowledge.baseline(question, sources, learned)
          if !generates || direct.isInstanceOf[Knowledge.Step.Deduce] ||
              direct.isInstanceOf[Knowledge.Step.Calculate] || direct.isInstanceOf[Knowledge.Step.Constraints] ||
              direct.isInstanceOf[Knowledge.Step.Compute] then
            Future.successful(Right(direct))
          else complete(Vector("system" -> Knowledge.prompt(question, sources, observations)), 1600, json = true)
            .map(Prompt.program(_).flatMap(Knowledge.parse))
            .recover { case t => Left(s"reasoning program failed: $t") }
      Async.fromFuture[X, Rest](f).flatMap(k)))

/** THE DISK: `Disk` by the browser's Cache API — every file opened kept under `data/<name>` (its text a response),
 * the names in local storage; the tables are there again at the next visit */

  // ---- THE FRAME READER (specs/frames.md §6): the frame built by choices, a program over `Read` and `Person`
  val framesDir = "model-frames"
  private var frames = false
  /** the frame readers' adapters over the one base, when the page has two (agreement as the doubt) */
  private var framesAdapted: Option[Adapted] = None
  type FrameRow[X] = (Async + Read + Person)[X]
  type AsyncRead[X] = (Async + Read)[X]
  private type Paused = Option[String] => Either[(Vector[String], Vector[String]), FrameReading.Reading] ! AsyncRead
  /** the reading paused at a question, its continuation a value: the person's choice resumes it (never a replay) */
  private var paused: Option[(String, Paused)] = None
  /** the doubt's bar: a line's margin below it asks the person — with two readers 1.0: about one press a question,
   * the wrong answers halved (guarantees.md §9); with ONE reader (a phone: no room for the second adapter) 0.3, its
   * measured bar (frames.md §5.4: en 30/15/9, ru 19/25/10) — 1.0 there asked about nearly every question */
  private def frameBar: Double = "frameBar=([0-9.]+)".r.findFirstMatchIn(dom.window.location.search)
    .map(_.group(1).toDouble).getOrElse(if framesAdapted.isEmpty then 0.3 else 1.0)
  /** THE PERSON ON THE PAGE: asked, the reading stops with the question and keeps `k`; the buttons resume it */
  private def pausing: Handler[Person, AsyncRead, FrameReading.Reading,
      Either[(Vector[String], Vector[String]), FrameReading.Reading]] =
    Handler.general[Person, AsyncRead, FrameReading.Reading, Either[(Vector[String], Vector[String]),
      FrameReading.Reading]](r => pure(Right(r)))(
      [X] => (op: Person[X], k: X => Either[(Vector[String], Vector[String]), FrameReading.Reading] ! AsyncRead) =>
        op match
          case Person.Choose(decided, options) =>
            pause = Some((c: Option[String]) => k(c.asInstanceOf[X]))
            pure(Left((decided, options))))
  private var pause: Option[Paused] = None
  /** a line's question in the person's language */
  private def lineQuestion(line: String, ru: Boolean): String = line.takeWhile(_ != ':') match
    case "table" => if ru then "О какой таблице вопрос?" else "Which table is the question about?"
    case "answer" => if ru then "Что посчитать?" else "What to compute?"
    case "part" => if ru then "Какая часть?" else "Which part?"
    case "from" | "to" => if ru then "Между чем сравнивать?" else "Compare between what?"
    case "where" => if ru then "Какое условие?" else "Which condition?"
    case "group" => if ru then "По чему группировать?" else "Grouped by what?"
    case "keep" => if ru then "Какие ответы оставить?" else "Which answers to keep?"
    case "order" => if ru then "В каком порядке?" else "In what order?"
    case "limit" => if ru then "Сколько строк?" else "How many rows?"
    case _ => if ru then "Что дальше?" else "What next?"
  private def askFrames(tables: Map[String, Table], question: String, choice: Option[String]): Unit =
    since = js.Date.now()
    said = Vector("the frame reader builds the formal model of the question…")
    stage("reading a question…")
    val ru = question.exists(c => c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я')
    val lang = if ru then "ru" else "en"
    println(s"frame links: ${tables.toVector.map((n, t) => s"$n -> ${Links.of(t, question).mkString(", ")}").mkString("; ")}")
    type Out = Either[(Vector[String], Vector[String]), FrameReading.Reading]
    def run(p: Out ! AsyncRead): Future[Out] = Read.future(p, [X] => (op: Read[X]) => {
      val done = traced(op); chunked(op).map { x => done(x); x } })
    // a reader of the two: its adapter in use, the prefix cache of the other let go (its states are another's)
    def by(reader: Int)(p: => Out ! AsyncRead): Future[Out] =
      framesAdapted.foreach { a => if a.current != reader then { a.current = reader; on.clearCache() } }
      run(p)
    def reading(start: Vector[String], doubt: Option[FrameReading.Doubt]): Out ! AsyncRead =
      handle(FrameReading.read[FrameRow](tables, question, doubt, start = start, link = 2.0, guess = 2.0),
        pausing)
    val resumed = (paused, choice) match
      case (Some((q, resume)), Some(_)) if q == question => Some(resume)
      case _ => None
    paused = None; pause = None
    val outcome: Future[Out] = resumed match
      case Some(resume) => by(0)(resume(choice))
      // TWO READERS (P4): each reads alone; the same frame is the answer; where they part, the person chooses the line
      // and the first reads on from it (its own doubt then asks as one reader does)
      case None if framesAdapted.nonEmpty =>
        for
          a <- by(0)(reading(Vector.empty, None))
          b <- by(1)(reading(Vector.empty, None))
          out <- (a, b) match
            case (Right(x), Right(y)) if x.lines != y.lines =>
              val i = x.lines.indices.find(k => y.lines.lift(k) != Some(x.lines(k))).getOrElse(0)
              val prefix = x.lines.take(i)
              pause = Some((c: Option[String]) => c.fold(pure[Out, AsyncRead](Right(x.copy(unresolved = true))))(line =>
                reading(prefix :+ line, Some(FrameReading.Doubt(frameBar)))))
              Future.successful(Left((prefix, Vector(x.lines(i)) ++ y.lines.lift(i).filter(_ != x.lines(i)))))
            case _ => Future.successful(a)
        yield out
      case None => by(0)(reading(Vector.empty, Some(FrameReading.Doubt(frameBar))))
    val f = outcome.map {
      case Left((_, options)) =>
        pause.foreach(k => paused = Some((question, k)))
        // the options as buttons: each line's canonical words (FrameSay), the choice carries the line itself
        Left(s"ask: ${lineQuestion(options.head, ru)}\n" +
          options.map(o => s"$o=>${FrameSay.say(Vector(o), lang)}").mkString(" | "))
      case Right(r) if r.unresolved => Left(if ru then "Не понял вопрос — перефразируйте, пожалуйста."
        else "I did not understand the question — please rephrase it.")
      // outside the language (a cause, a forecast): said plainly, no answer and no search
      case Right(r) if r.lines.contains("answer: cannot") => Left("ask: " + (if ru then
        "Это не вычисляется по данным таблицы (причина, прогноз). Спросите о самих числах — например, суммы по месяцам."
        else "This is not computed from the table's data (a cause, a forecast). Ask about the figures themselves — " +
          "for example, the sums by month."))
      case Right(r) => Frame.query(r.lines, tables).map(Query.print)
    }
    shown = 0
    thinking = Some(f.map { r => idle(); println(s"frame reader: $r"); r }.recover { case t =>
      idle(); t.printStackTrace(); println(s"frame reader failed: $t"); Left(s"the frame reader failed: $t") })
    thinking.foreach(_.onComplete(_ => js.timers.setTimeout(0)(wake())))

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
