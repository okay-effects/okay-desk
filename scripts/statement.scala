//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The statement reader in the real page (specs/formalization.md §12.1): the model loaded, tasks of the classes the
 * statement language states solved by "Solve" in both languages — the answer or the doubt, the reading under it,
 * the check — with the time each took. Serve the built page and pass its address, a directory for the screenshots
 * and how many tasks of a class to solve. `GL=1`: WebGL2 (WebGPU
 * hidden); `PHONE=1`: as a phone — WebGL2, a narrow screen, touch. */
@main def statement(url: String, shots: String, count: Int): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val flags = if sys.env.contains("GL") then List("--use-angle=metal", "--disable-features=WebGPU")
    else List("--enable-unsafe-webgpu", "--use-angle=metal")
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe))
    .setArgs(flags.asJava))
  try
    val context = browser.newContext(Browser.NewContextOptions().setViewportSize(if sys.env.contains("PHONE") then 390 else 1100, 900)
      .setHasTouch(sys.env.contains("PHONE")))
    context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
      .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
      .setBody("export {};")))
    val page = context.newPage()
    if sys.env.contains("GL") || sys.env.contains("PHONE") then
      page.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
    page.setDefaultTimeout(300000)
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    page.onPageError(e => { errors += e; () })
    page.navigate(url + "?models")
    page.click("text=\"Load the model\"")
    page.waitForFunction("() => document.body.textContent.includes('the readers on')")
    println(page.locator("text=/the readers on/").first.textContent().trim.take(160))
    var (right, wrong, asked) = (0, 0, 0)
    for
      russian <- Vector(false, true)
      (en, ru) <- Vector("Einstein's puzzle" -> "Задача Эйнштейна", "Who is where" -> "Кто где",
        "Comparisons" -> "Сравнения", "Who is who" -> "Кто есть кто")
      kind = if russian then ru else en
      at <- 0 until count
    do
      if page.locator("text=\"Clear chat\"").count() > 0 then page.click("text=\"Clear chat\"")
      if page.locator("text=\"Examples\"").count() > 0 then page.click("text=\"Examples\"")
      page.waitForFunction("() => document.body.textContent.includes('tasks 1–10 of') || " +
        "document.body.textContent.includes('задачи 1–10 из')")
      val other = if russian then "Русский" else "English"
      if page.locator(s"text=\"$other\"").count() > 0 then page.click(s"text=\"$other\"")
      val all = page.locator("span.okay-mark-button, span.okay-mark-button-plain",
        Page.LocatorOptions().setHasText(kind + " ("))
      if all.count() > 0 then
        all.first.click()
        page.waitForTimeout(200)
        val solve = page.locator(if russian then "text=\"Решить\"" else "text=\"Solve\"")
        if at < solve.count() then
          val before = page.locator(".okay-mark-check").count()
          val started = System.nanoTime()
          solve.nth(at).click()
          page.waitForFunction("n => document.querySelectorAll('.okay-mark-check').length > n", before)
          val seconds = (System.nanoTime() - started) / 1e9
          val check = page.locator(".okay-mark-check").last.textContent().trim
          val said = page.locator(".okay-mark-model").last.textContent().trim.replace("\n", " ⏎ ")
          if check.contains("✓") then right += 1
          else if check.contains("no answer") || check.contains("ответа нет") then asked += 1 else wrong += 1
          val language = if russian then "ru" else "en"
          println(f"$language $kind $at: $seconds%.1f s — ${check.take(60)} — ${said.take(150)}")
          if at == 0 then
            page.locator(".okay-mark-check").last.scrollIntoViewIfNeeded()
            page.screenshot(Page.ScreenshotOptions().setPath(
              Path.of(s"$shots/statement-$language-${en.take(5).trim}.png")))
    println(s"right $right, asked $asked, WRONG $wrong; errors: ${errors.mkString("; ")}")
    context.close()
  finally { browser.close(); pw.close() }
