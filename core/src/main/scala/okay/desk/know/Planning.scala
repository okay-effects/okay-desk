package okay.desk.know

import okay.datalog.{Eval, Fact, Parse}
import okay.desk.exec.Wire
import okay.effects.json.Json
import okay.inlay.Horn

/** A bounded finite transition model. Only the language model supplies its meaning; no task text is parsed. */
object Planning:
  final case class Result(status: String, variables: Vector[String], states: Vector[Vector[String]],
      moves: Vector[Vector[String]], visited: Int, engine: String, datalog: String, proof: Vector[String],
      semantics: String = "") derives Wire
  private final case class Move(guard: Json, assignments: Map[String, Json], flip: Vector[String])
  private def fields(j: Json): Vector[(String, Json)] = j match
    case Json.Obj(fs) if fs.map(_._1).distinct.size == fs.size => fs
    case _ => throw IllegalArgumentException("distinct object keys required")
  private def array(j: Json): Vector[Json] = j match
    case Json.Arr(xs) => xs.toVector
    case _ => throw IllegalArgumentException("array required")
  private def fail[A](result: Either[String, A]): A = result.fold(e => throw IllegalArgumentException(e), identity)
  private def show(j: Json): String = j match
    case Json.Str(s) => s
    case other => Json.print(other)
  private val rules = "reachable(X) :- initial(X). reachable(Y) :- reachable(X), edge(X, Y)."

  /** Read back the supplied definition, without deriving meaning from the original question. */
  def describe(text: String, russian: Boolean): String =
    try
      val fs = fields(Json.fromText(text)).toMap
      val names = fs("vars") match
        case Json.Arr(xs) => xs.map(_.string).toVector
        case value => fields(value).map(_._1)
      val labels = fs.get("labels").map(fields).getOrElse(Vector.empty).map((k, v) => k -> v.string).toMap
      def name(s: String): String = labels.getOrElse(s, s)
      val symbols = Map("!=" -> "≠", "and" -> "∧", "or" -> "∨", "implies" -> "⇒")
      def expr(j: Json): String = j match
        case Json.Str(s) => name(s)
        case Json.Arr(Vector(Json.Str("text"), Json.Str(s))) => "«" + s + "»"
        case Json.Arr(Json.Str(op) +: args) if args.size < 2 || Set("not", "abs", "count", "distinct", "if")(op) =>
          op + args.map(expr).mkString("(", ", ", ")")
        case Json.Arr(Json.Str(op) +: args) =>
          args.map(expr).mkString("(", " " + symbols.getOrElse(op, op) + " ", ")")
        case value => show(value)
      def state(j: Json): String = names.zip(array(j)).map((n, v) => name(n) + "=" + show(v)).mkString(", ")
      val actions = array(fs("moves")).map { j =>
        val m = fields(j).toMap
        val effect = m.get("flip").fold(fields(m("set")).map((n, v) => name(n) + " := " + expr(v))
          .mkString(", "))(xs => (if russian then "Переключить " else "Toggle ") +
            array(xs).map(expr).mkString(", "))
        val choice = m.get("choose").fold("") { c =>
          (if russian then "; дополнительно выбрать " else "; additionally choose ") +
            show(c("min")) + ".." + show(c("max")) + " ∈ {" + array(c("vars")).map(expr).mkString(", ") +
            "}, " + (if russian then "прежнее значение = " else "old value = ") + expr(c("equal"))
        }
        effect + choice + m.get("if").fold("")(g => (if russian then "; если " else "; if ") + expr(g))
      }
      val safe = array(fs("safe")).map(expr)
      Vector((if russian then "Начало: " else "Initial: ") + state(fs("start")),
        (if russian then "Цель: " else "Target: ") + state(fs("goal")),
        (if russian then "Допустимые действия:\n" else "Allowed actions:\n") + actions.mkString("\n"),
        (if russian then "Условия в начале и после каждого действия:\n" else "Initial and after-action conditions:\n") +
          (if safe.isEmpty then (if russian then "не заданы" else "none declared")
           else safe.zipWithIndex.map((p, i) => s"${i + 1}. $p").mkString("\n"))).mkString("\n\n")
    catch case _: Exception => "The model proposed an invalid transition definition."

  def run(text: String, includeSemantics: Boolean = false): Either[String, Result] =
    try
      require(text.length <= 20000, "planning program exceeds bounds")
      val fs = fields(Json.fromText(text)).toMap
      require(Set("vars", "start", "goal", "moves", "safe").subsetOf(fs.keySet), "incomplete planning model")
      require((fs.keySet -- Set("vars", "start", "goal", "moves", "safe", "labels")).isEmpty,
        "unknown planning field")
      val variables = fs("vars") match
        case Json.Arr(xs) => xs.map {
          case Json.Str(s) => s -> Vector(Json.Num(0), Json.Num(1))
          case _ => throw IllegalArgumentException("binary variable names required")
        }.toVector
        case other => fields(other).map((key, domain) => key -> array(domain))
      val names = variables.map(_._1)
      require(names.nonEmpty && names.size <= 8 && names.distinct == names &&
        names.forall(n => n.nonEmpty && n.length <= 80), "one to eight distinct state variables required")
      variables.foreach { (_, domain) =>
        require(domain.nonEmpty && domain.size <= 16 && domain.distinct == domain, "invalid state domain")
        domain.foreach(v => fail(Formal.checkLiteral(v)))
      }
      require(variables.map(_._2.size.toLong).product <= 256, "state space exceeds 256 states")
      val labels = fs.get("labels").map(fields).getOrElse(Vector.empty).map {
        case (key, Json.Str(s)) if names.contains(key) && s.nonEmpty && s.length <= 100 => key -> s
        case _ => throw IllegalArgumentException("labels must name declared variables")
      }.toMap
      require(labels.isEmpty || labels.keySet == names.toSet, "labels must describe every declared state variable")
      require(labels.values.toVector.distinct.size == labels.size, "entity labels must be distinct")
      def label(name: String): String = labels.getOrElse(name, name)
      def state(j: Json): Vector[Json] =
        val xs = array(j)
        require(xs.size == names.size && xs.zip(variables).forall((v, field) => field._2.contains(v)),
          "state values must belong to their declared domains")
        xs
      val initial = state(fs("start"))
      val target = state(fs("goal"))
      val safe = array(fs("safe"))
      require(safe.size <= 32, "too many safety predicates")
      safe.foreach(p => fail(Formal.check(p, names.toSet)))
      val moves = array(fs("moves")).flatMap { raw =>
        val m = fields(raw).toMap
        require((m.keySet -- Set("if", "set", "flip", "choose")).isEmpty &&
          (m.contains("set") != m.contains("flip")), "a move needs exactly one set or flip effect")
        val guard = m.getOrElse("if", Json.Bool(true))
        fail(Formal.check(guard, names.toSet))
        val sets = m.get("set").map(fields).getOrElse(Vector.empty).toMap
        require(sets.keys.forall(names.contains), "an effect names an undeclared variable")
        sets.values.foreach(p => fail(Formal.check(p, names.toSet)))
        val flips = m.get("flip").map(array).getOrElse(Vector.empty).map {
          case Json.Str(s) if names.contains(s) && variables(names.indexOf(s))._2.size == 2 => s
          case _ => throw IllegalArgumentException("flip requires a declared two-valued variable")
        }
        require(flips.distinct == flips && (sets.nonEmpty || flips.nonEmpty), "empty or duplicate move effect")
        m.get("choose") match
          case None => Vector(Move(guard, sets, flips))
          case Some(choice) =>
            require(sets.isEmpty, "choose extends a flip action")
            val params = fields(choice).toMap
            require(params.keySet == Set("vars", "min", "max", "equal"),
              "choose needs vars, min, max and equal")
            val candidates = array(params("vars")).map {
              case Json.Str(s) if names.contains(s) && variables(names.indexOf(s))._2.size == 2 => s
              case _ => throw IllegalArgumentException("choose needs declared two-valued variables")
            }
            require(candidates.distinct == candidates && candidates.forall(n => !flips.contains(n)),
              "choice variables must be distinct from fixed effects")
            def bound(key: String): Int = params(key) match
              case Json.Num(n) if n.isWhole && n >= 0 && n <= candidates.size => n.toInt
              case _ => throw IllegalArgumentException("invalid choice cardinality")
            val low = bound("min")
            val high = bound("max")
            require(low <= high, "invalid choice interval")
            val anchor = params("equal") match
              case Json.Str(s) if names.contains(s) => s
              case _ => throw IllegalArgumentException("choice equality needs a declared variable")
            (low to high).flatMap(k => candidates.combinations(k)).map { selected =>
              val conditions = guard +: selected.map(s => Json.Arr(Vector(Json.Str("="),
                Json.Str(s), Json.Str(anchor))))
              Move(Json.Arr(Json.Str("and") +: conditions), Map.empty, flips ++ selected)
            }.toVector
      }
      require(moves.size <= 128, "too many expanded moves")
      val all = variables.foldLeft(Vector(Vector.empty[Json])) { (states, variable) =>
        states.flatMap(s => variable._2.map(s :+ _))
      }
      def env(s: Vector[Json]): Map[String, Json] = names.zip(s).toMap
      def predicate(p: Json, bindings: Map[String, Json]): Boolean = fail(Formal.value(p, bindings)) match
        case Json.Bool(b) => b
        case _ => throw IllegalArgumentException("state guards and safety conditions must be boolean")
      def valid(s: Vector[Json]): Boolean = safe.forall(predicate(_, env(s)))
      require(valid(initial), "the declared initial state violates safety conditions")
      val legal = all.filter(valid)
      val ids = legal.zipWithIndex.toMap
      val start = ids(initial)
      val goal = ids.get(target)
      val edges = legal.zipWithIndex.flatMap { (s, from) =>
        moves.flatMap { move =>
          val bindings = env(s)
          if !predicate(move.guard, bindings) then None
          else
            val next = names.zipWithIndex.map { (name, i) =>
              if move.flip.contains(name) then
                val domain = variables(i)._2
                domain(1 - domain.indexOf(s(i)))
              else move.assignments.get(name).fold(s(i))(p => fail(Formal.value(p, bindings)))
            }
            ids.get(next).filter(_ != from).map(to => from -> to)
        }.distinct
      }
      val input = Fact("initial", Vector(s"s$start")) +:
        edges.map((a, b) => Fact("edge", Vector(s"s$a", s"s$b")))
      val derived = fail(Eval.run(fail(Parse.program(rules)), input, proofs = true))
      val reached = derived.relation("reachable").map(_.head.stripPrefix("s").toInt).toSet
      var engine = "Datalog + breadth-first search"
      if legal.size <= Horn.MaxAtoms && edges.size <= Horn.MaxRules then
        val atoms = legal.indices.map(i => s"s$i").toVector
        val horn = Horn.Block(atoms, edges.zipWithIndex.map { case ((a, b), i) =>
          Horn.Rule(s"edge $i", Vector(s"s$a"), s"s$b")
        }).run(Set(s"s$start"))
        require(horn.known == reached.map(i => s"s$i"), "compiled inlay and Datalog disagree")
        engine += " + compiled inlay Horn"
      val queue = scala.collection.mutable.Queue(start)
      val previous = scala.collection.mutable.Map(start -> start)
      val adjacent = edges.groupMap(_._1)(_._2)
      while queue.nonEmpty do
        val from = queue.dequeue()
        adjacent.getOrElse(from, Vector.empty).foreach { to =>
          if !previous.contains(to) then
            previous(to) = from
            queue.enqueue(to)
        }
      require(previous.keySet.toSet == reached, "Datalog and shortest-path search disagree")
      val path = goal.filter(reached).fold(Vector.empty[Int]) { end =>
        var out = Vector(end)
        while out.head != start do out = previous(out.head) +: out
        out
      }
      val steps = path.sliding(2).filter(_.size == 2).map { pair =>
        names.indices.filter(i => legal(pair.head)(i) != legal(pair.last)(i)).map(i => label(names(i))).toVector
      }.toVector
      val witness = Fact("initial", Vector(s"s$start")) +: path.sliding(2).filter(_.size == 2)
        .map(xs => Fact("edge", Vector(s"s${xs.head}", s"s${xs.last}"))).toVector
      val certificate = fail(Eval.run(fail(Parse.program(rules)), witness, proofs = true))
      val proof = goal.filter(reached).toVector.flatMap(g =>
        certificate.proof(Fact("reachable", Vector(s"s$g"))).toVector.flatten.map(_.toString))
      require(path.isEmpty || proof.nonEmpty, "the Datalog witness does not prove the selected path")
      val mapping = path.map(i => Fact("state", s"s$i" +: legal(i).map(show)).toString + ".")
      val datalog = "% state columns: " + Json.print(Json.Arr(names.map(n => Json.Str(label(n))))) + "\n" +
        rules + "\n" + (mapping ++ witness.map(_.toString + ".")).mkString("\n")
      val signature = if !includeSemantics then "" else
        require(names.map(label).distinct.size == names.size, "semantic comparison requires distinct entity labels")
        val order = names.indices.sortBy(i => label(names(i)))
        def canonical(s: Vector[Json]): Json = Json.Arr(order.map(s).toVector)
        Json.print(Json.obj("variables" -> Json.Arr(order.map(i => Json.Str(label(names(i)))).toVector),
          "start" -> canonical(initial), "goal" -> canonical(target),
          "states" -> Json.Arr(legal.map(canonical).sortBy(j => Json.print(j))),
          "edges" -> Json.Arr(edges.map((a, b) => Json.Arr(Vector(canonical(legal(a)), canonical(legal(b)))))
            .sortBy(j => Json.print(j)))))
      Right(Result(if path.nonEmpty then "proved" else "unreachable", names.map(label),
        path.map(i => legal(i).map(show)), steps, reached.size, engine, datalog, proof, signature))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse("invalid planning model"))

  val instructions: String = """
    |For a sequence of legal actions use {"plan":{"vars":["x","y"],"start":[0,0],"goal":[1,1],
    |"moves":[{"flip":["x"]},{"flip":["y"],"if":["=","x",1]}],"safe":[]}}.
    |vars as a name list means domains [0,1]; or give {"x":[0,1,2,3]} for finite domains.
    |start/goal list values follow variable order. Optional labels maps IDs to entity names.
    |Each move has flip (toggle listed two-valued variables) OR set (variable:expression), and optional if guard.
    |All effects use the OLD state simultaneously. Unmentioned variables stay unchanged.
    |A flip move can have choose:{"vars":["y","z"],"min":0,"max":1,"equal":"x"}: select that many
    |variables whose old value equals x, and flip them too. The tool enumerates subsets; do not enumerate them yourself.
    |safe lists predicates that must hold initially and after EVERY move. Include every stated prohibition.
    |Use the same expression operators as formal. Define all actions from the user's meaning; omit no restrictions.
    |The exact tool finds a shortest path and checks Datalog reachability. Do not write the answer or path yourself.
    |""".stripMargin
