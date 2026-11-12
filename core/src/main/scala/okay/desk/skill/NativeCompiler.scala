package okay.desk.skill

import okay.desk.know.{Formal, Knowledge, Planning}
import okay.scala3.*

/** Autoregressive program authoring by the native language model. No topic dictionary or clue parser is called. */
object NativeCompiler:
  final case class Output(text: String, tokens: Int, sequence: Vector[Int] = Vector.empty)
  val actions: String = """
    |You may build a solution step by step with the exact CPU tool:
    |{"eval":["*",8,4]} returns a named exact result, for example Tool result r1 = 32.
    |{"eval":["-","r1",5]} uses that result and returns Tool result r2 = 27.
    |{"result":"r2"} selects the tool result answering the original question. Never invent a result ID.
    |For multi-step arithmetic prefer these small steps to guessing a large expression or doing mental arithmetic.
    |All reference strings must name existing tool results. Choose operations from the task's meaning.
    |Use the formal model for variables, logical constraints and questions about unknown values.
    |""".stripMargin
  def toolTurn(feedback: String): String =
    "<|im_end|>\n<|im_start|>user\n" + feedback +
      "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
  def historyText(history: Vector[(String, String)]): String =
    history.map((program, feedback) => program + toolTurn(feedback)).mkString
  def promptText(question: String, history: Vector[(String, String)] = Vector.empty): String =
    OnQwen.template(Formal.instructions + actions + Planning.instructions, question) + historyText(history)
  def compile[R[_]](question: String, sources: Vector[Knowledge.Source] = Vector.empty,
      observations: Vector[String] = Vector.empty, maxTokens: Int = 1024, maxContext: Int = 3072,
      tableInstructions: String = "", history: Vector[(String, String)] = Vector.empty,
      continuation: Option[(Vector[Int], String)] = None, systemInstructions: Option[String] = None)(using
      In[Read, R]): Either[String, Output] ! R =
    val system = systemInstructions.getOrElse(Formal.instructions + actions + Planning.instructions) + tableInstructions
    val evidence = sources.map(s => s"${s.id}: ${s.text}").mkString("\n")
    val user = question + (if evidence.isEmpty then "" else "\nCollected evidence:\n" + evidence) +
      (if observations.isEmpty || history.nonEmpty then "" else
        "\nPrevious tool feedback:\n" + observations.mkString("\n"))
    for
      ids <- continuation match
        case Some((prefix, feedback)) => Read.encode[R](toolTurn(feedback)).map(prefix.toArray ++ _)
        case None => Read.prompt[R](system, user).flatMap { prefix =>
          if history.isEmpty then pure[Array[Int], R](prefix)
          else Read.encode[R](historyText(history)).map(prefix ++ _)
        }
      end <- Read.encode[R]("<|im_end|>")
      eof <- Read.encode[R]("<|endoftext|>")
      result <-
        if ids.length >= maxContext then pure[Either[String, Output], R](
          Left("The task exceeds this model's current context limit. No answer was proved."))
        else
          val stops = (end.toVector ++ eof.toVector).toSet
          def loop(prefix: Array[Int], generated: Vector[Int], text: String): Either[String, Output] ! R =
            if generated.size >= 64 && !text.contains('{') then
              pure(Left("The model did not start a formal program within 64 tokens; no answer was proved."))
            else if generated.size >= maxTokens || prefix.length >= maxContext then
              pure(Left("The model could not finish a formal program within its generation limit. " +
                "No answer was proved."))
            else
              for
                _ <- if generated.size % 16 == 0 then Read.note[R](
                  s"language model building the formal program: ${generated.size} tokens…") else pure[Unit, R](())
                scores <- Read.logits[R](prefix)
                answer <-
                  if scores.isEmpty || !scores.exists(_.isFinite) then pure[Either[String, Output], R](
                    Left("language model returned no finite token scores"))
                  else
                    val next = scores.indices.filter(i => scores(i).isFinite).maxBy(scores(_))
                    if stops(next) then pure(Right(Output(text, generated.size, prefix.toVector)))
                    else
                      val tokens = generated :+ next
                      Read.decode[R](tokens).flatMap { decoded =>
                        // Stop at a complete outer JSON object, before the model starts an unrequested explanation.
                        objectBoundary(decoded) match
                          case Left(error) => pure(Left(error))
                          case Right(Some((start, end))) => pure(Right(Output(decoded.substring(start, end),
                            tokens.size, (prefix :+ next).toVector)))
                          case Right(None) => loop(prefix :+ next, tokens, decoded)
                      }
              yield answer
          loop(ids, Vector.empty, "")
    yield result

  private def objectBoundary(text: String): Either[String, Option[(Int, Int)]] =
    val start = text.indexOf('{')
    if start < 0 then Right(None)
    else
      var stack = List.empty[Char]
      var string = false
      var escape = false
      var index = start
      while index < text.length do
        val ch = text(index)
        if string then
          if escape then escape = false
          else if ch == '\\' then escape = true
          else if ch == '"' then string = false
        else if ch == '"' then string = true
        else if ch == '{' || ch == '[' then
          stack = ch :: stack
          if stack.size > 32 then return Left(
            "The model's program exceeds the supported nesting limit. No answer was proved.")
        else if ch == '}' || ch == ']' then
          val expected = if ch == '}' then '{' else '['
          if !stack.headOption.contains(expected) then return Left(
            "The model produced mismatched JSON brackets. No answer was proved.")
          stack = stack.tail
          if stack.isEmpty then return Right(Some(start -> (index + 1)))
        index += 1
      Right(None)
