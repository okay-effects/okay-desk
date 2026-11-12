package okay.desk.know

/** Preserve whole evidence sentences, including decimals, abbreviations and parenthesized source details. */
object SourceText:
  private val abbreviations = Set("mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "e.g", "i.e")
  /** These scopes need richer temporal/modal terms; they cannot be silently flattened into present facts. */
  def assertive(text: String): Boolean =
    val unsupported = ("(?iu)\\b(?:perhaps|maybe|possibly|allegedly|reportedly|suppose|unknown|uncertain|" +
      "believes?|claims?|" +
      "suggested|formerly|historically|was|were)\\b|according to|used to|возможно|неизвестно|считает|предполож").r
    unsupported.findFirstIn(text).isEmpty

  def sentences(text: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var start = 0
    var depth = 0
    for i <- text.indices do
      val c = text(i)
      if c == '(' || c == '[' then depth += 1
      if c == ')' || c == ']' then depth = math.max(0, depth - 1)
      val decimal = i > 0 && i + 1 < text.length && text(i - 1).isDigit && text(i + 1).isDigit
      val word = text.substring(start, i).reverse.takeWhile(ch => ch.isLetter || ch == '.').reverse.toLowerCase
      val abbreviation = abbreviations(word) && i + 1 < text.length
      val boundary = i + 1 == text.length ||
        (text(i + 1).isWhitespace || text(i + 1) == '\u00a0' || text(i + 1) == '\u202f')
      if depth == 0 && boundary && (c == '?' || c == '!' || c == '.' && !decimal && !abbreviation) then
        val sentence = text.substring(start, i + 1).trim
        if sentence.nonEmpty then out += sentence
        start = i + 1
    out.result()
