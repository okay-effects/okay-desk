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
  /** the operation answered: `Read`'s one list of what a skill needs, this backend's way */
  def answer[X](op: Read[X]): X = op match
    case Read.States(ids, at) => states(ids, at)
    case Read.Logits(ids) => logits(ids)
    case Read.Encode(text) => encode(text)
    case Read.Decode(ids) => decode(ids)
    case Read.Prompt(system, user) => prompt(system, user)
    case Read.Note(_) => ()

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
