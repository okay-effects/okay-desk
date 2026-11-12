package okay.desk.bench

import mlx.{Arr, Mlx, Scope}
import okay.desk.skill.{Read, Reading}
import okay.effects.json.Json

import java.nio.file.{Files, Path}

/**
 * A BIDIRECTIONAL ENCODER AS THE READER'S BACKBONE (the trial of `StatementRead`, READ_BACKBONE=e5): a BERT of the
 * MiniLM family (`intfloat/multilingual-e5-small`: 12 layers of 384, 21M parameters under a 250k vocabulary) read
 * from its HuggingFace files and run on Apple's GPU — one pass over a task, every token's state knowing the whole
 * text. The question it answers: is the reader held back by the class of the model (a causal state knows only what
 * came before) or by the corpus.
 *
 * The tokenizer is the file's Unigram model by Viterbi, a word at a time (Metaspace: a word is `▁` and its
 * letters); a piece's place in the text is known as it is cut, so `Decode` of a prefix of the last prompt is the
 * text up to that piece's end — what `StatementRead.see` asks to place the tokens. No normalizer: the tasks' texts
 * are plain. GELU by its tanh form (no erf on the device): within 1e-3 of the exact one.
 */
final class EncoderReading(dir: Path, layer: Int) extends Reading:
  Mlx.init()
  private given weights: Scope = new Scope
  private val w = Mlx.loadSafetensors(dir.resolve("model.safetensors").toString)
  private val vocab: java.util.HashMap[String, (Int, Float)] =
    val json = Json.fromText(Files.readString(dir.resolve("tokenizer.json")))
    val map = java.util.HashMap[String, (Int, Float)]()
    for (piece, id) <- json("model")("vocab").list.zipWithIndex do
      piece match
        case Json.Arr(Vector(p, score)) => map.put(p.string, (id, score.double.toFloat))
        case other => sys.error(s"a piece with its score expected: $other")
    map
  private val (bos, eos, unk, longest, heads) = (0, 2, 3, 24, 12)
  private val hidden = w("embeddings.LayerNorm.weight").dim(0)
  private val places = w("embeddings.position_embeddings.weight").dim(0)
  /** a dense layer's weight as x · Wᵀ wants it */
  private val turned = w.collect { case (name, a) if name.endsWith(".weight") && a.ndim == 2 && name.contains("dense")
    || name.endsWith("query.weight") || name.endsWith("key.weight") || name.endsWith("value.weight") =>
    name -> Mlx.transpose(a, 1, 0) }
  /** the texts longer than the model's positions (their tail read at the last position) */
  var tooLong = 0

  /** a text's pieces: each an id and its place in the text (the space before a word is its first piece's) */
  def pieces(text: String): Vector[(Int, Int, Int)] =
    val out = Vector.newBuilder[(Int, Int, Int)]
    var i = 0
    while i < text.length do
      while i < text.length && text(i).isWhitespace do i += 1
      val from = i
      while i < text.length && !text(i).isWhitespace do i += 1
      if i > from then
        val word = "▁" + text.substring(from, i)
        // Viterbi: the best score of a cut of the first k letters, and the piece it ends with
        val best = Array.fill(word.length + 1)(Float.NegativeInfinity)
        val back = new Array[(Int, Int)](word.length + 1)
        best(0) = 0f
        for k <- 1 to word.length; a <- math.max(0, k - longest) until k if best(a) > Float.NegativeInfinity do
          val found = vocab.get(word.substring(a, k))
          val (id, score) = if found != null then found else if k - a == 1 then (unk, -30f) else (-1, 0f)
          if id >= 0 && best(a) + score > best(k) then { best(k) = best(a) + score; back(k) = (a, id) }
        var (k, cut) = (word.length, List.empty[(Int, Int, Int)])
        while k > 0 do
          val (a, id) = back(k)
          cut = (id, math.max(0, from - 1 + a), from - 1 + k) :: cut
          k = a
        out ++= cut
    out.result()

  private var last: (Array[Int], String, Vector[Int]) = (Array.empty, "", Vector.empty)

  private def layerNorm(x: Arr, p: String)(using Scope): Arr =
    val n = Mlx.scalar(x.dim(1).toFloat)
    val centred = Mlx.subtract(x, Mlx.divide(Mlx.sum(x, 1, keepdims = true), n))
    val variance = Mlx.divide(Mlx.sum(Mlx.multiply(centred, centred), 1, keepdims = true), n)
    val normed = Mlx.divide(centred, Mlx.sqrt(Mlx.add(variance, Mlx.scalar(1e-12f))))
    Mlx.add(Mlx.multiply(normed, w(p + ".weight")), w(p + ".bias"))
  private def dense(x: Arr, p: String)(using Scope): Arr = Mlx.add(Mlx.matmul(x, turned(p + ".weight")), w(p + ".bias"))
  private def gelu(x: Arr)(using Scope): Arr =
    val cube = Mlx.multiply(Mlx.multiply(x, x), x)
    val inner = Mlx.add(x, Mlx.multiply(cube, Mlx.scalar(0.044715f)))
    Mlx.multiply(x, Mlx.sigmoid(Mlx.multiply(inner, Mlx.scalar(1.5957691f))))

  /** the states after `layer` at the rows `at` */
  def states(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]] =
    val s = new Scope
    try
      given Scope = s
      val n = ids.length
      if n > places then tooLong += 1
      val words = Mlx.take(w("embeddings.word_embeddings.weight"), Mlx.ints(ids, n), 0)
      val position = Mlx.take(w("embeddings.position_embeddings.weight"),
        Mlx.ints(Array.tabulate(n)(i => math.min(i, places - 1)), n), 0)
      val kind = Mlx.slice(w("embeddings.token_type_embeddings.weight"), Seq(0, 0), Seq(1, hidden))
      var x = layerNorm(Mlx.add(Mlx.add(words, position), kind), "embeddings.LayerNorm")
      val headDim = hidden / heads
      def byHead(a: Arr): Arr = Mlx.transpose(Mlx.reshape(a, n, heads, headDim), 1, 0, 2)
      for l <- 0 until layer do
        val p = s"encoder.layer.$l."
        val (q, k, v) = (byHead(dense(x, p + "attention.self.query")), byHead(dense(x, p + "attention.self.key")),
          byHead(dense(x, p + "attention.self.value")))
        val scores = Mlx.multiply(Mlx.matmul(q, Mlx.transpose(k, 0, 2, 1)), Mlx.scalar(1f / math.sqrt(headDim).toFloat))
        val mixed = Mlx.reshape(Mlx.transpose(Mlx.matmul(Mlx.softmax(scores, -1), v), 1, 0, 2), n, hidden)
        val attended = layerNorm(Mlx.add(x, dense(mixed, p + "attention.output.dense")), p + "attention.output.LayerNorm")
        val fed = dense(gelu(dense(attended, p + "intermediate.dense")), p + "output.dense")
        x = layerNorm(Mlx.add(attended, fed), p + "output.LayerNorm").eval()
      val all = x.toFloats
      at.map(i => all.slice(i * hidden, (i + 1) * hidden))
    finally s.close()

  def answer[X](op: Read[X]): X = op match
    case Read.Prompt(_, user) =>
      val cut = pieces(user)
      val ids = (bos +: cut.map(_._1) :+ eos).toArray
      last = (ids, user, (0 +: cut.map(_._3) :+ user.length))
      ids
    case Read.Decode(ids) =>
      require(ids.size <= last._1.length && ids.indices.forall(i => ids(i) == last._1(i)), "a prefix of the last prompt")
      if ids.isEmpty then "" else last._2.substring(0, last._3(ids.size - 1))
    case Read.States(ids, at) => states(ids, at)
    case Read.Encode(text) => pieces(text).map(_._1).toArray
    case Read.Logits(_) => sys.error("an encoder has no next token")
    case Read.Note(_) => ()

