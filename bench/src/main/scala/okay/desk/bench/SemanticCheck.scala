package okay.desk.bench

import java.nio.file.Path
import okay.llm.compute.OnMlx

/** Development answer agreement. No runtime dictionaries, baseline reader or task-text parser participates. */
object SemanticCheck:
  val cases = Vector(
    "У Лены 8 яблок. Она отдала 3 яблока. Сколько яблок осталось?" -> "5",
    "A telescope recorded 12 signals on Monday and 9 on Tuesday. How many signals did it record?" -> "21",
    "Для эксперимента взяли 7 капсул, в каждой 4 семени. Сколько семян взяли?" -> "28",
    "The route is 18 km. A runner has covered 11 km. How far remains?" -> "7",
    "Цена товара 150 рублей. Скидка 20 процентов. Сколько рублей составляет скидка?" -> "30",
    "A battery has 48 units of energy. Each operation uses 6 units. How many operations are possible?" -> "8",
    "Петя на 2 года младше Васи. Пете 7 лет. Сколько лет Васе?" -> "9",
    "Целое x от 1 до 8. При увеличении x на 2 получаем 7. Чему равно x?" -> "5",
    "An archive has 6 shelves with 8 scrolls each. It loans 5 scrolls. How many scrolls remain?" -> "43",
    "В сосуде 3 литра раствора. Добавили 2 литра. Затем отлили 1 литр. Сколько литров осталось?" -> "4")
  def main(args: Array[String]): Unit =
    val dir = Path.of(args.headOption.getOrElse(sys.props("user.home") +
      "/.cache/okay-desk/semantic-student/weights"))
    val config = okay.llm.qwen.Qwen3Config.parse(java.nio.file.Files.readString(dir.resolve("config.json")))
    val r = ComputeReading(dir, config.layers, OnMlx())
    val tested = cases.take(args.lift(1).map(_.toInt).getOrElse(cases.size))
    var correct = 0
    for (question, expected) <- tested do
      val report = SemanticExecution.run(question, r)
      val result = report.result
      val passed = result.exists(x => x.status == "proved" && x.answers.values.toVector == Vector(Vector(expected)))
      if passed then correct += 1
      println(s"${if passed then "PASS" else "FAIL"}: $question\n${report.trace.mkString("\n")}\n" +
        s"$result; expected=$expected")
    println(s"ANSWER AGREEMENT (not whole-formalization fidelity): $correct/${tested.size}")
