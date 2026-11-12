//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** REAL DATA IN THE PAGE (specs/data.md §28–§29): the page driven as a person would — the readers loaded, a real
 * file opened (the GDP series), questions asked in English and Russian — each answer's program printed: the data's
 * own words (words/<table>.json) read in the browser as on the JVM.
 * `scala-cli run scripts/real.scala -- <page url> <data.csv> [question…]` */
@main def real(url: String, data: String, asked: String*): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)).setHeadless(false)
    .setArgs(List("--enable-unsafe-webgpu", "--enable-features=WebGPU").asJava))
  val p = browser.newContext().newPage()
  p.onPageError(e => println("page error: " + e.take(600)))
  p.setDefaultTimeout(sys.env.get("STEP_TIMEOUT").map(_.toInt).getOrElse(600000))
  def stuck(where: String): Nothing =
    p.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/real-stuck.png")))
    println(s"stuck at $where: " + p.locator("body").textContent().replaceAll("\\s+", " ").takeRight(600)); sys.exit(1)
  p.navigate(url + (if url.contains("?") then "&" else "?") + "models")
  p.waitForSelector("button:text-is('Load the model')")
  p.click("button:text-is('Load the model')")
  try p.waitForFunction("() => /readers on|cannot load/.test(document.body.textContent)") catch case _: Throwable => stuck("loading")
  println("loaded")
  p.click("text=\"Data: open files\"")
  p.waitForSelector("input[type=file]")
  p.locator("input[type=file]:not([webkitdirectory])").setInputFiles(Path.of(data))
  p.waitForFunction("() => /==> /.test(document.querySelector('textarea').value)")
  p.click("button:text-is('Open the files')")
  try p.waitForFunction("() => document.querySelectorAll('.okay-mark-tree-node, .okay-mark-tree-node-open').length >= 1")
  catch case _: Throwable => stuck("opening")
  println("opened")
  p.click("text=\"← Back to the chat\"")
  p.waitForSelector("textarea[id^=f-ask]")
  val typed = asked.filterNot(_.startsWith("--"))
  val questions = if typed.nonEmpty then typed else Seq("Какой был ВВП Украины в 2020 году?", "В каком году ВВП Польши был самым высоким?",
    "Средний ВВП в 2010 году?", "Самый большой ВВП за всё время.", "Какую долю мирового ВВП имел Китай в 2023 году?")
  for q <- questions do
    val before = p.locator(".okay-mark-result").count()
    p.fill("textarea[id^=f-ask]", q); p.click("button:text-is('Ask')")
    // an answer, or a refusal (no result: the model's line says why)
    val models = p.locator(".okay-mark-model").count()
    try p.waitForFunction(s"() => document.querySelectorAll('.okay-mark-result').length > $before || " +
      s"document.querySelectorAll('.okay-mark-model').length > $models")
    catch case _: Throwable => stuck(s"asking $q")
    val got = if p.locator(".okay-mark-result").count() > before then p.locator(".okay-mark-result").last().textContent()
      else "refused: " + p.locator(".okay-mark-model").last().textContent()
    println(s"Q: $q → " + got.replaceAll("\\s+", " ").take(260))
  browser.close(); pw.close()
