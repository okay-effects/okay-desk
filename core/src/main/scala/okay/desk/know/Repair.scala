package okay.desk.know

import okay.effects.json.Json

/** Stop an unchanged invalid tool program, while allowing different attempted repairs and successful progress. */
final case class Repair(previous: Option[(String, String)] = None, attempts: Int = 0):
  def failed(program: String, error: String): Either[String, Repair] =
    val normalized = try Json.print(Json.fromText(program)) catch case _: Exception => program.trim
    val signature = normalized -> error
    val count = if previous.contains(signature) then attempts + 1 else 1
    if count >= 3 then Left(
      "The language model repeated the same invalid program 3 times. No answer was produced. " +
        "Last tool error: " + error)
    else Right(Repair(Some(signature), count))
