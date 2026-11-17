package okay.desk.skill

import okay.llm.qwen.{Qwen3Compute, Tokenizer}

import scala.concurrent.{ExecutionContext, Future}

/**
 * THE BROWSER'S READING (specs/data.md §15, step 4): `Read` answered by okay-llm's Qwen3 forward on a `Compute`
 * device — WebGPU, WebGL2 or the CPU — each a future (`Read.future`). The chat prompt is Qwen3's template with
 * thinking off, as mlx-scala renders it on the JVM: the same tokens, so the skill trained there reads here.
 *
 * ONE PREFIX CACHED (§17): the readers ask ten to fifteen times about the same prompt with a few tokens appended
 * (`{"from": "orders", "where": [{"column": "`); the first ask of a sequence runs it whole and keeps its keys,
 * values and states on the device, every later ask that begins with it runs only the tokens after — a question
 * costs one forward of the prompt, not fifteen. A sequence that does not begin with the cached one replaces it.
 */
/** `ensure`: the embedding's rows of these tokens loaded before a forward (blocks by language fetched as needed) */
final class OnQwen(model: Qwen3Compute, tok: Tokenizer, layer: Int, ensure: Array[Int] => Future[Unit] = _ => Future.unit)(
    using ExecutionContext):
  def rehead(): Unit = model.rehead()
  def clearCache(): Unit =
    cached.foreach(_._2.free()); cached = None; model.dropTrees()
  def close(): Unit =
    clearCache()
    model.c.close()
  /** the options of one weighing at most, a forward (0: all at once) — a phone sets it (Engine) */
  var parts: Int = 0
  /** the trie's numbers checked against the packed segments', each weighing of a part (a device's law, a driver's flag) */
  var checkTrie: Boolean = false
  /** the tokens two prompts begin with alike; at least `shared` of them and the last cache's rows are reused */
  private def common(a: Array[Int], b: Array[Int]): Int =
    var i = 0
    while i < a.length && i < b.length && a(i) == b(i) do i += 1
    i
  private val shared = 32
  private var cached: Option[(Array[Int], model.Cache)] = None
  /** the forward: after the cached prefix when `ids` begins with it, else whole and kept */
  private def run(ids: Array[Int], at: Seq[Int], withLogits: Boolean): Future[(Vector[Array[Float]], Array[Float])] =
    ensure(ids).flatMap(_ => forward(ids, at, withLogits))
  private def forward(ids: Array[Int], at: Seq[Int], withLogits: Boolean): Future[(Vector[Array[Float]], Array[Float])] =
    cached match
      // (the cached sequence itself asked again: run whole — the cache holds no final state for its last row)
      case Some((prefix, cache)) if ids.length > prefix.length && ids.startsWith(prefix) =>
        val attempted = try
          model.run(ids, layer, at, withLogits, Some(cache), keep = withLogits, consumeCache = withLogits)
        catch case error: Exception => Future.failed(error)
        attempted.map { (s, l, next) =>
          if withLogits then
            cached = next.map(ids -> _)
            cache.free()
          (s, l)
        }.recoverWith { case error => clearCache(); Future.failed(error) }
      case _ =>
        cached.foreach(_._2.free()); cached = None
        model.run(ids, layer, at, withLogits, None, keep = true).map { (s, l, c) => cached = c.map(ids -> _); (s, l) }
  def answer[X](op: Read[X]): Future[X] = op match
    case Read.States(ids, at) => run(ids, at, withLogits = false).map(_._1)
    case Read.Logits(ids) => run(ids, Nil, withLogits = true).map(_._2)
    case Read.Encode(text) => Future.successful(tok.encode(text))
    case Read.Decode(ids) => Future.successful(tok.decode(ids))
    case Read.Prompt(system, user) => Future.successful(tok.encode(OnQwen.template(system, user)))
    case Read.Note(_) => Future.unit
    // a line's options weighed in one packed forward after the context's cache (okay-llm's `Qwen3Compute.score`);
    // the log-softmax within the options' tokens (the head a few hundred rows: a phone's; Everyday 62 of 108 right as over
    // the whole vocabulary, 0.91 s a question on the JVM)
    case Read.Score(context, options) =>
      val head = context.init
      // (a def: run only after the blocks are loaded)
      def ready: Future[model.Cache] = cached match
        case Some((prefix, cache)) if prefix.sameElements(head) => Future.successful(cache)
        // THE LINE CHOSEN, ALREADY RUN: its tokens were rows of the trie just weighed — the cache grows by them with no
        // forward (Qwen3Compute.extend; a phone: one forward a line instead of two)
        case Some((prefix, cache)) if head.startsWith(prefix) && head.length > prefix.length &&
            model.extend(cache, head.drop(prefix.length)).exists { next =>
              cache.free(); cached = Some(head -> next); true } =>
          Future.successful(cached.get._2)
        case Some((prefix, cache)) if head.startsWith(prefix) =>
          model.run(head, layer, Nil, withLogits = false, Some(cache), keep = true).map { (_, _, next) =>
            cache.free(); cached = next.map(head -> _); next.get }
        // A NEXT QUESTION OVER THE SAME TABLES: the prompt's beginning (the rule, the tables) is the last one's — its
        // rows kept (`take`), only the rest run; a phone ran the whole prompt again each question (37 s on the iPhone)
        case Some((prefix, cache)) if common(prefix, head) >= shared =>
          val shorter = cache.take(common(prefix, head))
          cache.free()
          model.run(head, layer, Nil, withLogits = false, Some(shorter), keep = true).map { (_, _, next) =>
            shorter.free(); cached = next.map(head -> _); next.get }
        case _ =>
          clearCache()
          model.run(head, layer, Nil, withLogits = false, None, keep = true).map { (_, _, next) =>
            cached = next.map(head -> _); next.get }
      // (the options' tokens' embedding blocks loaded first: the head knows only the loaded rows)
      // IN PARTS (`parts` > 0, a phone): a line of many options (171 for a `where`) packed into one forward was one GPU
      // task of minutes on an iPhone, the tab died in it; each part is normed over the tokens of all the options
      // (`over`), so the numbers are the one forward's
      val all = options.flatten.toArray
      ensure(context ++ options.flatten).flatMap(_ => ready).flatMap { cache =>
        if parts <= 0 || options.size <= parts then
          val got = model.score(cache, context.last, options, within = true)
          // THE TRIE CHECKED ON THE DEVICE (`checkTrie`): the same weighing by packed segments, the largest
          // difference said
          if !checkTrie then got
          else got.flatMap(a => model.score(cache, context.last, options, within = true, trie = Some(false)).map { b =>
            val diff = a.zip(b).map((x, y) => math.abs(x - y)).max
            println(f"model trie check: ${options.size} options, max diff $diff%.2e, " +
              f"best ${a.indexOf(a.max)} vs ${b.indexOf(b.max)}")
            a })
        else options.grouped(parts).foldLeft(Future.successful(Vector.empty[Double])) { (acc, part) =>
          acc.flatMap(got => model.score(cache, context.last, part, within = true, over = Some(all)).map(got ++ _)) } }
  def handler: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => answer[X](op)

  /**
   * THE STATES OF ONE READING, KEEPING NOTHING (specs/formalization.md §18.3): a statement reader asks once about a
   * prompt no later ask begins with — so no prefix is kept, and each layer's keys and values are let go as soon as
   * the next layer is computed. On a phone that is the difference between all the layers' keys and values of a long
   * task at once (some 100 MB) and one layer's. The cache of other readers is dropped first.
   */
  def statesOnce(ids: Array[Int], at: Seq[Int]): Future[Vector[Array[Float]]] =
    clearCache()
    ensure(ids).flatMap(_ => model.run(ids, layer, at, withLogits = false, None, keep = false)).map(_._1)
  /** `c.api` of the device it runs on */
  def api: String = model.c.api
  def layers: Int = model.config.layers

object OnQwen:
  /** Qwen3's chat template, thinking off (an empty think block, as the template writes it) */
  def template(system: String, user: String): String =
    s"<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
