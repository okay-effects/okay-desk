package okay.desk.know

/** Metadata and bounds for fetched documents. HTML is read as inert text by the browser, never attached to the page. */
object SourceDocument:
  def validUrl(url: String): Boolean =
    url.length <= 2048 && url.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:[/?][^\\s<>\"#]*)?")
  def contentType(headers: Map[String, String]): String =
    headers.find(_._1.equalsIgnoreCase("content-type")).map(_._2.takeWhile(_ != ';').trim.toLowerCase)
      .getOrElse("text/plain")
  def create(url: String, title: String, text: String, date: String): Either[String, Knowledge.Source] =
    if !validUrl(url) then Left("An HTTPS document address is required.")
    else if !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") then Left("Document retrieval date is missing.")
    else if text.trim.isEmpty then Left("The document contains no readable text.")
    else Right(Knowledge.Source("url:" + url + ":" + Integer.toHexString(text.hashCode), title.take(200),
      url, text.trim.take(12000), date))
