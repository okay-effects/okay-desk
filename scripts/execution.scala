//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** CPU execution in real browser Workers: examples, overlapping solvers, a ticking UI, cancellation and recovery. */
@main def execution(url: String): Unit =
  val exe = sys.env.getOrElse(
    "CHROME",
    sys.props("user.home") +
      "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
      "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  )
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.route(
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
  val page = context.newPage()
  page.setDefaultTimeout(120000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  var workers = 0
  page.onPageError(e => { errors += e; () })
  page.onWorker(_ => { workers += 1; () })
  try
    page.navigate(url)
    page.click("text=\"Examples\"")
    page.click("text=\"Try an inference\"")
    page.waitForFunction("() => document.body.textContent.includes('Proved: moby is an animal.')")
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-inference-worker.png")).setFullPage(true))
    page.navigate(url)
    page.click("text=\"Examples\"")
    page.click("text=\"Try a grid problem\"")
    page.waitForFunction("() => document.body.textContent.includes('Alice — dog.')")
    page.navigate(url)
    page.click("text=\"Examples\"")
    page.click("text=\"Two calculations\"")
    page.waitForFunction(
      "() => document.body.textContent.includes('2 + 2 = 4') && " +
        "document.body.textContent.includes('3 * 7 = 21')"
    )
    page.navigate(url + "?execution-test")
    page.waitForFunction("() => /execution-test: (done|failed)/.test(document.body.textContent)")
    val result = page.locator("#execution-test").innerText()
    println(result)
    assert(result.contains("execution-test: done"), result)
    assert(workers >= 2, "no actual parallel browser workers")
    assert(errors.isEmpty, errors.mkString("\n"))
    page.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
    page.navigate(url + "?execution-test-model")
    page.waitForFunction("() => /execution-test: (done|failed)/.test(document.body.textContent)")
    val together = page.locator("#execution-test").innerText()
    println(together)
    assert(
      together.contains("native WebGL2 forward overlapped CPU work") &&
        together.contains("execution-test: done"),
      together
    )
    assert(errors.isEmpty, errors.mkString("\n"))
    println(s"examples and worker lifecycle passed; $workers Worker instances observed")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(5000))
      println(errors.mkString("\n"))
      throw e
  finally
    browser.close()
    pw.close()
