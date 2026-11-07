package okay.desk.bench

import okay.desk.skill.{OnQwen, Read, Reading}
import okay.llm.compute.{Compute, OnCpu, OnMlx}
import okay.llm.qwen.{Qwen3Compute, Qwen3Config, Safetensors, Tokenizer}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * THE JVM'S READING BY THE PAGE'S OWN PROGRAM (specs/calculus.md §11): `Read` answered by okay-llm's `Qwen3Compute`
 * through `OnQwen` — the same forward, the same prefix cache, the same template as the browser — on a `Compute`
 * handler: Apple's GPU (`OnMlx`) or the CPU. mlx-scala's own forward (`MlxReading`) stays for training and the
 * teacher (35B); for reading, one program on four devices. DESK_DEVICE=mlx|cpu in the benches.
 */
final class ComputeReading(dir: java.nio.file.Path, layer: Int, c: Compute) extends Reading:
  private val st = Safetensors(java.nio.file.Files.readAllBytes(dir.resolve("model.safetensors")))
  private val config = Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
  private val tok = Tokenizer.fromJson(okay.effects.json.Json.fromText(java.nio.file.Files.readString(dir.resolve("tokenizer.json"))))
  private val on = OnQwen(Qwen3Compute(c, st, config), tok, layer)
  private def now[X](op: Read[X]): X = Await.result(on.answer(op), 10.minutes)
  def states(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]] = now(Read.States(ids, at))
  def logits(ids: Array[Int]): Array[Float] = now(Read.Logits(ids))
  def encode(text: String): Array[Int] = now(Read.Encode(text))
  def decode(ids: Seq[Int]): String = now(Read.Decode(ids))
  def prompt(system: String, user: String): Array[Int] = now(Read.Prompt(system, user))

object ComputeReading:
  /** the device named by DESK_DEVICE: mlx (Apple's GPU) or cpu; None for mlx-scala's own forward */
  def device: Option[Compute] = scala.sys.env.get("DESK_DEVICE").map {
    case "mlx" => OnMlx()
    case "cpu" => OnCpu()
    case other => sys.error(s"DESK_DEVICE=$other: mlx or cpu")
  }
