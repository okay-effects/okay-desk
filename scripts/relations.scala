//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Real online research and model reading in the ordinary chat, forced WebGL2 at phone width. */
@main def relations(url: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
    .setContentType("text/javascript").setBody("export {};")))
  val page = context.newPage()
  page.setDefaultTimeout(120000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  var liveRequests = 0
  page.onPageError(e => { errors += e; () })
  page.onRequest(r => if r.url().startsWith("https://en.wikipedia.org/w/api.php") then { liveRequests += 1; () })
  def ask(text: String): Unit =
    page.locator("textarea").fill(text)
    page.click("button:text-is('Ask')")
  try
    page.navigate(url)
    val options = page.locator("select option").allTextContents().asScala.toVector
    assert(options.size == 4 && options.count(_.contains("no generation")) == 1 &&
      options.count(_.contains("generates")) == 3, options.mkString("\n"))
    assert(page.locator("select option:checked").innerText().contains("12 layers"))
    ask("What is the capital of France?")
    page.click("text=\"Use online sources\"")
    page.waitForFunction("() => document.body.textContent.includes('Answer: paris is the capital of france.') || " +
      "document.body.textContent.includes('insufficient to settle') || document.body.textContent.includes('Unknown:')")
    var body = page.locator("body").innerText()
    assert(body.contains("Answer: paris is the capital of france."), body)
    assert(body.contains("(12 layers) and the readers on WebGL2") && body.contains("Model read:"), body)
    assert(liveRequests > 0 && page.locator("a[href*='oldid=']").count() > 0)
    println("live Wikipedia -> actual 12-layer WebGL2 reader -> exact capital answer passed")
    ask("Where is Poland?")
    page.waitForFunction("() => document.body.textContent.includes('Answer: poland is located in central europe.')")
    val fixtureUrl = "https://example.org/okay-desk-relations.html"
    context.route(fixtureUrl, route => route.fulfill(Route.FulfillOptions()
      .setContentType("text/html").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
      .setBody("""<html><head><title>Controlled document fixture</title></head><body><main>
        |<p>North Harbor is located in Coastland.</p><p>Coastland is located in Mainland.</p>
        |<p>If X is located in Y and Y is located in Z, then X is located in Z.</p>
        |<script>window.sourceScriptExecuted=true;</script></main></body></html>""".stripMargin)))
    val question = fixtureUrl + "\nIs North Harbor in Mainland?"
    ask(question)
    page.waitForFunction("() => document.body.textContent.includes('Proved: north harbor is located in mainland.')")
    assert(page.evaluate("() => window.sourceScriptExecuted === undefined").asInstanceOf[Boolean])
    assert(page.locator(s"a[href='$fixtureUrl']").count() > 0)
    context.setOffline(true)
    ask(question)
    page.waitForFunction("() => Array.from(document.querySelectorAll('.okay-mark-model')).filter(" +
      "e => e.textContent.includes('Proved: north harbor is located in mainland.')).length >= 2")
    body = page.locator("body").innerText()
    assert(errors.isEmpty, errors.mkString("\n"))
    assert(body.contains("Exact inlay Horn inference"))
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-relations-webgl2.png")).setFullPage(true))
    println("location, sourced composition rule, inert HTML and offline repeat passed")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(10000))
      throw e
  finally
    browser.close()
    pw.close()
