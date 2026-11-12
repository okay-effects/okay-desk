package okay.desk.bench

import java.nio.file.{Files, Path}
import okay.desk.know.Formal
import okay.desk.skill.NativeCompiler

/** Export the exact native compiler instructions for independent model/runtime checks. */
object SemanticPrompt:
  def main(args: Array[String]): Unit =
    Files.writeString(Path.of(args(0)), Formal.instructions + NativeCompiler.actions)
    ()
