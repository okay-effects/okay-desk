package okay.desk.know

/** Arithmetic as an exact tool, never evaluated as host-language code. Repeating decimals remain fractions. */
object Arithmetic:
  private case class Ratio(n: BigInt, d: BigInt):
    def +(b: Ratio): Ratio = ratio(n * b.d + b.n * d, d * b.d)
    def unary_- : Ratio = Ratio(-n, d)
    def *(b: Ratio): Ratio = ratio(n * b.n, d * b.d)
    def /(b: Ratio): Ratio = ratio(n * b.d, d * b.n)
    def show: String =
      if d == 1 then n.toString
      else
        var rest = d
        while rest % 2 == 0 do rest /= 2
        while rest % 5 == 0 do rest /= 5
        if rest == 1 then
          new java.math.BigDecimal(n.bigInteger)
            .divide(new java.math.BigDecimal(d.bigInteger))
            .stripTrailingZeros
            .toPlainString
        else s"$n/$d"

  private def ratio(n: BigInt, d: BigInt): Ratio =
    require(d != 0, "division by zero")
    val gcd = n.gcd(d) * d.signum
    Ratio(n / gcd, d / gcd)

  def expression(question: String): Option[String] =
    val q = question.trim
      .stripSuffix("?")
      .stripSuffix("=")
      .trim
      .replaceFirst("(?i)^(what is|calculate|сколько будет|вычисли)\\s+", "")
    Option.when(q.matches("[0-9+*/().\\s-]+") && q.exists("+*/-".contains(_)))(q)

  /** Operands must occur in the question; the model is not allowed to invent a factual input to arithmetic. */
  def run(expression: String, question: String): Either[String, String] =
    val number = "[0-9]+(?:\\.[0-9]+)?".r
    val inputs = number.findAllIn(question).toSet
    if expression.length > 300 || "[0-9.]\\s+[0-9.]".r.findFirstIn(expression).nonEmpty ||
      number.findAllIn(expression).exists(n => n.length > 30 || !inputs(n))
    then return Left("arithmetic inputs must be copied from the question and fit the tool's bounds")
    try
      val text = expression.filterNot(_.isWhitespace)
      var at = 0
      def take(c: Char): Boolean =
        if at < text.length && text(at) == c then { at += 1; true }
        else false
      def atom(): Ratio =
        if take('-') then -atom()
        else if take('+') then atom()
        else if take('(') then
          val value = sum()
          require(take(')'), "closing parenthesis missing")
          value
        else
          val start = at
          while at < text.length && (text(at).isDigit || text(at) == '.') do at += 1
          val literal = text.substring(start, at)
          require(literal.matches("[0-9]+(?:\\.[0-9]+)?"), "number expected")
          val pieces = literal.split("\\.")
          val denominator = if pieces.length == 1 then BigInt(1) else BigInt(10).pow(pieces(1).length)
          ratio(BigInt(pieces.mkString), denominator)
      def product(): Ratio =
        var value = atom()
        var more = true
        while more do
          if take('*') then value = value * atom()
          else if take('/') then value = value / atom()
          else more = false
        value
      def sum(): Ratio =
        var value = product()
        var more = true
        while more do
          if take('+') then value = value + product()
          else if take('-') then value = value + -product()
          else more = false
        value
      val answer = sum()
      require(at == text.length, "unexpected arithmetic input")
      Right(answer.show)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("invalid arithmetic expression"))
