package okay.desk.skill

import okay.llm.qwen.{Qwen3Compute, Tokenizer}

import scala.concurrent.{ExecutionContext, Future}

/**
 * THE BROWSER'S READING (specs/data.md §15, step 4): `Read` answered by okay-llm's Qwen3 forward on a `Compute`
 * device — WebGPU, WebGL2 or the CPU — each a future (`Read.future`). The chat prompt is Qwen3's template with
 * thinking off, as mlx-scala renders it on the JVM: the same tokens, so the skill trained there reads here.
 */
final class OnQwen(model: Qwen3Compute, tok: Tokenizer, layer: Int)(using ExecutionContext):
  def answer[X](op: Read[X]): Future[X] = op match
    case Read.States(ids, at) => model.forward(ids, layer, at, withLogits = false).map(_._1)
    case Read.Logits(ids) => model.forward(ids, 0, Nil, withLogits = true).map(_._2)
    case Read.Encode(text) => Future.successful(tok.encode(text))
    case Read.Decode(ids) => Future.successful(tok.decode(ids))
    case Read.Prompt(system, user) => Future.successful(tok.encode(OnQwen.template(system, user)))
  def handler: [X] => Read[X] => Future[X] = [X] => (op: Read[X]) => answer[X](op)
  /** `c.api` of the device it runs on */
  def api: String = model.c.api

object OnQwen:
  /** Qwen3's chat template, thinking off (an empty think block, as the template writes it) */
  def template(system: String, user: String): String =
    s"<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
