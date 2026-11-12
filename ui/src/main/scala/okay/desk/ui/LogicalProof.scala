package okay.desk.ui

import okay.desk.logic.Protocol

/** Read back the CPU's checked certificate, never a model-generated explanation of a proposed answer. */
object LogicalProof:
  def entry(result: Protocol.Result, russian: Boolean): App.Entry =
    val compiled = result.answers.count(_.inlayProof.nonEmpty)
    val title = if russian then "Логический вывод проверен" else "Logical inference checked"
    val summary = if russian then
      s"Ответов с проверенным доказательством: ${result.answers.size}. Проверено также через inlay: $compiled."
    else s"Answers with replayed proofs: ${result.answers.size}. Also checked through inlay: $compiled."
    val proof = result.answers.take(20).map { a =>
      a.fact + "\n" + a.proof.mkString("\n") + a.inlayProof.fold("")(steps => "\ninlay:\n" + steps.mkString("\n"))
    }.mkString("\n\n")
    val scope = if russian then
      "Доказательство проверяет вывод из интерпретации модели и исходных данных."
    else "The proof checks derivation from the model interpretation and supplied data."
    val omitted = if result.answers.size <= 20 then "" else if russian then
      "\nПоказаны первые 20 доказательств; проверены все ответы."
    else "\nShowing the first 20 proofs; every answer was checked."
    App.Entry("proof", title + "\n" + summary + omitted, program = Some(proof), note = Some(scope))
