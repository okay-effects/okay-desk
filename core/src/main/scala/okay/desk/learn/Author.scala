package okay.desk.learn

import okay.desk.Table
import okay.desk.calc.*
import okay.desk.organ.{Cue, Organ, Organs, Template}
import okay.desk.skill.Read
import okay.scala3.*

/**
 * THE MODEL AUTHORS AN ORGAN (specs/organs.md §3 v1, BACKLOG B 12): where the readers kept failing on a class of
 * questions, the model is asked — through `Read`, any model with logits — for a CUE: the words of the question that
 * mean the constructor at the hole, and the words whose presence would make it mean something else, as a few
 * lines of JSON. The cue is data; it serves only after two checks the model does not do: the LAW (the block equals
 * the interpreter — a property of the compiler, but checked on the cue's own words) and the ORACLE (the cue must
 * fire on its question and on no pair of the same hole whose constructor differs; the induced organ is what it is
 * measured against). A cue that fails is kept as a record of what the model proposed, never as a part.
 */
object Author:
  /** what the model proposed and what the checks said */
  final case class Proposal(question: String, path: String, constructor: String, raw: String, cue: Option[Cue], accepted: Boolean, why: String)

  /** the prompt: the question, the hole's meaning, the format — and the words of the question to choose from */
  def prompt(question: String, path: String, constructor: String, candidates: Vector[String], words: Vector[String]): String =
    s"""A rule that reads a question into a program. The question: "$question". Its words, stemmed: ${words.mkString(", ")}.
       |At the place "$path" the program's constructor is "$constructor" (the alternatives were: ${candidates.mkString(", ")}).
       |Write ONE rule as JSON on one line: {"words": [stems of this question that mean "$constructor" here, as few as possible],
       |"unless": [stems whose presence would make it mean another alternative, or []]}. Only stems from the list. JSON only.
       |""".stripMargin

  /** greedy decoding by `Read.logits`, token by token, until a stop or `max` tokens */
  def generate[R[_]: Has[Read]](ids: Array[Int], max: Int, stops: Set[Int]): Array[Int] ! R =
    def loop(cur: Array[Int], n: Int): Array[Int] ! R =
      if n >= max then pure(cur.drop(ids.length))
      else Read.logits[R](cur).flatMap { l =>
        var best = 0; var i = 1
        while i < l.length do { if l(i) > l(best) then best = i; i += 1 }
        if stops.contains(best) then pure(cur.drop(ids.length)) else loop(cur :+ best, n + 1)
      }
    loop(ids, 0)

  /** the cue parsed from the model's text: the first JSON object on it */
  def parse(text: String, path: String, constructor: String, vocabulary: Set[String]): Either[String, Cue] =
    import okay.effects.json.Json
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if start < 0 || end <= start then Left("no JSON object")
    else try Json.fromText(text.substring(start, end + 1)) match
      case Json.Obj(fs) =>
        val m = fs.toMap
        def strs(k: String) = m.get(k) match { case Some(Json.Arr(xs)) => xs.collect { case Json.Str(s) => s.toLowerCase.trim }.toSet; case _ => Set.empty[String] }
        val words = strs("words").filter(vocabulary); val unless = strs("unless").filter(vocabulary)
        if words.isEmpty then Left("no words of the question") else Right(Cue(words, unless, path, constructor, sure = true))
      case _ => Left("not an object")
    catch case e: Exception => Left(e.getMessage)

  /** THE ORACLE: the cue fires on its own question and on no pair of the same path with another constructor */
  def check(cue: Cue, own: Vector[String], others: Vector[(Vector[String], Template)]): Either[String, Unit] =
    def fires(ws: Vector[String]) = cue.words.subsetOf(ws.toSet) && cue.unless.forall(w => !ws.contains(w))
    if !fires(own) then Left("does not fire on its own question")
    else others.collectFirst { case (ws, t) if fires(ws) && t.parts.get(cue.path).exists(_ != cue.constructor) => ws } match
      case Some(ws) => Left(s"fires on a pair meaning ${others.find(_._1 == ws).flatMap(_._2.parts.get(cue.path)).getOrElse("?")}: ${ws.mkString(" ")}")
      case None => Right(())

  /** THE LAW on the cue's words: the organ of this one cue, the block equals the interpreter on the question */
  def lawful(cue: Cue, own: Vector[String]): Boolean =
    val o = Organ("proposal", (cue.words ++ cue.unless).toVector.sorted, Vector(cue))
    o.forward(own) == o.interpret(own) && o.forward(own).exists(_.parts.get(cue.path).contains(cue.constructor))

  /** one proposal: asked, parsed, checked by the law and the oracle */
  def propose[R[_]: Has[Read]](tables: Map[String, Table], question: String, lang: Language, path: String, constructor: String,
      candidates: Vector[String], others: Vector[(Vector[String], Template)], maxTokens: Int = 80): Proposal ! R =
    val words = Organ.words(question, lang)
    for
      ids <- Read.prompt[R](okay.desk.Prompt.system(tables), prompt(question, path, constructor, candidates, words))
      out <- generate[R](ids, maxTokens, Set.empty)
      text <- Read.decode[R](out.toIndexedSeq)
    yield
      val raw = text.trim
      parse(raw, path, constructor, words.toSet) match
        case Left(why) => Proposal(question, path, constructor, raw, None, accepted = false, why)
        case Right(cue) =>
          if !lawful(cue, words) then Proposal(question, path, constructor, raw, Some(cue), accepted = false, "the law failed on its own words")
          else check(cue, words, others) match
            case Left(why) => Proposal(question, path, constructor, raw, Some(cue), accepted = false, why)
            case Right(()) => Proposal(question, path, constructor, raw, Some(cue), accepted = true, "accepted")

  /** the accepted proposals as an organ, with the induced one as the reference it is measured against */
  def organ(proposals: Vector[Proposal], name: String = "authored"): Organ =
    val cues = proposals.flatMap(p => p.cue.filter(_ => p.accepted)).distinct
    Organ(name, cues.flatMap(c => c.words ++ c.unless).distinct.sorted, cues)
  val _ = Organs
