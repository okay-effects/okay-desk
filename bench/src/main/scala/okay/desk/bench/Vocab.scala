package okay.desk.bench

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import mlx.{Mlx, Scope}
import mlx.Mlx.*
import mlx.llm.{Chat, Models}
import okay.effects.json.Json
import okay.desk.Prompt
import okay.desk.skill.Pairs

/**
 * A SMALLER VOCABULARY (specs/data.md §19): the tokens the desk's languages use — met in English and Russian prose
 * (`VocabCorpus`), the desk's prompts and programs (`Pairs`), its sample — and every single character of those
 * languages and all 256 bytes (any text still encodes), each kept token's merge parents too (BPE reaches a token only
 * through its merge); the rest dropped. Ids renumbered (the kept in their old order, the special after), the
 * embedding's rows taken whole (4-bit rows quantize along the row: the same numbers), tokenizer.json, vocab.json,
 * merges.txt, tokenizer_config.json and config.json rewritten. A text whose tokens were all kept encodes to the same
 * tokens renumbered — the same states, exactly (VocabSuite). Written as `local/<name>-vocab`.
 * `sbt "bench/runMain okay.desk.bench.Vocab local/Qwen3-0.6B-4bit-cut19"`.
 */
object Vocab:
  /** GPT-2's byte characters: byte → char */
  private val byteChar: Array[Char] =
    val keep = (33 to 126) ++ (161 to 172) ++ (174 to 255)
    val a = new Array[Char](256)
    var n = 0
    for b <- 0 until 256 do
      if keep.contains(b) then a(b) = b.toChar
      else { a(b) = (256 + n).toChar; n += 1 }
    a
  /** a single character of the desk's languages: Latin, Cyrillic, digits, punctuation (its token: its bytes) */
  private def ours(c: Char): Boolean =
    c < 0x80 || (c >= 0x400 && c <= 0x4FF) || "«»—–…№€£°±×÷‘’“”•".contains(c) || (c >= 0xA0 && c <= 0xFF)
  private def asTokenText(s: String): String =
    s.getBytes("UTF-8").map(b => byteChar(b & 0xff)).mkString

  def texts(chat: Chat): Vector[Array[Int]] =
    val prose = Files.list(VocabCorpus.dir).iterator.asScala.filter(_.getFileName.toString.startsWith("corpus-"))
      .map(Files.readString).toVector
    def msg(role: String, content: String) = Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))
    val pairs = Pairs.many(42, 3000).map(p => chat.prompt(Vector(msg("system", Prompt.system(p.tables)),
      msg("user", p.question)), Nil, Map("enable_thinking" -> false)) ++ chat.tokenizer.encode(
      s"```json\n${p.program}\n```<|im_end|>"))
    // English as people write it: the okay-* projects' READMEs and specs (a few MB)
    val home = Path.of(sys.props("user.home"), "work", "my")
    val docs = Files.list(home).iterator.asScala.filter(d => d.getFileName.toString.startsWith("okay-") &&
      Files.isDirectory(d) && !d.getFileName.toString.contains("-wt-")).toVector.flatMap { d =>
      (Seq(d.resolve("README.md")) ++ (if Files.isDirectory(d.resolve("specs")) then
        Files.list(d.resolve("specs")).iterator.asScala.filter(_.toString.endsWith(".md")).toSeq else Nil))
        .filter(Files.isRegularFile(_))
    }.map(Files.readString).filter(_.length < 200000)
    prose.map(chat.tokenizer.encode) ++ docs.map(chat.tokenizer.encode) ++ pairs

  def main(args: Array[String]): Unit =
    val repo = args.headOption.getOrElse("local/Qwen3-0.6B-4bit-cut19")
    val src = Models.snapshot(repo).getOrElse(sys.error(s"$repo not in the cache"))
    val chat = Chat.load(src)
    val tj = Json.fromText(Files.readString(src.resolve("tokenizer.json")))
    val vocab = tj("model")("vocab").record.map((k, v) => k -> v.int).toMap
    val byId = vocab.map(_.swap)
    val merges = tj("model")("merges").list.map {
      case Json.Arr(Vector(a, b)) => (a.string, b.string)
      case s => val Array(a, b) = s.string.split(" ", 2); (a, b)
    }
    val parents = merges.map((a, b) => (a + b) -> (a, b)).toMap
    val added = tj("added_tokens").list.map(t => t("id").int -> t("content").string).sortBy(_._1)
    val addedIds = added.map(_._1).toSet
    // the tokens met
    val used = texts(chat).flatten.filterNot(addedIds).map(byId).toSet
    // every byte, every single character of the languages
    val singles = (0 until 256).map(b => byteChar(b).toString).filter(vocab.contains) ++
      ((0 until 0x500).map(_.toChar) ++ "«»—–…№€£°±×÷‘’“”•").filter(ours).map(c => asTokenText(c.toString))
        .filter(vocab.contains)
    // the merge closure: a token's parents, theirs, …
    val keep = scala.collection.mutable.Set.empty[String]
    def add(t: String): Unit = if keep.add(t) then parents.get(t).foreach((a, b) => { add(a); add(b) })
    (used ++ singles).foreach(add)
    val kept = keep.toVector.sortBy(vocab)
    val newId = kept.zipWithIndex.toMap
    val addedNew = added.zipWithIndex.map { case ((old, _), k) => old -> (kept.size + k) }.toMap
    def remap(old: Int): Int = addedNew.getOrElse(old, newId(byId(old)))
    val keptMerges = merges.filter((a, b) => keep(a) && keep(b) && keep(a + b))
    println(f"$repo: ${vocab.size} tokens → ${kept.size} kept (${used.size} met, ${singles.size} singles, the rest " +
      f"their parents) + ${added.size} special; merges ${merges.size} → ${keptMerges.size}")
    // the new repository
    val name = s"$repo-vocab"
    val dst = Models.hubCache.resolve("models--" + name.replace("/", "--")).resolve("snapshots").resolve("main")
    Files.createDirectories(dst)
    val model2 = tj("model").record.map {
      case ("vocab", _) => "vocab" -> Json.Obj(kept.map(t => t -> Json.Num(newId(t))))
      case ("merges", _) => "merges" -> Json.Arr(keptMerges.map((a, b) => Json.Arr(Vector(Json.Str(a), Json.Str(b)))))
      case kv => kv
    }
    val tj2 = Json.Obj(tj.record.map {
      case ("model", _) => "model" -> Json.Obj(model2)
      case ("added_tokens", a) => "added_tokens" -> Json.Arr(a.list.map(t => Json.Obj(t.record.map {
        case ("id", i) => "id" -> Json.Num(remap(i.int))
        case kv => kv
      })))
      case ("post_processor", p) if p != Json.Null && p("type").string != "ByteLevel" =>
        sys.error(s"a post-processor that may carry ids: not handled: $p")
      case kv => kv
    })
    Files.writeString(dst.resolve("tokenizer.json"), tj2.render)
    Files.writeString(dst.resolve("vocab.json"), Json.Obj(kept.map(t => t -> Json.Num(newId(t)))).render)
    Files.writeString(dst.resolve("merges.txt"), "#version: 0.2\n" + keptMerges.map((a, b) => s"$a $b").mkString("\n"))
    val tc = Json.fromText(Files.readString(src.resolve("tokenizer_config.json")))
    val tc2 = Json.Obj(tc.record.map {
      case ("added_tokens_decoder", d) => "added_tokens_decoder" -> Json.Obj(d.record.map((k, v) =>
        remap(k.toInt).toString -> v))
      case kv => kv
    })
    Files.writeString(dst.resolve("tokenizer_config.json"), tc2.render)
    val cfg = Json.fromText(Files.readString(src.resolve("config.json")))
    val total = kept.size + added.size
    val cfg2 = Json.Obj(cfg.record.map {
      case ("vocab_size", _) => "vocab_size" -> Json.Num(total)
      case (k @ ("bos_token_id" | "eos_token_id"), v) if v != Json.Null => k -> Json.Num(remap(v.int))
      case kv => kv
    })
    Files.writeString(dst.resolve("config.json"), cfg2.render)
    for f <- Seq("added_tokens.json", "special_tokens_map.json", "generation_config.json", "chat_template.jinja")
        if Files.exists(src.resolve(f)) do
      if f == "added_tokens.json" then
        val at = Json.fromText(Files.readString(src.resolve(f)))
        Files.writeString(dst.resolve(f), Json.Obj(at.record.map((k, v) => k -> Json.Num(remap(v.int)))).render)
      else Files.copy(src.resolve(f), dst.resolve(f), StandardCopyOption.REPLACE_EXISTING)
    // the embedding's rows, in the new order (the special tokens' rows after)
    Mlx.init()
    val s = new Scope
    given Scope = s
    try
      val all = loadSafetensors(src.resolve("model.safetensors").toString)
      val rows = ints((kept.map(vocab) ++ added.map(_._1)).toArray, total)
      val out = all.map { (n, a) =>
        if n.startsWith("model.embed_tokens.") then n -> take(a, rows, 0).eval() else n -> a
      }
      saveSafetensors(dst.resolve("model.safetensors").toString, out.toSeq.sortBy(_._1))
      Files.writeString(dst.resolve("vocab-map.tsv"), (kept.map(t => s"${vocab(t)}\t${newId(t)}") ++
        added.map((o, _) => s"$o\t${addedNew(o)}")).mkString("\n") + "\n")
      println(f"$name: ${total} tokens, ${Files.size(dst.resolve("model.safetensors")) / 1048576.0}%.0f MB " +
        f"(was ${Files.size(src.resolve("model.safetensors")) / 1048576.0}%.0f) at $dst")
    finally s.close()
