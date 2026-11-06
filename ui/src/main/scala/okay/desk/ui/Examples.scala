package okay.desk.ui

/**
 * EXAMPLES OF OPEN DATA: files on the internet whose hosts allow a page to read them (CORS), each opened in the desk
 * by one press — or several together. Every entry was checked: the host answers with `access-control-allow-origin:
 * *` and the file is a CSV or a JSON array.
 */
object Examples:
  final case class Example(key: String, name: String, urls: Vector[String], about: String, source: String)
  private val raw = "https://raw.githubusercontent.com/"
  val all: Vector[Example] = Vector(
    Example("sample", "The sample: orders, customers, edges", Vector("sample/orders.csv", "sample/customers.csv", "sample/edges.csv"),
      "Eight orders, five customers and a service graph — the tables the readers are measured on. Try: how many orders are unpaid? total amount per country? what does web depend on?",
      "okay-desk"),
    Example("airports", "Airports of the USA", Vector(raw + "vega/vega-datasets/main/data/airports.csv"),
      "3,376 airports: code, name, city, state, country, coordinates. Try: how many airports are in state AK? which airports are in city Boston?",
      "vega-datasets"),
    Example("cars", "Cars, 1970–1982", Vector(raw + "vega/vega-datasets/main/data/cars.json"),
      "406 cars: miles per gallon, cylinders, horsepower, weight, origin. Try: average horsepower per origin? the five heaviest cars?",
      "vega-datasets"),
    Example("weather", "Seattle weather, 2012–2015", Vector(raw + "vega/vega-datasets/main/data/seattle-weather.csv"),
      "1,461 days: precipitation, temperatures, wind, weather. Try: how many days with weather = snow? the warmest day?",
      "vega-datasets"),
    Example("sp500", "S&P 500 companies", Vector(raw + "datasets/s-and-p-500-companies/main/data/constituents.csv"),
      "503 companies: symbol, sector, sub-industry, headquarters, date added. Try: how many companies per GICS Sector?",
      "datasets/s-and-p-500-companies"),
    Example("population", "Population of countries by year", Vector(raw + "datasets/population/main/data/population.csv"),
      "The World Bank's series, 17,000 rows: country, code, year, value. Try: the population of Ukraine in 2020? the ten largest in 2023?",
      "datasets/population"),
    Example("gdp", "GDP of countries by year", Vector(raw + "datasets/gdp/main/data/gdp.csv"),
      "The World Bank's series: country, code, year, value in current US dollars. Try: where GDP grows fastest? the GDP of Ukraine in 2020?",
      "datasets/gdp"),
    Example("covid", "COVID-19, the latest figures by country", Vector(raw + "owid/covid-19-data/master/public/data/latest/owid-covid-latest.csv"),
      "Our World in Data's last snapshot: cases, deaths, vaccinations, population, per country. Try: the ten countries with the most total cases?",
      "owid/covid-19-data"),
    Example("nyc311", "New York City 311 complaints (the latest 2,000)", Vector("https://data.cityofnewyork.us/resource/erm2-nwe9.csv?$limit=2000"),
      "Service requests: agency, complaint type, borough, status, dates. Any Socrata open-data portal answers the same way. Try: how many complaints per borough?",
      "NYC Open Data"))
