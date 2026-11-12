package okay.desk.puzzle

import okay.effects.json.Json

/** A bounded research plan derived from a world hole. No answer comes from the model's memory: Wikidata's structured
  * statements supply the values, and okay-inlay selects among the resulting snapshots.
  */
object Wikidata:
  final case class Request(subject: String, property: String, url: String)
  private val MaxBindings = 100
  private val properties = Map(
    "capital" -> "P36",
    "столица" -> "P36",
    "столиця" -> "P36",
    "stolica" -> "P36",
    "continent" -> "P30",
    "континент" -> "P30",
    "kontynent" -> "P30",
    "currency" -> "P38",
    "валюта" -> "P38",
    "waluta" -> "P38",
    "borders" -> "P47",
    "граничит с" -> "P47",
    "межує з" -> "P47",
    "graniczy z" -> "P47"
  )
  private val classes = Map(
    "countries" -> "Q6256",
    "country" -> "Q6256",
    "страны" -> "Q6256",
    "країни" -> "Q6256",
    "kraje" -> "Q6256",
    "cities" -> "Q515",
    "города" -> "Q515",
    "міста" -> "Q515",
    "miasta" -> "Q515",
    "people" -> "Q5",
    "persons" -> "Q5",
    "люди" -> "Q5",
    "osoby" -> "Q5"
  )

  private def encode(text: String): String = text
    .getBytes("UTF-8")
    .map { b =>
      val n = b & 255
      if n >= 65 && n <= 90 || n >= 97 && n <= 122 || n >= 48 && n <= 57 || "-_.~".contains(n.toChar)
      then n.toChar.toString
      else "%%%02X".format(n)
    }
    .mkString

  private def lang(text: String): String =
    if text.exists("іїєґІЇЄҐ".contains(_)) then "uk"
    else if text.exists(c => c >= 'А' && c <= 'я' || c == 'ё' || c == 'Ё') then "ru"
    else if text.exists("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ".contains(_)) then "pl"
    else "en"

  def plan(need: World.Need, puzzle: Puzzle): Either[String, Vector[Request]] =
    for
      property <- properties
        .get(need.property.toLowerCase)
        .toRight(s"Wikidata property not supported yet: ${need.property}")
      sort <- puzzle.sort(need.sort).toRight(s"unknown sort: ${need.sort}")
      _ <- Either.cond(sort.values.size <= 12, (), "Wikidata research is limited to 12 subjects per clue")
    yield sort.values.map { subject =>
      val name = Json.print(Json.Str(subject))
      val kind = classes
        .get(need.sort.toLowerCase)
        .map(c => s"?entity wdt:P31/wdt:P279* wd:$c .")
        .getOrElse("")
      val query = s"""PREFIX wd: <http://www.wikidata.org/entity/>
        |PREFIX wdt: <http://www.wikidata.org/prop/direct/>
        |PREFIX p: <http://www.wikidata.org/prop/>
        |PREFIX ps: <http://www.wikidata.org/prop/statement/>
        |PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
        |PREFIX wikibase: <http://wikiba.se/ontology#>
        |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        |SELECT DISTINCT ?entity ?value ?label ?retrieved WHERE {
        |  ?entity (rdfs:label|skos:altLabel) $name@${lang(subject)} .
        |  $kind
        |  BIND(NOW() AS ?retrieved)
        |  OPTIONAL {
        |    ?entity p:$property ?statement .
        |    ?statement a wikibase:BestRank; ps:$property ?value .
        |    FILTER(isIRI(?value))
        |    FILTER NOT EXISTS { ?statement pq:P582 ?end . FILTER(?end <= NOW()) }
        |    FILTER NOT EXISTS { ?statement pq:P580 ?start . FILTER(?start > NOW()) }
        |    FILTER NOT EXISTS { ?statement pq:P585 ?pointInTime }
        |    OPTIONAL { ?value rdfs:label ?label . FILTER(LANG(?label) = "${lang(need.value)}") }
        |  }
        |} LIMIT ${MaxBindings + 1}
        |""".stripMargin
      Request(subject, need.property, "https://query.wikidata.org/sparql?format=json&query=" + encode(query))
    }

  private def fields(j: Json): Map[String, Json] = j match
    case Json.Obj(fs) => fs.toMap
    case _            => Map.empty
  private def string(j: Json): Option[String] = j match
    case Json.Str(s) => Some(s)
    case _           => None

  /** An absent statement is unknown. A source with multiple matching entities is ambiguous even when their values
    * agree. The API's timestamp is the retrieval date; the source identifies the subject and property.
    */
  def decode(request: Request, text: String): Either[String, World.Fact] =
    try
      val top = fields(Json.fromText(text))
      val bindings = top.get("results").flatMap(r => fields(r).get("bindings")) match
        case Some(Json.Arr(rows)) => rows.toVector
        case _                    => return Left("Wikidata did not return structured query results")
      def binding(row: Json, key: String): Option[String] =
        fields(row).get(key).flatMap(j => fields(j).get("value")).flatMap(string)
      val entities = bindings.flatMap(binding(_, "entity")).distinct
      val ids = entities.map(_.stripPrefix("http://www.wikidata.org/entity/"))
      if bindings.size > MaxBindings then Left("Wikidata result is too large; no partial facts imported")
      else if entities.isEmpty then Left(s"Wikidata entity not found: ${request.subject}")
      else if entities.size != 1 then Left(s"Wikidata entity is ambiguous: ${request.subject} (${ids.mkString(", ")})")
      else if !ids.head.matches("Q[1-9][0-9]*") then Left("invalid Wikidata entity identifier")
      else
        val dates = bindings.flatMap(binding(_, "retrieved")).map(_.take(10)).distinct
        val labels = bindings.flatMap(binding(_, "label"))
        val named = bindings.flatMap { row =>
          for id <- binding(row, "value"); label <- binding(row, "label") yield (id, label)
        }
        val collisions = named.groupBy(_._2).values.exists(_.map(_._1).distinct.size > 1)
        val complete = bindings.forall { row =>
          binding(row, "value").exists(_.matches("http://www.wikidata.org/entity/Q[1-9][0-9]*")) &&
          binding(row, "label").exists(_.nonEmpty)
        }
        if !complete then Left(s"Wikidata property or value label is missing: ${request.subject}/${request.property}")
        else if collisions then Left(s"Wikidata value label is ambiguous: ${request.subject}/${request.property}")
        else if dates.size != 1 || !dates.head.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") then
          Left("Wikidata retrieval date missing")
        else
          val property = properties(request.property.toLowerCase)
          Right(
            World.Fact(
              request.subject,
              request.property,
              labels.distinct,
              s"https://www.wikidata.org/wiki/${ids.head}#$property",
              dates.head
            )
          )
    catch case e: Exception => Left("Wikidata response not read: " + Option(e.getMessage).getOrElse("invalid JSON"))
