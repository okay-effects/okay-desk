package okay.desk

import okay.desk.skill.{NativeCompiler, Read, Reading}

/** Decoder/protocol laws only. These fake logits are not evidence of language-model comprehension. */
class NativeCompilerSuite extends munit.FunSuite:
  final class Script(parts: Vector[String]) extends Reading:
    var forwards = 0
    var prompts = 0
    var initialSize = 0
    var system = ""
    def answer[X](op: Read[X]): X = op match
      case Read.Prompt(instructions, _) => prompts += 1; system = instructions; Array(1)
      case Read.Encode("<|im_end|>") => Array(10000)
      case Read.Encode("<|endoftext|>") => Array(10001)
      case Read.Encode(s) if s.startsWith("<|im_end|>") => Array.fill(5)(9990)
      case Read.Encode(_) => fail("the decoder must not extract a task vocabulary")
      case Read.Decode(ids) => ids.map(id => parts(id - 2)).mkString
      case Read.Logits(ids) =>
        if forwards == 0 then initialSize = ids.length
        forwards += 1
        val index = ids.length - initialSize
        val next = if index < parts.size then index + 2 else 10000
        Array.tabulate(10002)(id => if id == next then 1f else Float.NegativeInfinity)
      case Read.Note(_) => ()
      case Read.States(_, _) => fail("a native author must use the LM output, not a hand-built slot reader")

  test("native authoring stops at complete JSON, including braces inside quoted literals"):
    val text = """{"formal":{"vars":{},"where":[],"ask":{"value":["text","a}b"]}}}"""
    val r = Script(text.map(_.toString).toVector :+ "unrequested explanation")
    val output = Read.run(NativeCompiler.compile[Read]("an unseen subject"), r).toOption.get
    assertEquals(output.text, text)
    assertEquals(r.forwards, text.length)

  test("context and token exhaustion stop explicitly without falling back to a text interpreter"):
    val r = Script(Vector("{", "\"formal\"", ":"))
    assert(Read.run(NativeCompiler.compile[Read]("anything", maxTokens = 2), r).isLeft)
    val tooLong = Script(Vector.empty)
    assert(Read.run(NativeCompiler.compile[Read]("anything", maxContext = 1), tooLong).isLeft)
    assertEquals(tooLong.forwards, 0)

  test("a published adapter can retain its measured prompt without research instructions being appended"):
    val r = Script(Vector("""{"clarify":"Which conditions apply?"}"""))
    val result = Read.run(NativeCompiler.compile[Read]("a question", systemInstructions = Some("pinned prompt")), r)
    assert(result.isRight)
    assertEquals(r.system, "pinned prompt")

  test("unstructured model prose stops early without spending the entire generation budget"):
    val r = Script(Vector.fill(100)("word "))
    val result = Read.run(NativeCompiler.compile[Read]("an arbitrary task"), r)
    assert(result.left.toOption.get.contains("did not start a formal program"))
    assertEquals(r.forwards, 64)

  test("a runaway nested expression stops before exhausting tokens or device memory"):
    val text = """{"formal":{"vars":{},"where":[],"ask":{"answer":""" +
      Vector.fill(40)("""["+",1,""").mkString + "2" + ("]" * 40) + "}}}"
    val r = Script(text.map(_.toString).toVector)
    val result = Read.run(NativeCompiler.compile[Read]("a task requiring a different formal model"), r)
    assert(result.left.toOption.get.contains("nesting limit"))
    assert(r.forwards < text.length)

  test("mismatched JSON brackets stop without executing a partial program"):
    val r = Script("""{"formal":[}""".map(_.toString).toVector)
    assert(Read.run(NativeCompiler.compile[Read]("anything"), r).left.toOption.get.contains("mismatched"))

  test("tool feedback extends the exact previous token sequence rather than replaying the whole question"):
    val first = Script(Vector("""{"eval":["+",1,1]}"""))
    val reply = Read.run(NativeCompiler.compile[Read]("a task"), first).toOption.get
    val second = Script(Vector("""{"result":"r1"}"""))
    val result = Read.run(NativeCompiler.compile[Read]("a task",
      continuation = Some(reply.sequence -> "Tool result r1 = 2")), second).toOption.get
    assertEquals(second.prompts, 0)
    assertEquals(second.initialSize, reply.sequence.size + 5)
    assert(result.sequence.startsWith(reply.sequence))
