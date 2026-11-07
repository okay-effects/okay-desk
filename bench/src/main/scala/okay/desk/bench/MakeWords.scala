package okay.desk.bench

import mlx.llm.{Chat, Model, Models, Session}
import okay.desk.*
import okay.desk.skill.Words

/**
 * THE DATA'S OWN WORDS MADE (specs/data.md §29): a dataset's names — its tables, its columns, the distinct values
 * of its text columns (not codes) — said in Russian by a model that generates, once, offline; kept beside the data
 * as `Words` (JSON). Checked as it is made: an answer per name or the name is left out, never two values with one
 * stem (an ambiguous word would be a wrong fact), never the name itself back. The readers read it as exact facts.
 *
 * `sbt "bench/runMain okay.desk.bench.MakeWords <data.csv> <out.json>"`; DESK_WORDS_MODEL (Qwen3.5-4B).
 */
object MakeWords:
  def main(args: Array[String]): Unit =
    val Array(in, out) = args: @unchecked
    val t = Table.parse(in, String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(in)), "UTF-8"))
      .fold(e => sys.error(e), identity)
    val repo = sys.env.getOrElse("DESK_WORDS_MODEL", "mlx-community/Qwen3.5-4B-MLX-4bit")
    val dir = Models.snapshot(repo).getOrElse(sys.error(s"$repo: not in the cache"))
    val chat = Chat.load(dir); val model = Model.load(dir, mapped = true)
    def msg(role: String, content: String) = okay.effects.json.Json.obj("role" -> okay.effects.json.Json.Str(role),
      "content" -> okay.effects.json.Json.Str(content))
    def complete(user: String, max: Int): String =
      val prompt = chat.prompt(Vector(msg("user", user)), Nil, Map("enable_thinking" -> false))
      val s = Session(model, prompt, max, chat.stops)
      try chat.tokenizer.decode(Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector) finally s.close()
    /** names into Russian, a batch a prompt: "name => перевод" lines, matched back by the name */
    def russian(kind: String, names: Vector[String]): Map[String, String] =
      names.grouped(25).flatMap { batch =>
        val answer = complete(s"Translate each $kind into Russian, the nominative case, as a Russian speaker would " +
          "write it in a question (the usual abbreviation if there is one). Answer with exactly one line per name, in the same order, formatted as " +
          "`name => перевод`, nothing else.\n\n" + batch.mkString("\n"), 40 * batch.size)
        if sys.env.contains("DESK_WORDS_SHOW") then println(answer)
        // the model translates the left side too ("Афганистан => Афганистан"): the lines matched by their order when
        // there is one per name, else by the name written back
        val lines = answer.split("\n").toVector.map(_.split("=>", 2)).collect { case Array(a, b) =>
          (a.trim.stripPrefix("`").trim, b.trim.stripSuffix("`").trim.toLowerCase) }
        if lines.size == batch.size then batch.zip(lines.map(_._2))
        else lines.flatMap((a, b) => batch.find(_ == a).map(_ -> b))
      }.toMap
    val textCols = t.columns.filter { c =>
      val i = t.columns.indexOf(c); val cells = t.rows.map(_(i)).filter(_.text.nonEmpty); val vs = cells.map(_.text).distinct
      vs.nonEmpty && vs.size <= 1000 && cells.forall(_.num.isEmpty) && !vs.forall(v => v.length <= 3 && v.forall(_.isUpper))
    }
    val values = textCols.flatMap(c => t.rows.map(_(t.columns.indexOf(c)).text).filter(_.nonEmpty)).distinct
    println(s"${t.name}: ${t.columns.size} columns, text columns ${textCols.mkString(", ")}: ${values.size} values")
    val t0 = System.nanoTime()
    val tableWords = russian("name of a data table (what it measures)", Vector(t.name))
    val columnWords = russian("column name", t.columns)
    val valueWords = russian("name", values)
    // checked: not the name back, Cyrillic, and no stem shared by two values (an ambiguous word is no fact)
    def stem(w: String) = w.split(" ").map(x => if x.length <= 3 then x else x.dropRight(if x.length > 4 then 2 else 1)).mkString(" ")
    def ok(k: String, w: String) = w.nonEmpty && !w.equalsIgnoreCase(k) && w.exists(c => c >= 'а' && c <= 'я')
    val clean = valueWords.filter(ok)
    val byStem = clean.groupBy((_, w) => stem(w))
    val unique = clean.filter((_, w) => byStem(stem(w)).size == 1)
    // a name said twice ("ввп (ввп)"): the part before the parentheses
    def once(w: String) = w.replaceAll("^(.+?) \\((.+)\\)$", "$1")
    val words = Words(tableWords.view.mapValues(once).toMap.filter(ok), columnWords.filter(ok), unique)
    java.nio.file.Files.writeString(java.nio.file.Path.of(out), words.json)
    println(f"words: ${words.tables.size} table, ${words.columns.size} columns, ${unique.size} of ${values.size} values " +
      f"(${valueWords.size - clean.size} not translated, ${clean.size - unique.size} with a shared stem) in " +
      f"${(System.nanoTime() - t0) / 1e9}%.0f s → $out")
    model.close()
