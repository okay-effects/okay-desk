package okay.desk.bench

import okay.llm.qwen.Safetensors

/** THE WEIGHTS FOR THE PAGE: the cut model's file split into safetensors files of at most 60 MB, whole tensors in the
 * file's order (the embedding first, then the layers) — a page reads them one by one as one (`Tensors.Many`), and
 * never holds the whole twice; manifest.json lists them. `sbt "bench/runMain okay.desk.bench.SplitWeights <model dir>
 * <out dir>"`; scripts/publish.sh runs it. */
object SplitWeights:
  def main(args: Array[String]): Unit =
    val src = java.nio.file.Path.of(args(0)); val out = java.nio.file.Path.of(args(1))
    java.nio.file.Files.createDirectories(out)
    val st = Safetensors(java.nio.file.Files.readAllBytes(src.resolve("model.safetensors")))
    val files = Safetensors.split(st, 60 * 1048576)
    val names = files.indices.map(i => f"weights-$i%02d.safetensors")
    files.zip(names).foreach((bytes, name) => java.nio.file.Files.write(out.resolve(name), bytes))
    java.nio.file.Files.writeString(out.resolve("manifest.json"), names.map(n => "\"" + n + "\"").mkString("{\"files\": [", ", ", "]}\n"))
    println(s"${files.size} files, ${files.map(_.length / 1048576).mkString(", ")} MB, at $out")
