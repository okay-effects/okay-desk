// THE PHONE BENCH'S SERVER (specs/phone.md §5): the published page served on the local network with bench.js put
// into it; every other file fetched once from the published site and kept on disk; POST /log appended to a file.
// scala-cli run scripts/phonebench/server.scala -- <port> <cache dir> <log file>
// The phone opens http://<this Mac's address>:<port>/ — the bench starts by itself and logs here.
//> using scala 3.3.8
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.LocalTime
import java.util.concurrent.Executors

val upstream = "https://okay-effects.github.io/okay-desk"
val here = Path.of(sys.props.getOrElse("bench.dir", "scripts/phonebench"))

@main def phoneBench(port: Int, cacheDir: String, logFile: String): Unit =
  val cache = Path.of(cacheDir); Files.createDirectories(cache)
  val log = Path.of(logFile); Files.createDirectories(log.getParent)
  val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
  val lock = new Object

  def kind(p: String): String =
    if p.endsWith(".html") then "text/html; charset=utf-8"
    else if p.endsWith(".js") then "text/javascript; charset=utf-8"
    else if p.endsWith(".json") then "application/json"
    else if p.endsWith(".csv") || p.endsWith(".txt") then "text/plain; charset=utf-8"
    else "application/octet-stream"

  /** the published file, from the disk when fetched before; None when the site has none */
  def file(path: String): Option[Path] = lock.synchronized {
    val local = cache.resolve(path.stripPrefix("/"))
    if Files.exists(local) then Some(local)
    else
      val r = client.send(HttpRequest.newBuilder(URI.create(upstream + path)).build(),
        HttpResponse.BodyHandlers.ofByteArray())
      if r.statusCode != 200 then None
      else
        Files.createDirectories(local.getParent)
        val part = local.resolveSibling(local.getFileName.toString + ".part")
        Files.write(part, r.body); Files.move(part, local)
        println(s"fetched $path (${r.body.length / 1024} KB)")
        Some(local)
  }

  def send(x: HttpExchange, status: Int, kindOf: String, body: Array[Byte]): Unit =
    x.getResponseHeaders.set("Content-Type", kindOf)
    x.getResponseHeaders.set("Cache-Control", "no-store")
    x.sendResponseHeaders(status, if body.isEmpty then -1 else body.length)
    if body.nonEmpty then x.getResponseBody.write(body)
    x.close()

  val server = HttpServer.create(InetSocketAddress(port), 64)
  server.setExecutor(Executors.newFixedThreadPool(16))
  server.createContext("/", x =>
    try
      val path = x.getRequestURI.getPath
      if x.getRequestMethod == "POST" && path == "/log" then
        val line = String(x.getRequestBody.readAllBytes(), UTF_8).replace("\n", " ")
        val from = x.getRemoteAddress.getAddress.getHostAddress
        val entry = s"${LocalTime.now.withNano(0)} $from $line\n"
        lock.synchronized(Files.writeString(log, entry, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
        print(entry.take(300) + (if entry.length > 300 then "…\n" else ""))
        send(x, 204, "text/plain", Array.emptyByteArray)
      else if path == "/bench.js" then
        send(x, 200, kind(path), Files.readAllBytes(here.resolve("bench.js")))
      else if path == "/" || path == "/index.html" then
        file("/index.html") match
          case None => send(x, 502, "text/plain", "the published page is unreachable".getBytes(UTF_8))
          case Some(f) =>
            val page = Files.readString(f, UTF_8)
              .replace("</body>", "<script src=\"bench.js\"></script>\n</body>")
            send(x, 200, kind(".html"), page.getBytes(UTF_8))
      else
        file(path) match
          case None => send(x, 404, "text/plain", Array.emptyByteArray)
          case Some(f) =>
            x.getResponseHeaders.set("Content-Type", kind(path))
            x.getResponseHeaders.set("Cache-Control", "no-store")
            x.sendResponseHeaders(200, Files.size(f))
            Files.copy(f, x.getResponseBody); x.close()
    catch case e: Throwable =>
      println(s"error ${x.getRequestURI}: $e")
      try x.close() catch case _: Throwable => ()
  )
  server.start()
  println(s"phone bench on port $port; log $log")
