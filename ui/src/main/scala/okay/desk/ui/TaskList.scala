package okay.desk.ui

import okay.business.scala3.*
import okay.desk.tasks.{Gold, Judge, Kind, Task, Tasks}

/**
 * THE COLLECTION OF LOGIC TASKS ON THE EXAMPLES SCREEN (specs/formalization.md §5): a class chosen, a page of its
 * tasks by title and first words. A task does not show its answer; it has two buttons. "Answer": the task alone and
 * whole, the person types an answer and it is checked. "Solve": the task is asked in the chat as a person would write
 * it (a task over data opens its tables first), and what the desk says is checked against the exact solver's answer.
 */
object TaskList:
  /** what is shown: the page (from 0), the class (none: every task), the language of the tasks; `open`: the task a
   * person is answering (its place among those shown); `tried`: the answer typed, and whether it is right;
   * `readable`: only the tasks the statement reader states (specs/formalization.md §12.2) */
  final case class View(page: Int = 0, kind: Option[Kind] = None, russian: Boolean = false, open: Option[Int] = None,
      tried: Option[(String, Boolean)] = None, readable: Boolean = false)

  /** a task the statement language states: "Solve" gives it to the statement reader */
  def stated(t: Task): Boolean = t.stated.exists(_.en.statement.problem == okay.desk.statement.Problem.Assign)
  /** the tasks a view shows */
  def of(v: View): Vector[okay.desk.tasks.Entry] = Tasks.of(v.kind).filter(e => !v.readable || stated(e.task))

  /** a task the desk is solving in the chat: its question is the entry `from` of the conversation; `result`: the
   * result of the statement it was read into, when the statement reader answered (judged by what it names) */
  final case class Solving(task: Task, russian: Boolean, from: Int, result: Option[Vector[String]] = None)

  /** a task in the list: this much of its text */
  val preview = 110

  val perPage = 10
  def pages(v: View): Int = math.max(1, (of(v).size + perPage - 1) / perPage)

  private val solvers = Map("ordered grid" -> "упорядоченная сетка", "finite constraints" -> "конечные ограничения",
    "planning" -> "планирование", "arithmetic" -> "арифметика", "tables" -> "таблицы")
  private def solver(t: Task, ru: Boolean): String = if ru then solvers.getOrElse(t.gold.solver, t.gold.solver)
    else t.gold.solver
  private def lines(t: Task, ru: Boolean): List[String] =
    t.text.in(ru).linesIterator.map(_.trim).filter(_.nonEmpty).toList
  private def about(t: Task, ru: Boolean): Part =
    val data = if t.opens.isEmpty then "" else
      s" · ${if ru then "данные" else "data"}: ${t.opens.map(_.split("/").last.takeWhile(_ != '.')).mkString(", ")}"
    Part.block("small")(Part.text(
      s"${t.kind.title.in(ru)} · ${if ru then "решатель" else "solver"}: ${solver(t, ru)}$data"))

  /** the section and its buttons: `show` gives the message that moves the view, `solve` the one that has a task
   * solved in the chat (the task, in Russian or not) */
  def section[A](v: View, show: View => A, solve: (Task, Boolean) => A): (Part, List[Button[A]]) =
    val ru = v.russian
    def word(en: String, russian: String): String = if ru then russian else en
    val all = of(v)
    val page = v.page.max(0).min(pages(v) - 1)
    val from = page * perPage
    val shown = all.slice(from, from + perPage).map(_.task).zipWithIndex.map((t, i) => (t, from + i))
    val cards = shown.toList.map { (t, at) =>
      val whole = lines(t, ru).mkString(" ")
      Part.block("task", s"${at + 1}. ${t.title.in(ru)}")(about(t, ru),
        Part.text(if whole.length <= preview then whole else whole.take(preview).trim + "…"), Part.slot(s"task-$at")) }
    val position = word(s"Page ${page + 1} of ${pages(v)} · tasks ${from + 1}–${from + shown.size} of ${all.size}",
      s"Страница ${page + 1} из ${pages(v)} · задачи ${from + 1}–${from + shown.size} из ${all.size}")
    val reads = word("Here the model writes a task down as a formal statement — it solves nothing itself — and an " +
      "exact solver answers; the reading is shown under the answer. Where it was not taught a wording, it says " +
      "which sentence it could not read.",
      "Здесь модель записывает задачу формально — сама она ничего не решает — и отвечает точный решатель; под " +
      "ответом показано, как задача прочитана. Если формулировке её не учили, она скажет, какое предложение не " +
      "смогла прочитать.")
    val part = Part.section(word("Logic tasks", "Логические задачи"))((List(
      Part.text(if v.readable then reads else word(s"The collection the desk is to solve: ${Tasks.entries.size} tasks in ${Tasks.kinds.size} " +
        "classes. Answer a task yourself and have your answer checked, or let the desk solve it in the chat: " +
        "what it says is checked against the answer of an exact solver on the task's gold statement.",
        s"Сборник задач, которые стол должен решать: ${Tasks.entries.size} задач в ${Tasks.kinds.size} классах. " +
        "Ответьте на задачу сами — ответ будет проверен, или дайте решить её столу в чате: его ответ сверяется " +
        "с ответом точного решателя на эталонной записи задачи.")),
      Part.block("choice")(Part.slot("task-kinds")),
      Part.block("choice")(Part.block("small")(Part.text(position)), Part.slot("task-pages"))) ++ cards ++ List(
      Part.block("choice")(Part.slot("task-pages-below"))))*)

    def style(chosen: Boolean): Style = if chosen then Style.Primary else Style.Plain
    val kinds = List(
      Button(s"${word("The model reads", "Читает модель")} (${Tasks.entries.count(e => stated(e.task))})",
        style(v.readable))(show(View(0, None, ru, readable = true))),
      Button(s"${word("All", "Все")} (${Tasks.entries.size})", style(v.kind.isEmpty && !v.readable))(
        show(View(0, None, ru)))) ++
      // the classes are the whole collection's: among the tasks the model reads they would only stand in the way
      (if v.readable then Nil else Tasks.kinds.toList.map((k, n) =>
        Button(s"${k.title.in(ru)} ($n)", style(v.kind.contains(k)))(show(View(0, Some(k), ru)))))
    def turn(label: String, to: Int): List[Button[A]] =
      if to < 0 || to >= pages(v) then Nil else List(Button(label)(show(View(to, v.kind, ru, readable = v.readable))))
    def paging(slot: String): List[Button[A]] =
      (turn(word("← Previous", "← Назад"), page - 1) ++ turn(word("Next →", "Вперёд →"), page + 1)).map(_.at(slot))
    val language = Button(if ru then "English" else "Русский")(show(View(page, v.kind, !ru, readable = v.readable)))
    val acts = shown.toList.flatMap { (t, at) => List(
      Button(word("Answer", "Ответить"))(show(View(page, v.kind, ru, Some(at), readable = v.readable))),
      Button(word("Solve", "Решить"), Style.Primary)(solve(t, ru))).map(_.at(s"task-$at")) }
    (part, kinds.map(_.at("task-kinds")) ++ paging("task-pages") ++ List(language.at("task-pages")) ++ acts ++
      paging("task-pages-below"))

  /** THE TASK A PERSON IS ANSWERING, alone on the screen: its whole text, the form for the answer, the check of what
   * was typed. A right answer is said as the collection writes it; a wrong one does not give the answer away. */
  def answering[A](v: View, show: View => A, solve: (Task, Boolean) => A): Option[(List[Part], List[Button[A]])] =
    val ru = v.russian
    def word(en: String, russian: String): String = if ru then russian else en
    v.open.flatMap(at => of(v).lift(at).map(e => (e.task, at))).map { (t, at) =>
      val form = Form.one(Field.text(word("Your answer", "Ваш ответ")).keyed(s"task-answer-$at")
        .hinted(word("the answer alone: a name, a number, a list", "только ответ: имя, число, список")))
      val check = v.tried.toList.map((typed, right) => Part.block("check")(Part.text(
        if right then word(s"Right ✓ — the answer: ${t.answer.en}", s"Верно ✓ — ответ: ${t.answer.ru}")
        else word(s"Not right ✗ — «$typed». Try again, or let the desk solve it.",
          s"Неверно ✗ — «$typed». Попробуйте ещё раз или дайте решить столу."))))
      val parts = List(Part.block("back")(Part.slot("back")),
        Part.block("task", s"${at + 1}. ${t.title.in(ru)}")(
          ((about(t, ru) +: lines(t, ru).map(Part.text)) :+ Part.slot("task-solve"))*), form: Part) ++ check
      val back = View(v.page, v.kind, ru, readable = v.readable)
      (parts, List(
        Button(word("← Back to the tasks", "← К задачам"))(show(back)).at("back"),
        Button.submit(word("Check", "Проверить"), form, Style.Primary)(typed =>
          show(v.copy(tried = Some(typed.trim -> t.accepts(typed, alone = true))))),
        Button(word("Solve", "Решить"))(solve(t, ru)).at("task-solve"))) }

  /** the desk's words when it does not answer (ui/Reasoning.scala): not an answer to be judged */
  private val gaveUp = Vector("settle this question", "could not complete a valid formal program") ++
    okay.desk.statement.Said.noAnswer

  /** THE CHECK OF WHAT THE DESK SAID about a task: right, not right, or no answer — with the exact solver's answer.
   * `said`: its statements; `tables`: the results its engines drew. A task over data is judged by the last table,
   * row by row; any other by the statements, a table never: a grid has every name of the task in it. */
  def check(solving: Solving, said: Vector[String], tables: Vector[String]): String =
    val (t, ru) = (solving.task, solving.russian)
    val text = said.mkString("\n")
    val right = (t.gold, tables.lastOption, solving.result) match
      case (_, _, Some(result)) => t.agrees(result, ru)
      case (Gold.Table(_), Some(table), _) => Judge.tabled(t, table)
      case _ => t.accepts(text)
    val silent = said.isEmpty && tables.isEmpty || gaveUp.exists(text.contains)
    val verdict =
      if silent then if ru then "ответа нет ✗" else "no answer ✗"
      else if right then if ru then "верно ✓" else "right ✓"
      else if ru then "неверно ✗" else "not right ✗"
    if ru then s"Проверка: $verdict — точный ответ (${solver(t, ru)}): ${t.answer.ru}"
    else s"Check: $verdict — the exact answer (${solver(t, ru)}): ${t.answer.en}"
