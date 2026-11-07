package okay.desk.skill

import okay.desk.Phrase

/**
 * THE DATA'S OWN WORDS (specs/data.md §29): a dataset's names said in another language — its tables, its columns,
 * the values of its text columns — beside the generator's dictionary (`Phrase.Russian`). Real data has names the
 * generator never saw ("Ukraine", "gdp"); a person writes "Украины", "ВВП". Made once per dataset by a model that
 * generates (bench `MakeWords`), kept beside the data as a file, read by the exact rules as facts: a word of the
 * question that is a form of a value is that value. Lower case, the nominative; the rules match the stem.
 */
final case class Words(tables: Map[String, String] = Map.empty, columns: Map[String, String] = Map.empty,
    values: Map[String, String] = Map.empty):
  /** a table's Russian forms (nominative, genitive, prepositional, dative): the generator's, else the data's nominative */
  def table(n: String): Option[(String, String, String, String)] =
    Phrase.Russian.tables.get(n).orElse(tables.get(n).map(w => (w, w, w, w)))
  /** a column's Russian forms (nominative, genitive, dative), by its name in any case */
  def column(c: String): Option[(String, String, String)] =
    Phrase.Russian.columns.get(c).orElse(columns.get(c).orElse(columns.collectFirst {
      case (k, w) if k.equalsIgnoreCase(c) => w }).map(w => (w, w, w)))
  /** a value's Russian form */
  def value(v: String): Option[String] = Phrase.Russian.values.get(v).orElse(values.get(v))
  def ++(o: Words): Words = Words(tables ++ o.tables, columns ++ o.columns, values ++ o.values)
  def isEmpty: Boolean = tables.isEmpty && columns.isEmpty && values.isEmpty
  def json: String =
    import okay.effects.json.Json
    def obj(m: Map[String, String]) = Json.Obj(m.toVector.sortBy(_._1).map((k, v) => k -> Json.Str(v)))
    Json.print(Json.Obj(Vector("tables" -> obj(tables), "columns" -> obj(columns), "values" -> obj(values))))

object Words:
  val none: Words = Words()
  def parse(text: String): Either[String, Words] =
    import okay.effects.json.Json
    def strings(j: Option[Json]): Map[String, String] = j match
      // a form of letters, spaces and hyphens only: the rules build patterns from it ("… (страны мбрр)" is left out)
      case Some(Json.Obj(fs)) => fs.collect { case (k, Json.Str(v)) if v.matches("(?U)[\\p{L} -]+") => k -> v.toLowerCase }.toMap
      case _ => Map.empty
    try Json.fromText(text) match
      case Json.Obj(fs) =>
        val m = fs.toMap
        Right(Words(strings(m.get("tables")), strings(m.get("columns")), strings(m.get("values"))))
      case _ => Left("an object of tables, columns and values expected")
    catch case e: Exception => Left(e.getMessage)
