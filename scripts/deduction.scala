//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Real 12-layer reader on forced WebGL2, then the common chat's compiled inlay inference. The HTTP evidence is
  * explicitly a fixture. This is a host-GPU browser test, not a physical-phone performance or memory measurement.
  */
@main def deduction(url: String): Unit =
  val exe = sys.env.getOrElse(
    "CHROME",
    sys.props("user.home") +
      "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
      "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  )
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)).setHeadless(false))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  val page = context.newPage()
  page.setDefaultTimeout(180000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  val externalModels = scala.collection.mutable.ArrayBuffer.empty[String]
  var searches = 0
  page.onPageError(e => { errors += e; () })
  page.onRequest(r =>
    if r.url().contains("huggingface.co") || r.url().contains("localhost:809") then { externalModels += r.url(); () }
  )
  page.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  page.route(
    "https://esm.run/**",
    route =>
      route.fulfill(
        Route
          .FulfillOptions()
          .setContentType("text/javascript")
          .setHeaders(Map("access-control-allow-origin" -> "*").asJava)
          .setBody("export {};")
      )
  )
  val extract = "Europa is an instance of moon. Being a moon entails being a natural satellite. " +
    "The class natural satellite is included in the class celestial body."
  val wiki = s"""{"curtimestamp":"2026-11-08T16:00:00Z","query":{"pages":[
    |{"pageid":50,"lastrevid":9,"title":"Taxonomy",
    |"extract":"$extract"}]}}""".stripMargin
  page.route(
    "https://en.wikipedia.org/w/api.php?*",
    route => {
      searches += 1
      route.fulfill(
        Route
          .FulfillOptions()
          .setHeaders(Map("access-control-allow-origin" -> "*").asJava)
          .setContentType("application/json")
          .setBody(wiki)
      )
    }
  )
  def ask(text: String): Unit =
    page.locator("textarea").fill(text)
    page.click("button:text-is('Ask')")
  try
    page.navigate(url + "?open=sample/orders.csv&models")
    assert(page.locator("select option:checked").innerText().contains("12 layers"), "wrong browser default model")
    page.click("button:text-is('Load the model')")
    page.waitForFunction("() => document.body.textContent.includes('(12 layers) and the readers on WebGL2')")
    println("real 12-layer student loaded on WebGL2")
    ask("How many orders are unpaid?")
    page.waitForSelector(".okay-mark-result")
    val table = page.locator(".okay-mark-result").innerText()
    assert(table.contains("4"), table)
    assert(page.locator("body").innerText().contains("read from the model's states"))
    println("real 12-layer student: data reading on WebGL2 passed")
    ask("Is Europa a celestial body?")
    page.click("text=\"Use online sources\"")
    page.waitForFunction("() => document.body.textContent.includes('Unknown:')")
    assert(page.locator("body").innerText().contains(
      "The fact reader abstained on these source sentences: Being a moon entails being a natural satellite."))
    val clarified = "Each moon belongs to the class natural satellite. Is Europa a celestial body?"
    ask(clarified)
    page.waitForFunction("() => document.body.textContent.includes('Proved: europa is a celestial body.')")
    var body = page.locator("body").innerText()
    assert(body.contains("Exact inlay Horn inference") &&
      body.contains("Each moon belongs to the class natural satellite.") && body.contains("Model read:"), body)
    assert(page.locator("a[href*='curid=50&oldid=9']").count() > 0)
    assert(searches == 2 && externalModels.isEmpty)
    assert(!body.contains("model-reviewed") && !body.contains("Draft"), body)
    context.setOffline(true)
    ask(clarified)
    page.waitForFunction(
      "() => Array.from(document.querySelectorAll('.okay-mark-model')).filter(" +
        "e => e.textContent.includes('Proved: europa is a celestial body.')).length >= 2"
    )
    ask("Europa is outside the class planet. Is Europa a planet?")
    page.waitForFunction("() => document.body.textContent.includes('Refuted: europa is a planet.')")
    ask("Moby is not a fish. Is Moby a fish?")
    page.waitForFunction("() => document.body.textContent.includes('Refuted: moby is a fish.')")
    ask("2 + 2\n3 * 7")
    page.waitForFunction("() => document.body.textContent.includes('2 + 2 = 4') && " +
      "document.body.textContent.includes('3 * 7 = 21')")
    body = page.locator("body").innerText()
    assert(searches == 2, "cached premises were fetched again")
    assert(errors.isEmpty, errors.mkString("\n"))
    // A model reload must also work offline, including its manifest, and release the previous WebGL2 device.
    page.click("button:text-is('Load the model')")
    page.waitForFunction("() => document.body.textContent.includes('(12 layers) and the readers on WebGL2') && " +
      "!document.querySelector('.okay-mark-loading')")
    ask("How many orders are unpaid?")
    page.waitForFunction("() => document.querySelectorAll('.okay-mark-result').length >= 2")
    assert(page.locator(".okay-mark-result").last().innerText().contains("4"))
    assert(errors.isEmpty, errors.mkString("\n"))
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-deduction-webgl2.png")).setFullPage(true))
    println("common chat: neural source reading, abstention, multi-hop proof, " +
      "offline reuse and negative reading passed")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(5000))
      println(errors.mkString("\n"))
      throw e
  finally
    browser.close()
    pw.close()
