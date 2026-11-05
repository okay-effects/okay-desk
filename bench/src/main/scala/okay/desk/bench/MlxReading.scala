package okay.desk.bench

import mlx.{Arr, Dtype, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Qwen3}
import okay.desk.skill.{Pairs, Readers, Reading}

/** THE JVM'S READING: mlx-scala's Qwen3 on Apple's GPU — the residual after `layer` by the hook, the logits by the
 * model, the tokenizer and the chat template of the model's directory */
final class MlxReading(chat: Chat, model: Qwen3, layer: Int) extends Reading:
  private val tok = chat.tokenizer
  def states(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]] =
    var got = Vector.empty[Array[Float]]
    model.hook = new Qwen3.Hook:
      def after(i: Int, h: Arr)(using Scope): Arr =
        if i + 1 == layer then
          val d = h.dim(2); val all = astype(h, Dtype.Float32).toFloats
          got = at.map(p => all.slice(p * d, (p + 1) * d))
        h
    val st = model.newState(); val s = new Scope
    try { model.logits(ints(ids, 1, ids.length)(using s), st)(using s).eval(); got }
    finally { s.close(); st.close(); model.hook = null }
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

object MlxReading:
  /** the readers for a model and a layer: loaded from the cache when there (DESK_RETRAIN=1 trains again) */
  def ready(chat: Chat, model: Qwen3, layer: Int, repo: String, pairs: => Vector[Pairs.Pair]): Readers.On =
    val r = Readers.On(Readers(), MlxReading(chat, model, layer))
    val path = java.nio.file.Path.of(sys.props("user.home"), ".cache", "okay-desk", s"readers-${repo.split('/').last}-$layer.skill")
    if java.nio.file.Files.exists(path) && !scala.sys.env.contains("DESK_RETRAIN") then
      r.load(java.nio.file.Files.readAllBytes(path)); println(s"  readers loaded: $path")
    else
      r.train(pairs)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, r.bytes); println(s"  readers saved: $path (${java.nio.file.Files.size(path) / 1024} KB)")
    r
