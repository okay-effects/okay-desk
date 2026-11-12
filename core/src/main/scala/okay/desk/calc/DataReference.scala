package okay.desk.calc

import okay.desk.Table
import okay.desk.skill.Words

/** The routing gate uses the same dataset names and localized forms as the data readers, before weights load. */
object DataReference:
  def matches(tables: Map[String, Table], question: String, words: Words = Words.none,
      languages: Vector[Language] = Vector(Language.En.language, Language.Ru.language)): Boolean =
    def normal(text: String): String =
      text.toLowerCase.replace('ё', 'е').split("[^\\p{L}\\p{N}]+").filter(_.nonEmpty).mkString(" ")
    val q = " " + normal(question) + " "
    def written(text: String): Boolean =
      val n = normal(text)
      n.nonEmpty && q.contains(" " + n + " ")
    def localized(form: String): Boolean =
      val n = normal(form)
      val cyrillic = n.exists(c => c >= 'а' && c <= 'я')
      val stem = if n.length <= 3 then n else n.dropRight(if n.length > 6 then 2 else 1)
      written(n) || cyrillic && q.matches(s"(?U).*\\b${java.util.regex.Pattern.quote(stem)}\\p{L}{0,5}\\b.*")
    tables.valuesIterator.exists { table =>
      val tableForms = words.table(table.name).toVector.flatMap((a, b, c, d) => Vector(a, b, c, d)) ++
        languages.flatMap(_.tables.getOrElse(table.name, Vector.empty))
      val columnForms = table.columns.flatMap { c =>
        words.column(c).toVector.flatMap((a, b, d) => Vector(a, b, d)) ++
          languages.flatMap(_.columns.getOrElse(c, Vector.empty))
      }
      (table.name +: table.columns).exists(written) || (tableForms ++ columnForms).exists(localized) ||
        table.rows.iterator.flatMap(_.iterator).exists { value =>
          written(value.text) || (words.value(value.text).toVector ++
            languages.flatMap(_.values.get(value.text))).exists(localized)
        }
    }

  def asksCause(question: String): Boolean =
    "(?iU)\\b(?:why|почему|чому|dlaczego)\\b".r.findFirstIn(question).nonEmpty
