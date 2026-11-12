package okay.desk.statement

import Reader.{Doubt, Reading}

/**
 * A READING IN WORDS (specs/formalization.md §8, points 2 and 5): the statement shown sentence by sentence in the
 * text's own words — the last witness of a silent fault is the person — and, where no answer is shown, the doubt
 * said about the sentence it is in, never "please rephrase".
 */
object Said:
  /** the marks of a reading that gives no answer: a check of what was said takes these for none */
  val noAnswer: Vector[String] = Vector("so I do not answer", "поэтому не отвечаю",
    "could not write the task down", "не удалось записать задачу")

  /** what each sentence was read as, a line a sentence that says anything */
  def lines(text: String, s: Statement, ru: Boolean): Vector[String] =
    def w(en: String, russian: String): String = if ru then russian else en
    // an attribute is named by the first of its values: the text has no other word for it
    val lists = s.frames.filter(_.con == Con.Attribute).map(_.args.flatMap(_.span).map(_.in(text)).take(2)
      .mkString("", ", ", ", …"))
    def arg(a: Arg): String = a match
      case Arg.Mention(at, _, denied) => (if denied then w("not ", "не ") else "") + at.in(text)
      case Arg.Pick(i) => "[" + lists.lift(i).getOrElse("?") + "]"
      case other => other.span.fold("?")(_.in(text))
    def frame(f: Frame): String =
      val a = f.args.map(arg)
      def at(i: Int): String = a.lift(i).getOrElse("?")
      f.con match
        case Con.Attribute => w("a list: ", "список: ") + a.mkString(", ")
        case Con.Same => s"${at(0)} = ${at(1)}"
        case Con.Differ => s"${at(0)} ≠ ${at(1)}"
        case Con.Either => s"${at(0)} = ${at(1)} ${w("or", "или")} ${at(2)}"
        // an order is said by its sign, whatever the text orders by — places, time, height: "<" is the lesser
        case Con.Before => s"${at(0)} < ${at(1)}"
        case Con.After => s"${at(0)} > ${at(1)}"
        case Con.JustBefore => s"${at(0)} = ${at(1)} − 1"
        case Con.JustAfter => s"${at(0)} = ${at(1)} + 1"
        case Con.Beside => s"${at(0)} ${w("next to", "рядом с:")} ${at(1)}"
        case Con.At => s"${at(0)} = ${at(1)}"
        case Con.Middle => s"${at(0)} ${w("in the middle", "посередине")}"
        case Con.NotAt => s"${at(0)} ≠ ${at(1)}"
        case Con.Between => s"|${at(0)} − ${at(1)}| = ${at(2)} + 1"
        case Con.Ask => w("asked: ", "вопрос: ") + a.mkString(" — ")
        case Con.First => w("asked: the least", "вопрос: наименьший")
        case Con.Last => w("asked: the greatest", "вопрос: наибольший")
        case Con.Whether => w("asked: is it so", "вопрос: верно ли это")
        case other => other.toString + a.map(" " + _).mkString
    s.sentences.filter(_.frames.nonEmpty).map(x => s"«${x.at.in(text).trim}» → ${x.frames.map(frame).mkString("; ")}")

  /** what the desk says of a reading, and under it what was read: the answer in the text's names, or the doubt */
  def words(text: String, r: Reading, ru: Boolean): (String, Option[String]) =
    def w(en: String, russian: String): String = if ru then russian else en
    // the sentences the exact side decided: the model hesitated, and one reading alone gave the puzzle one answer
    val decided = r.settled.map(at => w(s"The solver decided how to read «${at.in(text).trim}»: of the readings the " +
      "model hesitated between, only this one gives the puzzle a single answer.",
      s"Как читать «${at.in(text).trim}», решил решатель: из прочтений, между которыми колебалась модель, " +
      "единственный ответ задаче даёт только это."))
    val read = r.statement.map(s => (decided ++ (w("Read as:", "Прочитано так:") +: lines(text, s, ru))).mkString("\n"))
    def quote(at: Span): String = s"«${at.in(text).trim}»"
    val said = r.doubt match
      case None => (for s <- r.statement; result <- r.result yield Reader.answer(text, s, result, ru))
        .getOrElse(w("could not write the task down", "не удалось записать задачу"))
      case Some(Doubt.Unfamiliar(at, words)) =>
        w(s"I was not taught this wording, so I do not answer: ${quote(at)} (new to me: ${words.mkString(", ")}).",
          s"Такой формулировке меня не учили, поэтому не отвечаю: ${quote(at)} (незнакомо: ${words.mkString(", ")}).")
      case Some(Doubt.Unsure(at)) =>
        w(s"I am not sure how to read this sentence, so I do not answer: ${quote(at)}.",
          s"Не уверен, как читать это предложение, поэтому не отвечаю: ${quote(at)}.")
      case Some(Doubt.NoStatement(why)) =>
        w(s"I could not write the task down: $why.", s"Мне не удалось записать задачу: $why.")
    (said, read)
