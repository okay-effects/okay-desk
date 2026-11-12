//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Regression from the phone report: a localized GDP query must use opened data before model loading. */
@main def gdp(url: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
    .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
    .setBody("export {};")))
  val page = context.newPage()
  page.setDefaultTimeout(180000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  val requests = scala.collection.mutable.ArrayBuffer.empty[String]
  page.onPageError(e => { errors += e; () })
  page.onRequest(r => { requests += r.url(); () })
  val question = "Где ВВП растет быстрее всего и почему?"
  def ask(q: String): Unit =
    page.locator("textarea").fill(q)
    page.click("button:text-is('Ask')")
  try
    page.navigate(url + "?open=https://raw.githubusercontent.com/datasets/gdp/main/data/gdp.csv&models")
    page.waitForSelector("textarea")
    assert(page.locator("body").innerText().contains("the data: gdp"))
    assert(page.locator(".okay-mark-model-note").innerText().contains("not loaded yet"))
    ask(question)
    page.waitForFunction("() => document.querySelectorAll('.okay-mark-result').length > 0 || " +
      "document.body.textContent.includes('This question needs external evidence')")
    var body = page.locator("body").innerText()
    assert(page.locator(".okay-mark-result").count() == 1, body)
    assert(body.contains("(12 layers) and the readers on WebGL2"), body)
    val result = page.locator(".okay-mark-result").innerText()
    assert(result.contains("growth") && result.contains("Year") && result.contains("-growth"), result)
    assert(body.contains("не устанавливает экономические причины") && body.contains("это не годовой темп"), body)
    assert(page.locator("text=\"Use online sources\"").count() == 0)
    val dictionary = requests.indexWhere(_.contains("words/gdp.json"))
    val weights = requests.indexWhere(_.contains("model-small/config.json"))
    assert(dictionary >= 0 && weights > dictionary, requests.mkString("\n"))
    assert(!requests.exists(r => r.contains("wikipedia.org") || r.contains("query.wikidata.org")))
    assert(page.evaluate("""async () => {
      const c = await caches.open('okay-desk-model');
      return (await c.keys()).some(r => r.url.includes('/words/gdp.json'));
    }""").asInstanceOf[Boolean])
    page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions()
      .setPath(Path.of("/tmp/okay-desk-gdp-question.png")).setFullPage(true))
    context.setOffline(true)
    page.click("text=\"Clear chat\"")
    ask("Где ВВП растёт быстрее всего?")
    page.waitForSelector(".okay-mark-result")
    body = page.locator("body").innerText()
    assert(page.locator(".okay-mark-result").innerText().contains("growth"), body)
    assert(!body.contains("This question needs external evidence"), body)
    assert(errors.isEmpty, errors.mkString("\n"))
    page.screenshot(com.microsoft.playwright.Page.ScreenshotOptions()
      .setPath(Path.of("/tmp/okay-desk-gdp-routing.png")).setFullPage(true))
    println("GDP: dataset aliases before model loading, 12-layer WebGL2, exact growth, cause scope and offline passed")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(10000))
      throw e
  finally
    context.close()
    browser.close()
    pw.close()
