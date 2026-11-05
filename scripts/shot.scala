//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{BrowserType, Playwright}
import com.microsoft.playwright.options.{ScreenshotType, ViewportSize}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** A SCREENSHOT OF THE PAGE in the readers' mode, for the site: the sample's files opened, two questions answered,
 * the second's program unfolded. `scala-cli run scripts/shot.scala -- <page url> <sample dir> <out.png>` */
@main def shot(url: String, sample: String, out: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)).setHeadless(false)
    .setArgs(List("--enable-unsafe-webgpu", "--enable-features=WebGPU").asJava))
  val ctx = browser.newContext(com.microsoft.playwright.Browser.NewContextOptions().setViewportSize(1100, 760).setDeviceScaleFactor(2))
  val p = ctx.newPage()
  p.setDefaultTimeout(600000)
  p.navigate(url)
  p.selectOption("select", "readers")
  p.click("text=Load the model")
  p.waitForFunction("() => /readers on/.test(document.querySelector('.status').textContent)")
  p.setInputFiles("#files", Seq("orders.csv", "customers.csv", "edges.csv").map(f => Path.of(sample, f)).toArray)
  for q <- Seq("How many orders are unpaid?", "Total amount per country.") do
    val before = p.locator(".msg.result").count()
    p.fill("input.q", q); p.press("input.q", "Enter")
    p.waitForFunction(s"() => document.querySelectorAll('.msg.result').length > $before")
  p.locator(".msg.result").last().locator("details summary").click()
  p.waitForTimeout(500)
  p.screenshot(com.microsoft.playwright.Page.ScreenshotOptions().setPath(Path.of(out)).setType(ScreenshotType.PNG))
  browser.close(); pw.close()
  println(s"written $out")
