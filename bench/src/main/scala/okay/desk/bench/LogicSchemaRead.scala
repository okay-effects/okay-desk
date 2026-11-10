package okay.desk.bench

import okay.desk.calc.{Term, Ty}
import okay.desk.logic.{Compile, Corpus, LogicGrammar, Ontology, Schema}
import okay.desk.skill.{Extract, LogicRead, Probes, Read}
import okay.desk.skill.Extract.Evidence
import okay.desk.skill.LogicRead.{Features, Layout, Masked}

import java.nio.file.Path

/**
 * THE SCHEMA-FIRST DECODER, END TO END (specs/logic.md §5.7): the schema head picks the form from the task text, the
 * pointer reads each of the schema's slots under the prefix the schema itself writes, `Schema.instantiate` builds the
 * program and the exact handler runs it. Nothing here is trained on a gold prefix at decode time: the slots see the
 * schema's own word with the constants read so far, which is what a page would run.
 *
 * The margin is the safety: the schema's top-two gap and the pointer's gap to its runner-up both have to clear their
 * calibrated value, or the task is abstained. The sweep on Validate picks the pair with no wrong-closed reading; the
 * Test split is then read once with that pair. The budget is 1 schema decision plus one pointer read per slot
 * (§5.6), against 34 constructor decisions (§5.5).
 *
 * What the run reports, and why (§5.7): where a reading fails (the form or a slot), the two bottlenecks it names, and
 * two margins — one global pair, and one pointer margin per form (the form is known at decode time, so a form can carry
 * its own calibration; a form no Validate task chose abstains). Knobs: SCHEMA_TRAIN (tasks per family), SCHEMA_EPOCHS,
 * SCHEMA_LR, LOGIC_DIR, LOGIC_LAYERS.
 */
