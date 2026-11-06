package okay.desk.bench

/** A SET OF THE MAP SILENCED as a model in the cache: `sbt "bench/runMain okay.desk.bench.Silence free"` (free,
 * free+near, free+ru, free+near+ru — StudentByMap's sets) → `local/Qwen3-0.6B-4bit-cut19-no-<set>` */
object Silence:
  def main(args: Array[String]): Unit =
    val repo = args.lift(1).getOrElse("local/Qwen3-0.6B-4bit-cut19")
    val set = args(0)
    val parts = StudentByMap.sets.find(_._1 == set).getOrElse(sys.error(s"no set $set"))._2
    println(Ablate.silence(repo, parts, "no-" + set.replace("+", "-")))
