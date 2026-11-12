package okay.desk.tasks

/**
 * THE COLLECTION (specs/formalization.md §5): the logic tasks the page must cope with, class by class — each with its
 * text in two languages, the answer a person expects, the gold statement for an exact solver and that solver's
 * result. The page shows it (the screen "Logic tasks"); the tests hold `Task.check` for every one of them.
 */
object Tasks:
  /** every task, in the order of the classes; a drawn task is made when it is first asked for */
  val entries: Vector[Entry] = (GridTasks.entries ++ Matching.entries ++ Entry.of(Deduction.deduction ++
    Deduction.truth ++ Relations.kinship ++ Relations.routes ++ Plans.tasks ++ Numbers.quantities ++
    Numbers.numbers ++ DataTasks.tasks)).sortBy(_.kind.ordinal)

  /** the classes that have tasks, each with how many */
  val kinds: Vector[(Kind, Int)] =
    Kind.values.toVector.map(k => k -> entries.count(_.kind == k)).filter(_._2 > 0)

  def of(kind: Option[Kind]): Vector[Entry] = kind.fold(entries)(k => entries.filter(_.kind == k))
