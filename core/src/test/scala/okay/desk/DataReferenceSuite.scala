package okay.desk

import okay.desk.calc.DataReference
import okay.desk.skill.Words

class DataReferenceSuite extends munit.FunSuite:
  val table = Table.parse("gdp.csv", "Country Name,Year,Value\nUkraine,2020,100\nPoland,2020,200\n").toOption.get
  val words = Words(tables = Map("gdp" -> "ввп", "population" -> "население"),
    columns = Map("Country Name" -> "страна", "Year" -> "год", "Value" -> "значение"),
    values = Map("Ukraine" -> "украина", "Poland" -> "польша", "Canada" -> "канада"))
  val data = Map("gdp" -> table)

  test("dataset aliases route the screenshot question, not just literal schema names"):
    val q = "Где ВВП растет быстрее всего и почему?"
    assert(!DataReference.matches(data, q))
    assert(DataReference.matches(data, q, words))
    assert(DataReference.matches(data, "Где ВВП растёт быстрее всего?", words))
    assert(DataReference.matches(data, "Where does GDP grow fastest?", words))

  test("localized columns and inflected values refer only to currently opened data"):
    assert(DataReference.matches(data, "Как изменились значения?", words))
    assert(DataReference.matches(data, "Какой показатель Украины?", words))
    assert(DataReference.matches(data, "Покажи население", words.copy(tables = Map("gdp" -> "население"))))
    for q <- Vector("Why does rain fall?", "Почему идёт дождь?", "Покажи Канаду", "Покажи население", "gdpish") do
      assert(!DataReference.matches(data, q, words), q)
    assert(!DataReference.matches(Map.empty, "ВВП Украины", words))
