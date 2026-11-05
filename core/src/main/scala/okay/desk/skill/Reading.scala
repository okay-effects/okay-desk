package okay.desk.skill

/**
 * WHAT A SKILL NEEDS OF A MODEL (specs/data.md §15): the residual after the skill's layer at given positions of a
 * token sequence, the last position's logits (for ask), the tokenizer, and the chat prompt — nothing else. The JVM
 * provides it through mlx-scala; the browser through a WebGPU forward of the cut model. A skill never sees weights.
 */
trait Reading:
  /** the residual after the skill's layer at positions `at` of `ids`: one vector each */
  def states(ids: Array[Int], at: Vector[Int]): Vector[Array[Float]]
  /** the logits of the next token after `ids` */
  def logits(ids: Array[Int]): Array[Float]
  def encode(text: String): Array[Int]
  def decode(ids: Seq[Int]): String
  /** the chat prompt for a system text and a user text, thinking off */
  def prompt(system: String, user: String): Array[Int]
