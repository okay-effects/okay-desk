//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Real 12-layer WebGL2 reader -> authored Datalog query -> CPU Worker -> checked proof in the same chat. */
@main def logicProof(url: String): Unit =
  val pw = Playwright.create()
  val exe = sys.props("user.home") + "/Library/Caches/ms-playwright/chromium-1234/" +
    "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
    .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
    .setBody("export {};")))
  val page = context.newPage()
  page.setDefaultTimeout(180000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  var workers = 0
  page.onPageError(error => { errors += error; () })
  page.onWorker(_ => { workers += 1; () })
  def ask(question: String): Unit =
    page.locator("textarea").fill(question)
    page.click("button:text-is('Ask')")
    page.waitForSelector(".okay-mark-proof")
    page.locator("button:text-is('Ask')").waitFor()
  try
    page.navigate(url + "?open=sample/edges.csv&open=sample/orders.csv&open=sample/customers.csv")
    page.waitForSelector("textarea")
    ask("What does web depend on, directly or through others?")
    val result = page.locator(".okay-mark-result").innerText()
    assert(Vector("checkout", "payments", "orders-db", "stripe").forall(result.contains), result)
    assert(!result.contains("catalog"), result)
    assert(page.locator("body").innerText().contains("(12 layers) and the readers on WebGL2"))
    assert(page.locator(".okay-mark-proof .okay-mark-code").count() == 0)
    page.click("text=\"Show proof\"")
    val proof = page.locator(".okay-mark-proof").innerText()
    assert(proof.contains("(given)") && proof.contains("reach(web") && proof.contains("inlay:"), proof)
    assert(workers > 0 && errors.isEmpty, errors.mkString("\n"))
    assert(page.evaluate("""() => document.documentElement.scrollWidth <= innerWidth + 2""")
      .asInstanceOf[Boolean], "the proof causes horizontal page overflow")
    page.click("text=\"Hide proof\"")
    assert(page.locator(".okay-mark-proof .okay-mark-code").count() == 0)
    context.setOffline(true)
    page.click("text=\"Clear chat\"")
    ask("От чего зависит web, прямо или через других?")
    val russian = page.locator(".okay-mark-result").innerText()
    assert(Vector("checkout", "payments", "orders-db", "stripe").forall(russian.contains), russian)
    assert(page.locator(".okay-mark-proof").innerText().contains("Логический вывод проверен"))
    page.click("text=\"Clear chat\"")
    ask("Which customers have no orders?")
    assert(page.locator(".okay-mark-result").innerText().contains("fay"))
    page.click("text=\"Show proof\"")
    val negative = page.locator(".okay-mark-proof").innerText()
    assert(negative.contains("not has(fay)"), negative)
    assert(negative.contains("Also checked through inlay: 0."), negative)
    assert(!negative.contains("\ninlay:\n"), "negative inference must not claim a positive Horn certificate")
    assert(errors.isEmpty, errors.mkString("\n"))
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-logic-proof-webgl2.png")).setFullPage(true))
    println("12-layer WebGL2 reader, authored Datalog, checked CPU proofs, compiled inlay, " +
      "absence query offline and phone-width layout passed")
  catch
    case error: Throwable =>
      println(page.locator("body").innerText().takeRight(16000))
      throw error
  finally
    context.close()
    browser.close()
    pw.close()
