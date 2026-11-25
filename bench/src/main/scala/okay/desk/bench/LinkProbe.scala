package okay.desk.bench

import mlx.llm.{Chat, Model, Models}

/**
 * VALUES LINKED BY MEANING, PROBED (frames.md §5.16: the largest class of the 1004's errors — «роман» for novel,
 * «терраса» for terrace): does the reader's own model already put a word and its translation near each other? Each
 * word embedded alone ("Word: …", the mean of its tokens' states at a layer), its column's values the same way; the
 * nearest value by cosine against the right one, at several layers. No second model if the 0.6B's states suffice.
 * `sbt "bench/runMain okay.desk.bench.LinkProbe"`
 */
object LinkProbe:
  private val columns = Map(
    "genre" -> Vector("novel", "poetry", "sci-fi", "history", "biography", "fantasy"),
    "table_area" -> Vector("hall", "terrace", "takeaway", "bar"),
    "cause" -> Vector("school", "animals", "health", "disaster", "hospital"),
    "city" -> Vector("Kyiv", "Lviv", "Warsaw", "Odesa", "Krakow", "Gdansk"),
    "category" -> Vector("groceries", "transport", "restaurants", "utilities", "health", "entertainment"),
    "status" -> Vector("paid", "unpaid", "active", "cancelled", "closed", "open"),
    "shift" -> Vector("morning", "evening", "night"))
  private val cases = Vector(
    "роман" -> ("genre", "novel"), "поэзия" -> ("genre", "poetry"), "poezja" -> ("genre", "poetry"),
    "фантастика" -> ("genre", "fantasy"), "біографія" -> ("genre", "biography"),
    "терраса" -> ("table_area", "terrace"), "на террасе" -> ("table_area", "terrace"),
    "на вынос" -> ("table_area", "takeaway"), "зал" -> ("table_area", "hall"), "sala" -> ("table_area", "hall"),
    "тварин" -> ("cause", "animals"), "животные" -> ("cause", "animals"), "szkoły" -> ("cause", "school"),
    "школа" -> ("cause", "school"), "лікарня" -> ("cause", "hospital"),
    "Львова" -> ("city", "Lviv"), "Варшаві" -> ("city", "Warsaw"), "Kijowie" -> ("city", "Kyiv"),
    "Одессы" -> ("city", "Odesa"), "Кракове" -> ("city", "Krakow"),
    "продукты" -> ("category", "groceries"), "рестораны" -> ("category", "restaurants"),
    "rozrywka" -> ("category", "entertainment"), "транспорт" -> ("category", "transport"),
    "коммунальные" -> ("category", "utilities"),
    "оплаченные" -> ("status", "paid"), "неоплачені" -> ("status", "unpaid"), "активні" -> ("status", "active"),
    "закрытые" -> ("status", "closed"), "anulowane" -> ("status", "cancelled"),
    "ночные" -> ("shift", "night"), "ранкові" -> ("shift", "morning"), "wieczorne" -> ("shift", "evening"))
  private val layers = Vector(4, 8, 12, 16, 20, 24, 28)

  def main(args: Array[String]): Unit =
    val repo = sys.env.getOrElse("FRAME_MODEL", "mlx-community/Qwen3-0.6B-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
    sys.env.get("FRAME_ADAPTER").foreach(f => mlx.llm.Lora.attach(model.asInstanceOf[mlx.llm.Adaptable], f))
    val reading = MlxReading(chat, model, 1)
    val tok = chat.tokenizer
    /** a word's mean state at each layer */
    def embed(word: String): Map[Int, Array[Float]] =
      val prefix = tok.encode("Word:"); val ids = prefix ++ tok.encode(" " + word)
      val at = (prefix.length until ids.length).toVector
      reading.statesAt(ids, layers, at).view.mapValues { rows =>
        val d = rows.head.length; val m = new Array[Float](d)
        rows.foreach(r => (0 until d).foreach(k => m(k) += r(k) / rows.size)); m }.toMap
    def cos(a: Array[Float], b: Array[Float]): Double =
      var dot = 0.0; var na = 0.0; var nb = 0.0
      for k <- a.indices do { dot += a(k) * b(k); na += a(k) * a(k); nb += b(k) * b(k) }
      dot / math.sqrt(na * nb + 1e-12)
    val values = columns.values.flatten.toVector.distinct.map(v => v -> embed(v)).toMap
    val words = cases.map((w, _) => w -> embed(w)).toMap
    for l <- layers do
      val hits = cases.map { case (w, (col, want)) =>
        val best = columns(col).maxBy(v => cos(words(w)(l), values(v)(l)))
        (w, want, best) }
      val right = hits.count((_, want, best) => want == best)
      println(f"layer $l%2d: $right of ${cases.size} — wrong: " +
        hits.filter((_, w, b) => w != b).map((w, want, best) => s"$w→$best (not $want)").mkString(", "))
    // THE THRESHOLD: layers 4 and 20 together; a link taken only where the best value is near enough and clear of the
    // second — the words of a question that name no value (negatives) must take none
    val negatives = Vector("сколько" -> "category", "заказов" -> "table_area", "самый" -> "city", "всего" -> "status",
      "покажи" -> "genre", "сумма" -> "category", "how" -> "city", "orders" -> "status", "ile" -> "cause",
      "середня" -> "genre", "pokaż" -> "shift", "больше" -> "status")
    val negEmb = negatives.map((w, _) => w -> embed(w)).toMap
    def score(e: Map[Int, Array[Float]], v: String) = cos(e(4), values(v)(4)) + cos(e(20), values(v)(20))
    def ranked(e: Map[Int, Array[Float]], col: String) = columns(col).map(v => v -> score(e, v)).sortBy(-_._2)
    val pos = cases.map { case (w, (col, want)) => val r = ranked(words(w), col); (r.head._1 == want, r.head._2,
      r.head._2 - r(1)._2) }
    val neg = negatives.map((w, col) => { val r = ranked(negEmb(w), col); (false, r.head._2, r.head._2 - r(1)._2) })
    for (minScore, minGap) <- Vector((0.0, 0.0), (1.0, 0.0), (1.1, 0.0), (1.2, 0.0), (1.0, 0.05), (1.1, 0.05),
        (1.2, 0.05), (1.1, 0.1), (1.2, 0.1)) do
      def taken(x: (Boolean, Double, Double)) = x._2 >= minScore && x._3 >= minGap
      val t = pos.filter(taken); val nt = neg.filter(taken)
      println(f"score ≥ $minScore%.2f, gap ≥ $minGap%.2f: linked ${t.size} of ${pos.size}, right ${t.count(_._1)}; " +
        f"negatives linked ${nt.size} of ${neg.size}")
    model.close()
