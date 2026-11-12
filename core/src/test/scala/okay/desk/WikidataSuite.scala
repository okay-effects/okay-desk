package okay.desk

import okay.desk.puzzle.*
import okay.effects.json.Json

class WikidataSuite extends munit.FunSuite:
  val puzzle = PuzzleText.parse(PuzzleText.worldExample, PuzzleDomain.rules).puzzle
  val need = World.Need("countries", "capital", "Paris", Ref("pets", "dog"))
  val request = Wikidata.plan(need, puzzle).toOption.get.head

  def results(rows: (String, Option[String], Option[String])*): String =
    def binding(value: String) = Json.obj("value" -> Json.Str(value))
    Json.print(Json.obj("results" -> Json.obj("bindings" -> Json.Arr(rows.toVector.map { (id, value, label) =>
      Json.Obj(
        Vector(
          "entity" -> binding("http://www.wikidata.org/entity/" + id),
          "retrieved" -> binding("2026-11-08T15:00:00Z")
        ) ++
          value.map(v => "value" -> binding("http://www.wikidata.org/entity/" + v)) ++
          label.map(v => "label" -> binding(v))
      )
    }))))

  test("the need authors the research plan, including type, rank and time filters; query text is escaped"):
    val plan = Wikidata.plan(need, puzzle).toOption.get
    assertEquals(plan.map(_.subject), Vector("France", "Germany"))
    val query = "%([A-F0-9]{2})".r.replaceAllIn(request.url, m => Integer.parseInt(m.group(1), 16).toChar.toString)
    assert(query.contains("wdt:P31/wdt:P279* wd:Q6256"), query)
    assert(query.contains("wikibase:BestRank") && query.contains("pq:P582") && query.contains("pq:P580"))
    assert(query.contains("pq:P585") && query.contains("p:P36") && query.contains("NOW()"))
    val unsafe = puzzle.copy(sorts = Vector(Sort("countries", Vector("A\" . } #", "Germany")), puzzle.sorts(1)))
    val escaped = Wikidata.plan(need, unsafe).toOption.get.head.url
    assert(escaped.contains("%5C%22"), escaped)
    assert(Wikidata.plan(need.copy(property = "invented property"), puzzle).isLeft)

  test("structured values become sourced facts; duplicate statements do not duplicate property values"):
    val response = results(("Q142", Some("Q90"), Some("Paris")), ("Q142", Some("Q90"), Some("Paris")))
    assertEquals(
      Wikidata.decode(request, response),
      Right(World.Fact("France", "capital", Vector("Paris"), "https://www.wikidata.org/wiki/Q142#P36", "2026-11-08"))
    )

  test("entity ambiguity, unknown statements, missing labels, truncation and API errors remain unknown"):
    val ambiguous = results(("Q142", Some("Q90"), Some("Paris")), ("Q999", Some("Q90"), Some("Paris")))
    assert(Wikidata.decode(request, ambiguous).left.toOption.exists(_.contains("ambiguous")))
    val sameLabel = results(("Q142", Some("Q90"), Some("Paris")), ("Q142", Some("Q999"), Some("Paris")))
    assert(Wikidata.decode(request, sameLabel).left.toOption.exists(_.contains("ambiguous")))
    for bad <- Vector(
        results(),
        results(("Q142", None, None)),
        results(("Q142", Some("Q90"), None)),
        results(("not-an-id", Some("Q90"), Some("Paris"))),
        "{\"error\":{}}",
        "not JSON",
        results(Vector.fill(101)(("Q142", Some("Q90"), Some("Paris")))*)
      )
    do assert(Wikidata.decode(request, bad).isLeft, bad.take(150))
