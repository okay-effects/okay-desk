package okay.desk.bench

import okay.llm.qwen.{Safetensors, Tokenizer, Vocab}

/** THE WEIGHTS FOR THE PAGE: the cut model's file split into safetensors files of at most 60 MB — the layers, whole
 * tensors in order — and THE EMBEDDING IN BLOCKS BY LANGUAGE (okay-llm `Vocab`): embed-<block>.safetensors for
 * common, latin-en, latin-rest, cyrillic, cjk, other, with embed-index.bin (a byte a token: its block's number) so a
 * page fetches a block when a token of it appears. manifest.json lists them all. `sbt "bench/runMain
 * okay.desk.bench.SplitWeights <model dir> <out dir> [words]"`; scripts/publish.sh runs it. */
object SplitWeights:
  def main(args: Array[String]): Unit =
    val src = java.nio.file.Path.of(args(0)); val out = java.nio.file.Path.of(args(1))
    val words = java.nio.file.Path.of(args.lift(2).getOrElse("/usr/share/dict/words"))
    java.nio.file.Files.createDirectories(out)
    val st = Safetensors(java.nio.file.Files.readAllBytes(src.resolve("model.safetensors")))
    val tok = Tokenizer.fromJson(okay.effects.json.Json.fromText(java.nio.file.Files.readString(src.resolve("tokenizer.json"))))
    // the layers, without the embedding
    val layers = Safetensors.split(st, 60 * 1048576, skip = _.startsWith("model.embed_tokens."))
    val names = layers.indices.map(i => f"weights-$i%02d.safetensors")
    layers.zip(names).foreach((bytes, name) => java.nio.file.Files.write(out.resolve(name), bytes))
    // the embedding by block
    // the English block: the word list, and the desk's own prompt (its words are English, and not all in a list: "json")
    val own = Iterator(okay.desk.Prompt.system(okay.desk.Sample.tables)) ++ okay.desk.Sample.questions.iterator.map(_.en)
    val of = Vocab.of(tok, Vocab.uses(tok, scala.io.Source.fromFile(words.toFile).getLines() ++ own))
    val byBlock = (0 until tok.size).groupBy(of).view.mapValues(_.toVector).toMap
    val blocks = Vocab.blocks.filter(byBlock.contains)
    for b <- blocks do java.nio.file.Files.write(out.resolve(s"embed-$b.safetensors"), Vocab.blockFile(st, byBlock(b)))
    java.nio.file.Files.write(out.resolve("embed-index.bin"), Array.tabulate(tok.size)(id => blocks.indexOf(of(id)).toByte))
    val manifest = "{\"files\": [" + names.map(n => "\"" + n + "\"").mkString(", ") + "], \"embedding\": {" +
      blocks.map(b => "\"" + b + "\": \"embed-" + b + ".safetensors\"").mkString(", ") + "}, \"index\": \"embed-index.bin\", " +
      "\"vocab\": " + tok.size + "}\n"
    java.nio.file.Files.writeString(out.resolve("manifest.json"), manifest)
    println(s"${layers.size} layer files (${layers.map(_.length / 1048576).mkString(", ")} MB); the embedding: " +
      blocks.map(b => s"$b ${byBlock(b).size} rows ${java.nio.file.Files.size(out.resolve(s"embed-$b.safetensors")) / 1048576} MB").mkString(", "))
