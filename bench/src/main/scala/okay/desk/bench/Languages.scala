package okay.desk.bench

import okay.desk.calc.Language

/** THE LANGUAGE FILES on the JVM (the page fetches the same `lang/<code>.json`): loaded once each, when a question
 * asks for them (`Language.forQuestion`); DESK_LANG_DIR, else web/page/lang beside the repository's root */
object Languages:
  private val dir = sys.env.get("DESK_LANG_DIR").map(java.nio.file.Path.of(_)).getOrElse {
    val here = java.nio.file.Path.of(sys.props("user.dir")); Vector(here.resolve("web/page/lang"), here.resolve("../web/page/lang")).find(java.nio.file.Files.isDirectory(_)).getOrElse(here) }
  private val loaded = scala.collection.mutable.Map.empty[String, Option[Language]]
  def load(code: String): Option[Language] = Language.builtIn.get(code).orElse(loaded.getOrElseUpdate(code, {
    val f = dir.resolve(s"$code.json")
    if java.nio.file.Files.exists(f) then Language.parse(java.nio.file.Files.readString(f)).toOption else None }))
  def forQuestion(question: String): Vector[Language] = Language.forQuestion(question, load)
