package okay.desk.bench

import okay.desk.know.{FactReader, Relations}
import okay.desk.skill.Read
import okay.llm.compute.OnMlx
import java.nio.file.{Files, Path}

/** Inspect confidence and copied arguments on documents without retraining. */
object RelationsCheck:
  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val classes = FactReader.parse(Files.readString(Path.of(home + "/.cache/okay-desk/facts-readers-small.json")))
      .fold(sys.error, identity)
    val kinds = FactReader.Kinds.dropRight(1) ++ Relations.kinds
    val file = Path.of(sys.env.getOrElse("RELATIONS_FILE", home + "/.cache/okay-desk/relations-readers-small.json"))
    val reader = Relations.parse(Files.readString(file)).fold(sys.error, identity)
    val on = ComputeReading(dir, 12, OnMlx())
    Files.readString(Path.of(args(0))).linesIterator.filter(_.nonEmpty).foreach { text =>
      Read.run(FactReader.features[Read](text), on).foreach { f =>
        println(s"$text => ${FactReader.combined(reader, classes, "check", text, f)}")
        val global = FactReader.normalize(f.last, reader.parameters.mean, reader.parameters.std)
        def norm(x: Array[Float]) = FactReader.normalize(x, reader.parameters.mean, reader.parameters.std)
        val scores = reader.kind.scores(FactReader.kindFeatures(global, f.states.map(norm)))
        println(scores.zip(kinds).sortBy(-_._1).take(3).toVector)
        println(f.words.zip(f.states).map { (w, state) =>
          val tag = reader.roles.scores(FactReader.roleFeatures(norm(state), global))
          w.text -> FactReader.Roles(tag.indices.maxBy(tag(_)))
        })
      }
    }
