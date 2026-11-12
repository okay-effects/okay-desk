package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.Formal
import okay.effects.json.Json

/** Offline training-label preparation from GSM8K's authored calculation annotations, never a runtime text reader.
  * Ambiguous reuse of a numeral as both input and intermediate result is rejected, not guessed.
  */
object GsmPrograms:
  private val numbers = "[0-9]+(?:\\.[0-9]+)?".r
  private val structural = Set("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
    "24", "60", "100", "360", "365", "1000")
  private def canonical(s: String): String = BigDecimal(s).bigDecimal.stripTrailingZeros.toPlainString
  private def literal(s: String): Json =
    val b = BigDecimal(s)
    val d = BigInt(10).pow(b.scale.max(0))
    val n = (b * BigDecimal(d)).toBigIntExact.get
    require(n.abs <= 1000000000 && d <= 1000000000, "number outside formal bounds")
    if d == 1 then Json.Num(n.toLong.toDouble)
    else Json.Arr(Vector(Json.Str("/"), Json.Num(n.toLong.toDouble), Json.Num(d.toLong.toDouble)))
  private def program(expr: Json): String = Json.print(Json.obj("formal" -> Json.obj(
    "vars" -> Json.Obj(Vector.empty), "where" -> Json.Arr(Vector.empty),
    "ask" -> Json.obj("answer" -> expr))))
  private def value(expr: Json): String =
    val text = Json.print(Json.obj("vars" -> Json.Obj(Vector.empty), "where" -> Json.Arr(Vector.empty),
      "ask" -> Json.obj("answer" -> expr)))
    Formal.run(text).fold(e => throw IllegalArgumentException(e), _.answers("answer").head)

  def compile(question: String, solution: String): Either[String, SemanticCorpus.Pair] =
    try
      val inputs = numbers.findAllIn(question.replace(",", "")).map(canonical).toSet
      var derived = Map.empty[String, Json]
      var last: Option[(Json, String)] = None
      val annotations = "<<([^<>]*?)=([^<>]*?)>>".r.findAllMatchIn(solution).toVector
      require(annotations.nonEmpty && annotations.size <= 12, "missing or excessive calculation annotations")
      for annotation <- annotations do
        val text = annotation.group(1).replace(",", "").filterNot(_.isWhitespace)
        var at = 0
        def take(c: Char): Boolean =
          if at < text.length && text(at) == c then { at += 1; true } else false
        def binary(op: String, a: Json, b: Json): Json = Json.Arr(Vector(Json.Str(op), a, b))
        def atom(): Json =
          if take('-') then Json.Arr(Vector(Json.Str("-"), atom()))
          else if take('+') then atom()
          else if take('(') then
            val x = sum()
            require(take(')'), "missing close parenthesis")
            x
          else
            val from = at
            while at < text.length && (text(at).isDigit || text(at) == '.') do at += 1
            require(at > from, "unsupported annotated expression")
            val key = canonical(text.substring(from, at))
            require(!(derived.contains(key) && (inputs(key) || structural(key))), "ambiguous intermediate reference")
            derived.getOrElse(key, {
              require(inputs(key) || structural(key), "ungrounded numeric input")
              literal(key)
            })
        def product(): Json =
          var x = atom()
          var more = true
          while more do
            if take('*') then x = binary("*", x, atom())
            else if take('/') then x = binary("/", x, atom())
            else more = false
          x
        def sum(): Json =
          var x = product()
          var more = true
          while more do
            if take('+') then x = binary("+", x, product())
            else if take('-') then x = binary("-", x, product())
            else more = false
          x
        val expr = sum()
        require(at == text.length && Json.print(expr).length < 1200, "unsupported or excessive expression")
        val expected = canonical(annotation.group(2).replace(",", "").trim)
        require(value(expr) == value(literal(expected)), "annotation arithmetic mismatch")
        derived = derived.updated(expected, expr)
        last = Some(expr -> expected)
      val answer = canonical(solution.split("####").last.trim.replace(",", ""))
      require(last.exists(_._2 == answer), "final annotation differs from the published answer")
      Right(SemanticCorpus.Pair(question, program(last.get._1)))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("unreadable training annotation"))

  def main(args: Array[String]): Unit =
    val input = Path.of(args(0))
    val out = Path.of(args(1))
    val parsed = Files.readString(input).linesIterator.toVector.map { line =>
      val row = Json.fromText(line)
      compile(row("question").string, row("answer").string)
    }
    val good = parsed.flatMap(_.toOption)
    val (held, train) = good.partition(p => Math.floorMod(p.question.hashCode, 10) == 0)
    def write(path: Path, ps: Vector[SemanticCorpus.Pair]): Unit =
      Files.writeString(path,
        ps.map(p => Json.print(Json.obj("question" -> Json.Str(p.question), "program" -> Json.Str(p.program))))
          .mkString("", "\n", "\n"))
      ()
    write(out, train)
    write(out.resolveSibling(out.getFileName.toString + ".heldout"), held)
    println(s"GSM8K authored annotations: ${parsed.size} total, ${train.size} train, ${held.size} held out")
    parsed.collect { case Left(reason) => reason }.groupBy(identity).toVector.sortBy(-_._2.size)
      .foreach((reason, failures) => println(s"${failures.size}: $reason"))
