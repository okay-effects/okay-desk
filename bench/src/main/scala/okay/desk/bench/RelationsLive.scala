package okay.desk.bench

import okay.desk.know.{Deduction, FactReader, Knowledge, Relations}
import okay.desk.skill.Read
import okay.llm.compute.OnMlx
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}

/** Live source development regression audit. Inputs and all accepted interpretations are printed. */
object RelationsLive:
  def main(args: Array[String]): Unit =
    val home = sys.props("user.home")
    val dir = Path.of(sys.env.getOrElse("SMALL_DIR", home +
      "/.cache/huggingface/hub/models--local--Qwen3-0.6B-4bit-cut19-vocab-p12-m4/snapshots/main"))
    val classes = FactReader.parse(Files.readString(Path.of(home + "/.cache/okay-desk/facts-readers-small.json")))
      .fold(sys.error, identity)
    val primary = Relations.parse(Files.readString(Path.of(home + "/.cache/okay-desk/relations-readers-small.json")))
      .fold(sys.error, identity)
    val on = ComputeReading(dir, 12, OnMlx())
    val url = "https://en.wikipedia.org/w/api.php?action=query&titles=Paris%7CPoland%7CMoon" +
      "&prop=extracts%7Cinfo&explaintext=1&exintro=1&inprop=url&format=json&formatversion=2&curtimestamp=1&origin=*"
    val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
      .header("User-Agent", "okay-desk-source-audit/1.0").GET().build(), HttpResponse.BodyHandlers.ofString())
    require(response.statusCode() == 200, s"Wikipedia HTTP ${response.statusCode()}")
    val sources = Knowledge.searchResults(response.body(), "en").fold(sys.error, identity)
    val readings = sources.flatMap(FactReader.sentences).flatMap { q =>
      val result = Read.run(FactReader.features[Read](q.text), on)
        .flatMap(f => FactReader.combined(primary, classes, q.source, q.text, f))
      result.foreach(r => println(s"${q.source}: ${q.text}\n  => ${FactReader.meaning(r)}"))
      result
    }
    val allowed = Set(Deduction.Atom("paris", "france", relation = "capital-of"),
      Deduction.Atom("poland", "central europe", relation = "located-in"))
    require(readings.forall(r => FactReader.meaning(r).exists {
      case Deduction.Meaning.Fact(a) => allowed(a)
      case _ => false
    }), "a live document produced an unsupported interpretation; do not ship")
    for q <- Vector("What is the capital of France?", "Where is Poland?") do
      val plan = Deduction.plan(q, sources, readings).get
      val out = Deduction.run(q, sources, plan, learned = readings).fold(sys.error, identity)
      println(s"$q => ${out.status}: ${out.answers.map(_.show)}")
      require(out.status == Deduction.Status.Proven, s"live source reading failed: $q")
      if q.contains("France") then require(out.answers.exists(a => a.subject == "paris" && a.category == "france"))
      else require(out.answers.exists(a => a.subject == "poland" && a.category == "central europe"))
    args.headOption.foreach(p => Files.writeString(Path.of(p), Knowledge.cache("live relation audit", sources)))
