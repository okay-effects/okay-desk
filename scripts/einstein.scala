//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Reported zebra and fish texts, real 12-layer model on forced WebGL2 and CPU Worker inference. */
@main def einstein(url: String): Unit =
  val text = Files.readString(Path.of("core/src/test/resources/puzzles/einstein-ru.txt"))
  val fish = Files.readString(Path.of("core/src/test/resources/puzzles/einstein-fish-ru.txt"))
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
  var externalFacts = 0
  var workers = 0
  page.onPageError(e => { errors += e; () })
  page.onWorker(_ => { workers += 1; () })
  page.onRequest(r => if r.url().contains("wikipedia.org") || r.url().contains("query.wikidata.org") then
    { externalFacts += 1; () })
  def ask(q: String): Unit =
    page.locator("textarea").fill(q)
    page.click("button:text-is('Ask')")
  def waitAnswers(): Unit =
    page.waitForFunction("() => document.body.textContent.includes('Норвежец — вода.') && " +
      "document.body.textContent.includes('Японец — зебра.')")
  try
    page.navigate(url + (if url.contains("?") then "&" else "?") + "models")
    // Keep the actual phone model resident: the exact solver must work alongside its WebGL2 device.
    page.click("button:text-is('Load the model')")
    page.waitForFunction("() => document.body.textContent.includes('(12 layers) and the readers on WebGL2')")
    ask(text)
    waitAnswers()
    var body = page.locator("body").innerText()
    assert(page.locator(".okay-mark-result").count() == 1, body)
    assert(body.contains("Read categories") && body.contains("сразу справа"), body)
    assert(page.locator("text=\"Use online sources\"").count() == 0 && externalFacts == 0)
    assert(workers > 0 && errors.isEmpty, errors.mkString("\n"))
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-einstein-webgl2.png")).setFullPage(true))
    context.setOffline(true)
    page.click("text=\"Clear chat\"")
    ask(text.replace('ё', 'е'))
    waitAnswers()
    body = page.locator("body").innerText()
    assert(body.contains("(12 layers) and the readers on WebGL2") && errors.isEmpty, body)
    println("reported Einstein text: 12-layer WebGL2 resident, CPU Worker, both answers and offline е variant passed")
    page.click("text=\"Clear chat\"")
    ask(fish)
    page.waitForFunction("() => document.body.textContent.includes('Немец — рыба.')")
    body = page.locator("body").innerText()
    assert(page.locator(".okay-mark-result").count() == 1 && body.contains("Read categories"), body)
    assert(body.contains("сразу справа") && body.contains("Rothmans") && body.contains("Winfield"), body)
    assert(page.locator("text=\"Use online sources\"").count() == 0 && externalFacts == 0 && errors.isEmpty)
    assert(page.evaluate("""() => [...document.querySelectorAll('.okay-mark-user .okay-text')]
      .every(e => e.scrollWidth <= e.clientWidth + 1)""").asInstanceOf[Boolean], "question text must wrap")
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-fish-webgl2.png")).setFullPage(true))
    println("reported fish text: offline WebGL2 resident, CPU Worker, new brands and exact fish answer passed")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(15000))
      throw e
  finally
    context.close()
    browser.close()
    pw.close()
