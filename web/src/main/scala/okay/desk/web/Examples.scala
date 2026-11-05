package okay.desk.web

import okay.business.scala3.*

/**
 * EXAMPLES OF OPEN DATA (examples.html), an okay-ui screen: files on the internet whose hosts allow a page to read
 * them (CORS), each opened in okay-desk by one link — or several ticked and opened together. Every entry was checked:
 * the host answers with `access-control-allow-origin: *` and the file is a CSV or a JSON array.
 */
object Examples:
  final case class Example(name: String, urls: Vector[String], about: String, source: String)
  private val raw = "https://raw.githubusercontent.com/"
  val all: Vector[Example] = Vector(
    Example("The sample: orders, customers, edges", Vector("sample/orders.csv", "sample/customers.csv", "sample/edges.csv"),
      "Eight orders, five customers and a service graph — the tables the readers are measured on. Try: how many orders are unpaid? total amount per country? what does web depend on?",
      "okay-desk"),
    Example("Airports of the USA", Vector(raw + "vega/vega-datasets/main/data/airports.csv"),
      "3,376 airports: code, name, city, state, country, coordinates. Try: how many airports are in state AK? which airports are in city Boston?",
      "vega-datasets"),
    Example("Cars, 1970–1982", Vector(raw + "vega/vega-datasets/main/data/cars.json"),
      "406 cars: miles per gallon, cylinders, horsepower, weight, origin. Try: average horsepower per origin? the five heaviest cars?",
      "vega-datasets"),
    Example("Seattle weather, 2012–2015", Vector(raw + "vega/vega-datasets/main/data/seattle-weather.csv"),
      "1,461 days: precipitation, temperatures, wind, weather. Try: how many days with weather = snow? the warmest day?",
      "vega-datasets"),
    Example("S&P 500 companies", Vector(raw + "datasets/s-and-p-500-companies/main/data/constituents.csv"),
      "503 companies: symbol, sector, sub-industry, headquarters, date added. Try: how many companies per GICS Sector?",
      "datasets/s-and-p-500-companies"),
    Example("Population of countries by year", Vector(raw + "datasets/population/main/data/population.csv"),
      "The World Bank's series, 17,000 rows: country, code, year, value. Try: the population of Ukraine in 2020? the ten largest in 2023?",
      "datasets/population"),
    Example("GDP of countries by year", Vector(raw + "datasets/gdp/main/data/gdp.csv"),
      "The World Bank's series: country, code, year, value in current US dollars.", "datasets/gdp"),
    Example("COVID-19, the latest figures by country", Vector(raw + "owid/covid-19-data/master/public/data/latest/owid-covid-latest.csv"),
      "Our World in Data's last snapshot: cases, deaths, vaccinations, population, per country. Try: the ten countries with the most total cases?",
      "owid/covid-19-data"),
    Example("New York City 311 complaints (the latest 2,000)", Vector("https://data.cityofnewyork.us/resource/erm2-nwe9.csv?$limit=2000"),
      "Service requests: agency, complaint type, borough, status, dates. Any Socrata open-data portal answers the same way. Try: how many complaints per borough?",
      "NYC Open Data"))

  /** the desk's address with these files to open */
  def link(urls: Vector[String]): String =
    "index.html?" + urls.map(u => "open=" + scala.scalajs.js.URIUtils.encodeURIComponent(u)).mkString("&")

  /** the screen: each example a card with its link; a form of ticks, and one button that opens the ticked together —
   * the program's value is the addresses chosen */
  def program[R[_]](using In[Ui, R]): Vector[String] ! R =
    val ticks = Form.list(all.toList.map(e => Field.flag(e.name)))
    val cards = all.map(e => Part.block("example", e.name)(Part.text(e.about), Part.block("small")(Part.text(e.urls.mkString("  "))),
      Part.text(s"Source: ${e.source}"), Part.link("Open in okay-desk", link(e.urls))))
    show[Vector[String], R](Screen[Vector[String]]("Examples of open data")((List(
      Part.block("top")(Part.block("brand")(Part.text("okay-desk")), Part.link("← okay-desk", "index.html")),
      Part.text("Each file comes straight from its host into your browser; okay-desk reads it there and nothing leaves the page. " +
        "A host must allow cross-origin reads for that — these do. Open one, or tick several and open them together: " +
        "every file becomes a table, and a question may join them.")) ++ cards :+ Part.section("Open several together")(ticks))*)(
      Button.submit("Open the ticked together", ticks)(on => all.zip(on).filter(_._2).flatMap(_._1.urls))))
