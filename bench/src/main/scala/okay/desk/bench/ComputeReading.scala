package okay.desk.bench

import okay.desk.skill.{Adapted, OnQwen, Read, Reading}
import okay.llm.compute.{Compute, OnCpu, OnMlx}
import okay.llm.qwen.{Qwen3Compute, Qwen3Config, Safetensors, Tokenizer}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/**
 * THE JVM'S READING BY THE PAGE'S OWN PROGRAM (specs/calculus.md §11): `Read` answered by okay-llm's `Qwen3Compute`
 * through `OnQwen` — the same forward, the same prefix cache, the same template as the browser — on a `Compute`
 * handler: Apple's GPU (`OnMlx`) or the CPU. mlx-scala's own forward (`MlxReading`) stays for training and the
 * teacher (35B); for reading, one program on four devices. DESK_DEVICE=mlx|cpu in the benches.
 */
final class ComputeReading(dir: java.nio.file.Path, layer: Int, c: Compute) extends Reading:
  // Generated prefixes change size at every token; cap retained free Metal buffers in workstation benches.
  if c.api == "mlx" then
    val _ = mlx.Mlx.setCacheLimit(256L * 1024 * 1024)
  // every step on the calling thread: MLX's default stream is the thread's (a pool thread has none)
  private given ExecutionContext = ExecutionContext.parasitic
  private val st = Safetensors(java.nio.file.Files.readAllBytes(dir.resolve("model.safetensors")))
  private val config = Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
  private val tok = Tokenizer.fromJson(okay.effects.json.Json.fromText(java.nio.file.Files.readString(dir.resolve("tokenizer.json"))))
  private val adapted = sys.env.get("SEMANTIC_NATIVE_ADAPTER").map { path =>
    Adapted(c, st, Safetensors(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path))))
  }
  private val model = adapted.fold(Qwen3Compute(c, st, config))(a =>
    Qwen3Compute(a, a.tensors, config))
  adapted.foreach(_.releaseSources())
  private val on = OnQwen(model, tok, layer)
  def answer[X](op: Read[X]): X = Await.result(on.answer(op), 10.minutes)

object ComputeReading:
  /** the device named by DESK_DEVICE: mlx (Apple's GPU) or cpu; None for mlx-scala's own forward */
  def device: Option[Compute] = scala.sys.env.get("DESK_DEVICE").map {
    case "mlx" => OnMlx()
    case "cpu" => OnCpu()
    case other => sys.error(s"DESK_DEVICE=$other: mlx or cpu")
  }
