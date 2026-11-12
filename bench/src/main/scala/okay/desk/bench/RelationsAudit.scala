package okay.desk.bench

import okay.desk.know.{FactReader, Relations}
import okay.desk.skill.Read
import okay.llm.compute.OnMlx
import java.nio.file.{Files, Path}

/** Recheck the saved artifact after handler changes without retraining or selecting new thresholds. */
object RelationsAudit:
  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val classes = FactReader.parse(Files.readString(Path.of(home + "/.cache/okay-desk/facts-readers-small.json")))
      .fold(sys.error, identity)
    val primary = Relations.parse(Files.readString(Path.of(home + "/.cache/okay-desk/relations-readers-small.json")))
      .fold(sys.error, identity)
    val on = ComputeReading(dir, 12, OnMlx())
    import RelationsBench.{pairs, negativeForms, uncertain}
    val testing = pairs(RelationsBench.testing ++ negativeForms(RelationsBench.testing, 2) ++
      uncertain(RelationsBench.testing, 2), 24, 13) ++
      FactReaderBench.pairs(FactReaderBench.test, 32, 3, Vector("Zeno", "South Port", "Лена"))
    val accepted = testing.flatMap { p =>
      Read.run(FactReader.features[Read](p.text), on)
        .flatMap(f => FactReader.combined(primary, classes, "test", p.text, f)).map(p -> _)
    }
    val wrong = accepted.filter((p, r) => p.kind != r.kind || p.a != r.a || p.b != r.b)
    wrong.foreach(println)
    println(s"saved artifact: accepted=${accepted.size}/${testing.size}, errors=${wrong.size}")
    require(accepted.size >= 48 && wrong.isEmpty)
    Relations.names.foreach { label =>
      val count = accepted.count(_._1.kind == label)
      println(s"$label: $count/24")
      require(count >= 3)
    }
