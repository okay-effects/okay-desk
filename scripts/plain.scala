//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The page by its plain address, as a phone (WebGL2, a narrow screen, touch): two buttons and the question, the
 * model named in the chat and not loaded; a calculation asked (no model needed); then a logic task solved from the
 * examples — the model loads by itself and the chat says on what. Pass the address and a directory for shots. */
@main def plain(url: String, shots: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe))
    .setArgs(List("--use-angle=metal").asJava))
  try
    val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844).setHasTouch(true)
      .setLocale("ru-RU"))
    context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
      .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
      .setBody("export {};")))
    val page = context.newPage()
    page.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
    page.setDefaultTimeout(600000)
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    page.onPageError(e => { errors += e; () })
    def shot(name: String): Unit =
      page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"$shots/plain-$name.png"))); ()
    def has(t: String): Boolean = page.evaluate("t => document.body.innerText.includes(t)", t) == true
    def buttons: Vector[String] = page.locator("[class*=okay-mark-button]")
      .allTextContents().asScala.toVector.map(_.trim).distinct
    page.navigate(url)
    page.waitForSelector("text=\"Ask\"")
    page.waitForTimeout(400)
    println(s"buttons: ${buttons.mkString(" | ")}")
    assert(buttons == Vector("Data: open files", "Examples") && has("Ask"), buttons.toString)
    assert(!has("Model:") && !has("not loaded yet"), "the model is said before it is wanted")
    assert(!has("Load the model") && !has("THE MODEL"))
    shot("opened")
    page.locator("textarea:visible").last.fill("2 + 2")
    page.click("text=\"Ask\"")
    page.waitForFunction("() => document.querySelectorAll('.okay-mark-result, .okay-mark-model').length > 0")
    println("2 + 2: " + page.locator(".okay-mark-result, .okay-mark-model").last.textContent().trim.take(60))
    page.click("text=\"Examples\"")
    page.locator("span.okay-mark-button, span.okay-mark-button-plain",
      Page.LocatorOptions().setHasText("The model reads (")).first.click()
    page.waitForFunction("() => document.body.textContent.includes('tasks 1–10 of 91')")
    val started = System.nanoTime()
    page.locator("text=\"Solve\"").nth(1).click()
    page.waitForFunction("() => document.querySelectorAll('.okay-mark-check').length > 0")
    println(f"a task: ${(System.nanoTime() - started) / 1e9}%.1f s — " +
      page.locator(".okay-mark-check").last.textContent().trim.take(80))
    println("the model's line: " + page.locator(".okay-mark-system").first.textContent().trim.take(160))
    assert(has("the readers on WebGL2"), "the chat does not say what the model runs on")
    page.locator(".okay-mark-system").first.scrollIntoViewIfNeeded()
    shot("solved-top")
    page.locator(".okay-mark-check").last.scrollIntoViewIfNeeded()
    shot("solved")
    println(s"errors: ${errors.mkString("; ")}")
    context.close()
  finally { browser.close(); pw.close() }