object LogicSchemaRead:
  def term(i: Corpus.Item): Term =
    val p = okay.datalog.Parse.program(i.rules).fold(e => sys.error(e), identity)
    val a = okay.datalog.Parse.atom(i.goal).fold(e => sys.error(e), identity)
    Compile.ofProgram(p, a, i.ontology).fold(e => sys.error(e), identity)

  final case class Task(item: Corpus.Item, gold: Term, schema: Schema, layout: Layout, schemaState: Array[Float])

  /** one task read at margin zero: the schema's gap, the gap at every slot, and the term (None when a slot had no
   * candidate at all). A higher margin only stops the same path earlier, so the trace answers every margin pair. */
  final case class Trace(task: Task, chosen: Option[String], schemaGap: Float, schemaRight: Boolean,
      slotGaps: Vector[Float], slotRight: Vector[Boolean], term: Option[Term])

  final case class Score(exact: Int, abstained: Int, wrong: Int, sameAnswer: Int, wrongSchema: Int):
    def line(tasks: Int): String =
      s"exact=$exact/$tasks abstained=$abstained wrong=$wrong (same answer $sameAnswer, wrong schema " +
        s"$wrongSchema)"

  def main(args: Array[String]): Unit =
    val trainCount = sys.env.get("SCHEMA_TRAIN").map(_.toInt).getOrElse(16)
    val splits = Vector("train" -> trainCount, "validate" -> 8, "test" -> 8)
    def splitOf(name: String): Corpus.Split =
      Corpus.Split.values.find(_.toString.toLowerCase == name).get

    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("LOGIC_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val layers = sys.env.get("LOGIC_LAYERS").map(_.toInt).getOrElse(12)
    println(s"backbone: $dir, layers=$layers, train=$trainCount per family")
    val reading = ComputeReading(dir, layers, ComputeReading.device.getOrElse(okay.llm.compute.OnMlx()))

    var read = 0
    def task(i: Corpus.Item): Task =
      val gold = term(i)
      val layout = Read.run(LogicRead.layout[Read](i.text), reading)
      read += 1
      if read % 10 == 0 then println(s"reading $read…")
      val state = Read.run(LogicRead.features[Read](i.text, Vector.empty, layout), reading).last
      Task(i, gold, Schema.of(gold), layout, state)

    val tasks = splits.map((name, n) => name -> Corpus.items(splitOf(name), n, seed = 7).map(task)).toMap
    val families = tasks("validate").map(_.item.family).distinct.sorted
    val library = Schema.library(tasks("train").map(_.gold))
    // the forms a task can ask are the forms its own tables allow: a schema naming a predicate the task's ontology does
    // not have is not a candidate. This is the ontology's filter, not the family's label — the page knows its tables.
    val foreign = Corpus.family.predicates.keySet ++ Corpus.numbers.predicates.keySet ++
      Corpus.plan.predicates.keySet ++ Corpus.data.predicates.keySet
    def labelsOf(o: Ontology): Vector[String] =
      library.filter(s => s.word.forall(t => !foreign.contains(t) || o.predicates.contains(t))).map(Schema.name)
    val byFamily = families.map(f => f -> Schema.library(
      tasks("train").filter(_.item.family == f).map(_.gold)).map(Schema.name)).toMap
    val ontologySame = families.forall { f =>
      val o = tasks("train").find(_.item.family == f).map(_.item.ontology).get
      labelsOf(o).sorted == byFamily(f).sorted
    }
    println(s"ontology filter equals the family partition: $ontologySame")

    val d = tasks("train").head.schemaState.length
    val all = tasks("train").map(_.schemaState)
    val mean = Vector.tabulate(d)(i => (all.map(_(i).toDouble).sum / all.size).toFloat)
    val std = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(all.map(x => math.pow(x(i) - mean(i), 2)).sum / all.size)).toFloat)
    def norm(x: Array[Float]): Array[Float] = LogicRead.normalize(x, mean, std)

    // ---- the schema head: one decision for the form ----
    val head = Masked(library.map(Schema.name), d)
    val schemaExamples = tasks("train").map(t =>
      (norm(t.schemaState), labelsOf(t.item.ontology), Schema.name(t.schema)))
    head.train(schemaExamples, epochs = 300, lr = 0.1f, seed = 1, batch = 8)
    val headTrain = schemaExamples.count((x, labels, y) => head.scores(x, labels).head._1 == y)
    println(s"schema head: ${schemaExamples.size} examples, ${library.size} schemas, train $headTrain/" +
      s"${schemaExamples.size}")

    // ---- the pointer: which of the task's words is this slot ----
    val cache = scala.collection.mutable.Map.empty[(String, String, String), Features]
    def state(t: Task, prefix: Vector[String], options: Vector[String]): Features =
      cache.getOrElseUpdate((t.item.text, prefix.mkString(" "), options.mkString(" ")),
        Read.run(LogicRead.features[Read](t.item.text, prefix, t.layout, options), reading))

    def fromAt(gold: Extract.Reading, i: Int): Int =
      gold.choices.take(i).flatMap(_.evidence).collect { case Evidence.Text(s) => s.end }.lastOption.getOrElse(0)

    def rawCopies(ts: Vector[Task]): Vector[(Array[Float], Vector[Array[Float]], Int)] =
      ts.flatMap { t =>
        val g = LogicGrammar(t.item.ontology)
        val gold = Extract.gold(g, t.item.text, t.gold).fold(e => sys.error(s"${t.item.text}: $e"), identity)
        LogicRead.steps(g, gold).zipWithIndex.flatMap { (s, i) =>
          if !Extract.copies(s.ty, s.target) then None
          else
            val f = state(t, s.prefix, LogicRead.shown(g, s.ty))
            if f.states.size != f.words.size then None
            else
              val cs = LogicRead.copiesAt(g, t.item.text, s.ty, fromAt(gold, i), f.words, f.states)
              if cs.isEmpty then None
              else
                val target = gold.choices(i).evidence match
                  case Some(Evidence.Text(_)) => cs.indexWhere(_._1 == s.target)
                  case _                      => cs.size
                if target < 0 then None
                else Some((f.last, cs.map(_._3), target))
        }
      }

    // the pointer normalizes on its own inputs (the slots' states), not on the schema head's one state per task
    val rawTrain = rawCopies(tasks("train"))
    val rawValidate = rawCopies(tasks("validate"))
    val slotStates = rawTrain.flatMap((q, ks, _) => q +: ks)
    val slotMean = Vector.tabulate(d)(i => (slotStates.map(_(i).toDouble).sum / slotStates.size).toFloat)
    val slotStd = Vector.tabulate(d)(i => math.max(1e-3,
      math.sqrt(slotStates.map(x => math.pow(x(i) - slotMean(i), 2)).sum / slotStates.size)).toFloat)
    def normSlot(x: Array[Float]): Array[Float] = LogicRead.normalize(x, slotMean, slotStd)
    def scaleOf(x: Array[Float]): Array[Float] = LogicRead.scale(normSlot(x), d)
    def copiesOf(raw: Vector[(Array[Float], Vector[Array[Float]], Int)]): Vector[(Array[Float], Vector[
        Array[Float]], Int)] = raw.map((q, ks, y) => (scaleOf(q), ks.map(scaleOf), y))
    val trainCopies = copiesOf(rawTrain)
    val validateCopies = copiesOf(rawValidate)
    val tuned = Vector((150, 0.05f), (150, 0.02f), (300, 0.01f), (300, 0.005f)).map { (steps, rate) =>
      val p = Probes.Pointer(d, 64)
      if trainCopies.nonEmpty then p.train(trainCopies, steps = steps, lr = rate)
      val ok = trainCopies.count((q, keys, y) => p.predict(q, keys) == y)
      val okV = validateCopies.count((q, keys, y) => p.predict(q, keys) == y)
      println(s"pointer steps=$steps lr=$rate: train $ok/${trainCopies.size} validate $okV/${validateCopies.size}")
      (okV, (steps, rate), p)
    }
    val (_, (pointerSteps, pointerLr), pointer) = tuned.maxBy(_._1)
    println(s"pointer: ${trainCopies.size} examples, steps=$pointerSteps lr=$pointerLr")

    /** the constant at a slot: the pointer's best candidate, `none` meaning the ontology's own constant, and the
     * gap to the runner-up — the margin that decides whether the slot is read or abstained */
    def pick(g: LogicGrammar, ty: Ty, cs: Vector[(String, Extract.Span, Array[Float])],
        f: Features): (Option[(String, Option[Extract.Span])], Float) =
      val keys = cs.map(c => scaleOf(c._3))
      val scores = pointer.scores(scaleOf(f.last), keys)
      val order = scores.indices.sortBy(i => -scores(i))
      val gap = if order.size < 2 then Float.PositiveInfinity else scores(order(0)) - scores(order(1))
      if order(0) < cs.size then (Some((cs(order(0))._1, Some(cs(order(0))._2))), gap)
      else
        val known = g.constructors(ty).filter(c => Extract.copies(ty, c) && !cs.exists(_._1 == c))
        (if known.size == 1 then Some((known.head, None)) else None, gap)

    def prefixOf(s: Schema, k: Int, values: Vector[String]): Vector[String] =
      val out = Vector.newBuilder[String]
      var slot = 0
      var i = 0
      var stop = false
      while i < s.word.size && !stop do
        if s.word(i) == "#" then
          if slot < k then
            out += values(slot)
            slot += 1
          else stop = true
        else out += s.word(i)
        i += 1
      out.result()

    /** the task read once at margin zero: what a margin can only cut short */
    def trace(t: Task): Trace =
      val g = LogicGrammar(t.item.ontology)
      val ranked = head.scores(norm(t.schemaState), labelsOf(t.item.ontology))
      val schemaGap = if ranked.size < 2 then Float.PositiveInfinity else ranked.head._2 - ranked(1)._2
      val chosen = library.find(s => Schema.name(s) == ranked.head._1)
      chosen match
        case None => Trace(t, None, schemaGap, false, Vector.empty, Vector.empty, None)
        case Some(s) =>
          val goldValues = Schema.values(t.gold)
          var values = Vector.empty[String]
          var gaps = Vector.empty[Float]
          var right = Vector.empty[Boolean]
          var from = 0
          var k = 0
          var stuck = false
          while !stuck && k < s.size do
            val f = state(t, prefixOf(s, k, values), LogicRead.shown(g, s.slots(k)))
            val cs = LogicRead.copiesAt(g, t.item.text, s.slots(k), from, f.words, f.states)
            val (con, gap) = pick(g, s.slots(k), cs, f)
            con match
              case None => stuck = true
              case Some((c, span)) =>
                gaps :+= gap
                right :+= goldValues.lift(k).contains(c)
                values :+= c
                from = span.map(_.end).getOrElse(from)
                k += 1
          Trace(t, Some(Schema.name(s)), schemaGap, Schema.name(s) == Schema.name(t.schema), gaps, right,
            if stuck then None else Schema.instantiate(g, s, values).toOption)

    val traces = tasks.map((name, ts) => name -> ts.map(trace))

    def at(tr: Trace, sm: Float, pm: Float): Option[Term] =
      if tr.schemaGap < sm || tr.slotGaps.exists(_ < pm) then None else tr.term

    def answer(t: Task, x: Term): Option[String] =
      Compile.run(t.item.tables, x).toOption.map((_, r) => r.rows.map(_.mkString("|")).mkString(";"))

    def tally(ts: Vector[Trace], decide: Trace => Option[Term]): Score =
      var exact = 0
      var abstained = 0
      var wrong = 0
      var same = 0
      var wrongSchema = 0
      for tr <- ts do
        decide(tr) match
          case None => abstained += 1
          case Some(x) =>
            if x == tr.task.gold then exact += 1
            else
              wrong += 1
              if !tr.schemaRight then wrongSchema += 1
              if answer(tr.task, x) == answer(tr.task, tr.task.gold) then same += 1
      Score(exact, abstained, wrong, same, wrongSchema)

    def summarize(ts: Vector[Trace], sm: Float, pm: Float): Score =
      tally(ts, tr => at(tr, sm, pm))

    val schemaMargins = Vector(0f, 0.5f, 1f, 2f, 3f, 5f, 8f)
    val pointerMargins = Vector(0f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f, 5f)
    val settings = for sm <- schemaMargins; pm <- pointerMargins yield
      ((sm, pm), summarize(traces("validate"), sm, pm))
    println(s"\nvalidate: ${settings.size} margin pairs, " +
      s"${settings.count(_._2.wrong == 0)} without a wrong-closed reading")
    for ((sm, pm), s) <- settings.sortBy { case ((sm, pm), s) => (-s.exact, sm, pm) }.take(12) do
      println(s"  schema=$sm pointer=$pm: ${s.line(32)}")
    for ((sm, pm), s) <- settings.filter(_._2.wrong == 0).sortBy { case ((sm, pm), s) => (-s.exact, sm, pm) }.take(8)
    do println(s"  safe: schema=$sm pointer=$pm: ${s.line(32)}")
    val usable = settings.filter(_._2.wrong == 0).filter(_._2.exact > 0)
    if usable.isEmpty then
      println("no margin pair reads a held-out program without a wrong-closed reading: do not ship")
      return
    val ((sm, pm), validation) = usable.maxBy(_._2.exact)
    println(s"\nchosen: schema margin $sm, pointer margin $pm; validate ${validation.line(32)}")
    val test = summarize(traces("test"), sm, pm)
    println(s"test: ${test.line(32)}")
    for f <- families do
      val ts = traces("test").filter(_.task.item.family == f)
      println(s"  $f: ${summarize(ts, sm, pm).line(ts.size)}")
    println("\nwhere the reading fails (margin 0):")
    for name <- Vector("validate", "test"); f <- families do
      val ts = traces(name).filter(_.task.item.family == f)
      val right = ts.filter(_.schemaRight)
      val closed = ts.count(_.term.nonEmpty)
      val exact = ts.count(tr => tr.term.exists(_ == tr.task.gold))
      val picks = right.flatMap(_.slotRight)
      println(s"  $name $f: schema ${right.size}/${ts.size}, closed $closed/${ts.size}, exact $exact/${ts.size}, " +
        s"slots ${picks.count(identity)}/${picks.size}")

    println("\nschema head misses on test:")
    for tr <- traces("test") if !tr.schemaRight do
      println(s"  ${tr.task.item.family}: gold ${Schema.name(tr.task.schema)}\n    got  ${tr.chosen.getOrElse("-")}")
    println("\npointer misses on test (right schema):")
    for tr <- traces("test") if tr.schemaRight && tr.slotRight.contains(false) do
      val gold = Schema.values(tr.task.gold)
      println(s"  ${tr.task.item.family}: gold ${gold.mkString(" ")} / slots " +
        tr.slotRight.zipWithIndex.map((ok, k) => if ok then "ok" else s"#$k wrong").mkString(" "))

    println("\nby family on validate (schema margin 0):")
    for f <- families do
      val ts = traces("validate").filter(_.task.item.family == f)
      val cells = pointerMargins.map { pm =>
        val s = summarize(ts, 0f, pm)
        s"$pm=${s.exact}/${s.abstained}/${s.wrong}"
      }
      println(s"  $f (${ts.size}): ${cells.mkString(" ")}")

    // ---- per-schema margins: the schema head names the form at decode time, so each form can carry its own
    // calibrated pointer margin. A form no validate task chose abstains: nothing calibrates it. ----
    val perSchema = traces("validate").groupBy(_.chosen).map { (name, ts) =>
      val safe = pointerMargins.filter(pm => summarize(ts, 0f, pm).wrong == 0)
      name -> safe.find(pm => summarize(ts, 0f, pm).exact > 0).getOrElse(Float.PositiveInfinity)
    }
    def atSchema(tr: Trace): Option[Term] =
      val pm = perSchema.getOrElse(tr.chosen, Float.PositiveInfinity)
      if tr.slotGaps.exists(_ < pm) then None else tr.term
    println(s"\nper-schema margins: " + perSchema.toVector.sortBy(_._1.getOrElse("")).map { (n, pm) =>
      s"${n.getOrElse("none")}=$pm" }.mkString(", "))
    println(s"validate: ${tally(traces("validate"), atSchema).line(32)}")
    val schemaTest = tally(traces("test"), atSchema)
    println(s"test: ${schemaTest.line(32)}")
    for f <- families do
      val ts = traces("test").filter(_.task.item.family == f)
      println(s"  $f: ${tally(ts, atSchema).line(ts.size)}")

    for tr <- traces("test") if at(tr, sm, pm).exists(_ != tr.task.gold) do
      println(s"WRONG (${tr.task.item.family}): ${tr.task.item.text}\n" +
        s"  gold: ${Extract.word(Extract.gold(LogicGrammar(tr.task.item.ontology), tr.task.item.text,
          tr.task.gold).fold(e => sys.error(e), identity)).mkString(" ")}\n" +
        s"  got:  ${at(tr, sm, pm).map(t => Schema.word(t).mkString(" ")).getOrElse("-")}")
