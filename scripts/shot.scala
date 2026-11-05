//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{BrowserType, Playwright}
import com.microsoft.playwright.options.ScreenshotType
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** THE PAGE DRIVEN AS A PERSON WOULD (okay-ui's DOM face) and a screenshot for the site: the readers loaded, the
 * sample's files chosen, two questions answered, a table closed. Prints what the page says at each step.
 * `scala-cli run scripts/shot.scala -- <page url> <sample dir> <out.png>` */
@main def shot(url: String, sample: String, out: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)).setHeadless(false)
    .setArgs(List("--enable-unsafe-webgpu", "--enable-features=WebGPU").asJava))
  val ctx = browser.newContext(com.microsoft.playwright.Browser.NewContextOptions().setViewportSize(1100, 900).setDeviceScaleFactor(2))
  val p = ctx.newPage()
  p.onConsoleMessage(m => if m.`type`() == "error" || m.text().contains("Exception") then println("console: " + m.text().take(600)))
  p.onPageError(e => println("page error: " + e.take(600)))
  p.setDefaultTimeout(sys.env.get("STEP_TIMEOUT").map(_.toInt).getOrElse(600000))
  def body() = p.locator("body").textContent()
  def snap(name: String): Unit = p.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of(out).resolveSibling(s"$name.png")).setType(ScreenshotType.PNG))
  p.navigate(url)
  p.waitForSelector("text=Load the model")
  try
    p.click("button:text-is('Load the model')")
    p.waitForFunction("() => /readers on|cannot load/.test(document.body.textContent)")
  catch
    case e: Throwable =>
      snap("stuck"); println("stuck at loading: " + body().take(400)); throw e
  println("model: " + (if body().contains("readers on") then "loaded" else "FAILED"))
  p.locator("input[type=file]:not([webkitdirectory])").setInputFiles(Seq("orders.csv", "customers.csv", "edges.csv").map(f => Path.of(sample, f)).toArray)
  p.waitForFunction("() => /==> /.test(document.querySelector('textarea').value)")
  p.click("button:text-is('Open the files')")
  p.waitForFunction("() => /opened edges.csv/.test(document.body.textContent)")
  println("files: opened " + p.locator(".okay-mark-table").count() + " tables")
  for q <- Seq("How many orders are unpaid?", "Total amount per country.") do
    val before = p.locator(".okay-mark-result").count()
    p.fill("input[id^=f-ask]", q); p.click("button:text-is('Ask')")
    try p.waitForFunction(s"() => document.querySelectorAll('.okay-mark-result').length > $before")
    catch
      case e: Throwable =>
        snap("stuck-ask"); println("stuck at asking; the page: " + body().replaceAll("\\s+", " ").takeRight(700))
        println("marks: " + p.evaluate("() => Array.from(document.querySelectorAll('[class*=okay-mark-]')).map(e => e.className).join(' ')"))
        throw e
    println(s"Q: $q → " + p.locator(".okay-mark-result").last().textContent().take(160).replace("\n", " ⏎ "))
  p.click("text=\"Close edges\"")
  p.waitForFunction("() => /closed edges/.test(document.body.textContent)")
  println("after a close: " + p.locator(".okay-mark-table").count() + " tables")
  p.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of(out)).setType(ScreenshotType.PNG).setFullPage(sys.env.contains("FULL")))
  browser.close(); pw.close()
  println(s"written $out")
