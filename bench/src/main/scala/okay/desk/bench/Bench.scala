package okay.desk.bench

/** the repository's root: a forked bench runs in `bench/`, the files it names are the project's */
object Bench:
  val root: java.nio.file.Path =
    val here = java.nio.file.Path.of("").toAbsolutePath
    if java.nio.file.Files.exists(here.resolve("web")) then here else here.getParent
