//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The page as a phone opens it by its logic link (`?logic`): WebGL2 (WebGPU hidden), a narrow screen, touch, the
 * browser's language given. The tasks the model reads are there at once; ONE press of "Solve" loads the model and
 * answers; then a few more tasks, each timed. Pass the page's address, a directory for screenshots, the language
 * (ru or en) and how many tasks to solve. */
@main def logic(url: String, shots: String, language: String, count: Int): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe))
    .setArgs(List("--use-angle=metal").asJava))
  try
    val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844).setHasTouch(true)
      .setLocale(if language == "ru" then "ru-RU" else "en-US"))
    context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
      .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
      .setBody("export {};")))
    val page = context.newPage()
    page.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
    page.setDefaultTimeout(600000)
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    page.onPageError(e => { errors += e; () })
    def shot(name: String): Unit =
      page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"$shots/logic-$language-$name.png"))); ()
    val (solve, back) = (if language == "ru" then "Решить" else "Solve", "Examples")
    page.navigate(url + "?logic")
    page.waitForSelector(s"text=\"$solve\"")
    page.waitForTimeout(500)
    println(s"opened at scroll ${page.evaluate("() => window.scrollY")}")
    shot("tasks")
    var (right, wrong, asked) = (0, 0, 0)
    for at <- 0 until count do
      if at > 0 then
        page.click(s"text=\"$back\"")
        page.waitForSelector(s"text=\"$solve\"")
        page.waitForTimeout(300)
        if at == 1 then println(s"back at scroll ${page.evaluate("() => window.scrollY")}")
      val before = page.locator(".okay-mark-check").count()
      val started = System.nanoTime()
      page.locator(s"text=\"$solve\"").nth(at).click()
      page.waitForFunction("n => document.querySelectorAll('.okay-mark-check').length > n", before)
      val seconds = (System.nanoTime() - started) / 1e9
      val check = page.locator(".okay-mark-check").last.textContent().trim
      if check.contains("✓") then right += 1
      else if check.contains("no answer") || check.contains("ответа нет") then asked += 1 else wrong += 1
      println(f"task ${at + 1}: $seconds%.1f s${if at == 0 then " (with the model's loading)" else ""} — " +
        check.take(70))
      if at == 0 then
        page.locator(".okay-mark-check").last.scrollIntoViewIfNeeded()
        shot("solved")
    println(s"right $right, asked $asked, WRONG $wrong; errors: ${errors.mkString("; ")}")
    context.close()
  finally { browser.close(); pw.close() }
