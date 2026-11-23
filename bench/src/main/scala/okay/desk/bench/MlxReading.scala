package okay.desk.bench

import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Model, Qwen3, Qwen35}
import okay.desk.skill.{Pairs, Read, Readers, Reading}

/** THE JVM'S READING: mlx-scala's Qwen3 or Qwen3.5/3.6 (the teacher, 35B) on Apple's GPU — the residual after `layer`
 * by the hook, the logits by the model, the tokenizer and the chat template of the model's directory */
final class MlxReading(chat: Chat, model: Model, layer: Int) extends Reading:
  private val tok = chat.tokenizer
  private def hook(h: Qwen3.Hook | Null): Unit = model match
    case m: Qwen3 => m.hook = h
    case m: Qwen35 => m.hook = h
    case _ => sys.error("the readers need a model with a hook of its layers (Qwen3, Qwen3.5/3.6)")
  /** the residual after each of `layers` at the positions, in one forward: layer → position → vector */
  def statesAt(ids: Array[Int], layers: Seq[Int], at: Vector[Int]): Map[Int, Vector[Array[Float]]] =
    val got = scala.collection.mutable.Map.empty[Int, Vector[Array[Float]]]
    hook(new Qwen3.Hook:
      def after(i: Int, h: Arr)(using Scope): Arr =
        if layers.contains(i + 1) then
          val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats.map(v => if v.isNaN || v.isInfinite then 0f else v)
          got(i + 1) = at.map(p => all.slice(p * d, (p + 1) * d))
        h)
    val st = model.newState(); val s = new Scope
    try { model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval(); got.toMap }
    finally { s.close(); st.close(); hook(null) }
  def states(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]] = statesAt(ids, Seq(layer), at)(layer)
  def logits(ids: Array[Int]): Array[Float] =
    val st = model.newState(); val s = new Scope
    try astype(model.logits(ints(ids, 1, ids.length)(using s), st)(using s), Dtype.Float32)(using s).toFloats
    finally { s.close(); st.close() }
  def encode(text: String): Array[Int] = tok.encode(text)
  def decode(ids: Seq[Int]): String = tok.decode(ids)
  def prompt(system: String, user: String): Array[Int] =
    def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
      "content" -> okay.effects.json.Json.Str(content))
    chat.prompt(Vector(msg("system", system), msg("user", user)), Nil, Map("enable_thinking" -> false))
  // the state fed with a context but its last token, kept: the next weighing of a longer context feeds the rest only
  private var kept: Option[(Array[Int], Model.State)] = None
  /** the options weighed after the context: from a checkpoint of the context's state, each option fed after the
   * context's last token, the probabilities of its tokens read, the state put back */
  def score(context: Array[Int], options: Vector[Array[Int]]): Vector[Double] =
    val (head, last) = (context.init, context.last)
    val s = new Scope
    try
      def feed(st: Model.State, ids: Array[Int]): Unit =
        if ids.nonEmpty then model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval()
      val st = kept match
        case Some((ids, st)) if head.startsWith(ids) => feed(st, head.drop(ids.length)); st
        case other =>
          other.foreach(_._2.close())
          val st = model.newState(); feed(st, head); st
      kept = Some(head -> st)
      options.map { o =>
        val at = st.checkpoint()
        val input = last +: o.init
        val lp = Scope(s) {
          val logits = astype(model.logitsAll(ints(input, 1, input.length), st), Dtype.Float32)
          val v = logits.dim(-1)
          takeAlong(softmax(reshape(logits, input.length, v), 1), ints(o, input.length, 1), 1).toFloats.take(o.length)
        }
        st.rewind(at, 0): Unit
        at.close()
        lp.map(p => math.log(math.max(p, 1e-30f).toDouble)).sum / o.length
      }
    finally s.close()
  /** THE OPTIONS IN ONE PASS (the default; `FRAME_EACH_OPTION` the old way): the context and every option one trie
   * (mlx-scala `Model.hiddenTree`: each token attends to its ancestors), the logits of the rows that predict the
   * options' tokens — `score`'s numbers but for bf16 rounding (one forward an option, the state rewound), one
   * forward a line: a `where` line has up to 170 options */
  def scoreTree(context: Array[Int], options: Vector[Array[Int]]): Vector[Double] =
    val ids = scala.collection.mutable.ArrayBuffer.empty[Int]
    val parent = scala.collection.mutable.ArrayBuffer.empty[Int]
    val child = scala.collection.mutable.HashMap.empty[(Int, Int), Int]
    def walk(from: Int, tokens: Array[Int]): Array[Int] =
      var at = from
      tokens.map { t => at = child.getOrElseUpdate((at, t), { ids += t; parent += at; ids.size - 1 }); at }
    val ctx = walk(-1, context)
    val rows = scala.collection.mutable.ArrayBuffer.empty[Int]
    val spans = options.map { o =>
      val nodes = walk(ctx.last, o)
      val start = rows.size
      rows ++= (ctx.last +: nodes.init)
      (start, rows.size)
    }
    val s = new Scope
    given Scope = s
    try
      val h = model.hiddenTree(ids.toArray, parent.toArray)
      val m = rows.size
      val at = take(reshape(h, ids.size, h.dim(2)), ints(rows.toArray, m), 0)
      val logits = astype(model.head(reshape(at, 1, m, h.dim(2))), Dtype.Float32)
      val pred = reshape(logits, m, logits.dim(-1))
      val targets = options.flatten.toArray
      val lp = subtract(reshape(takeAlong(pred, ints(targets, m, 1), 1), m), logsumexp(pred, 1)).toFloats
      spans.map((a, b) => (a until b).map(i => math.max(lp(i).toDouble, math.log(1e-30))).sum / (b - a))
    finally s.close()
  // the default since 2026-11-24 (Everyday 1.3 → 0.4 s a question; 1 of 108 outcomes moved, margins within 0.11):
  // FRAME_EACH_OPTION=1 weighs one option a forward as before
  private val treeRead = !sys.env.contains("FRAME_EACH_OPTION")

  /** the operation answered: `Read`'s one list of what a skill needs, this backend's way */
  def answer[X](op: Read[X]): X = op match
    case Read.States(ids, at) => states(ids, at)
    case Read.Logits(ids) => logits(ids)
    case Read.Encode(text) => encode(text)
    case Read.Decode(ids) => decode(ids)
    case Read.Prompt(system, user) => prompt(system, user)
    case Read.Note(_) => ()
    case Read.Score(context, options) => if treeRead then scoreTree(context, options) else score(context, options)

object MlxReading:
  /** the readers for a model and a layer: loaded from the cache when there (DESK_RETRAIN=1 trains again) */
  def ready(chat: Chat, model: Model, layer: Int, repo: String, pairs: => Vector[Pairs.Pair]): Readers.On =
    val r = Readers.On(Readers(), MlxReading(chat, model, layer))
    val path = java.nio.file.Path.of(sys.props("user.home"), ".cache", "okay-desk", s"readers-${repo.split('/').last}-$layer.skill")
    if java.nio.file.Files.exists(path) && !scala.sys.env.contains("DESK_RETRAIN") then
      r.load(java.nio.file.Files.readAllBytes(path)); println(s"  readers loaded: $path")
    else
      r.train(pairs)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, r.bytes); println(s"  readers saved: $path (${java.nio.file.Files.size(path) / 1024} KB)")
    r