/** A CHECK BY THE MODEL'S OWN PURPOSE: the pieces of a sentence, and the mean states of sentences that say the same
 * in two languages against one that says another thing — `bench/runMain okay.desk.bench.EncoderReading` */
object EncoderReading:
  def main(args: Array[String]): Unit =
    val e = EncoderReading(Path.of(sys.env.getOrElse("E5_DIR", sys.props("user.home") + "/.cache/okay-desk/e5-small")),
      sys.env.get("READ_LAYER").map(_.toInt).getOrElse(12))
    def embed(text: String): Array[Float] =
      val ids = e.answer(Read.Prompt("", text))
      val states = e.states(ids, ids.indices.toVector)
      val mean = Array.tabulate(states.head.length)(j => states.map(_(j)).sum / states.size)
      val norm = math.sqrt(mean.map(x => x * x).sum).toFloat
      mean.map(_ / norm)
    def cos(a: String, b: String): Float = embed(a).zip(embed(b)).map(_ * _).sum
    val texts = Vector("query: Anna lives in the red house, next to the doctor.",
      "query: Анна живёт в красном доме, рядом с врачом.", "query: The stock market fell by three percent on Monday.")
    for t <- texts do println(e.pieces(t).map((id, a, b) => s"$id:${t.substring(a, b)}").mkString("|"))
    println(f"same meaning en/ru ${cos(texts(0), texts(1))}%.3f, other en ${cos(texts(0), texts(2))}%.3f, " +
      f"other ru/en ${cos(texts(1), texts(2))}%.3f")
