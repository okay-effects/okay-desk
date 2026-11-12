//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The collection of logic tasks in the real page: pages turned, a class chosen; a task answered by hand (a wrong try,
 * the right one) and solved by the desk in the chat with its check — at two widths; the screenshots go to the
 * directory given. Serve the built page and pass its address. */
@main def tasks(url: String, shots: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  try
    for width <- Vector(390, 1100) do
      val context = browser.newContext(Browser.NewContextOptions().setViewportSize(width, 844))
      context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
        .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
        .setBody("export {};")))
      val page = context.newPage()
      page.setDefaultTimeout(30000)
      val errors = scala.collection.mutable.ArrayBuffer.empty[String]
      page.onPageError(e => { errors += e; () })
      def shot(name: String, full: Boolean = false): Unit =
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"$shots/$name-$width.png")).setFullPage(full)); ()
      def has(t: String): Boolean =
        page.evaluate("t => document.body.textContent.includes(t)", t) == true
      page.navigate(url + "?models")
      assert(!has("Logic tasks"), "the collection is on the chat screen")
      page.click("text=\"Examples\"")
      assert(has("Logic tasks") && has("Page 1 of 24") && has("1. The zebra puzzle"))
      assert(page.locator("text=\"Answer\"").count() == 10 && page.locator("text=\"Solve\"").count() == 10)
      assert(!has("✓") && !has("✗"), "an answer is shown")
      println(s"$width: page height ${page.evaluate("() => document.documentElement.scrollHeight")}")
      val overflow = page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth")
      println(s"$width: horizontal overflow $overflow")
      shot("examples", full = true)
      page.locator("text=\"Next →\"").last.scrollIntoViewIfNeeded()
      val before = page.evaluate("() => window.scrollY")
      page.locator("text=\"Next →\"").last.click()
      page.waitForFunction("() => document.body.textContent.includes('Page 2 of 24')")
      println(s"$width: scroll before $before, after ${page.evaluate("() => window.scrollY")}")
      shot("page2")
      page.click("text=\"Plans (29)\"")
      page.waitForFunction("() => document.body.textContent.includes('Page 1 of 3')")
      assert(has("1. The wolf, the goat and the cabbage"))
      page.click("text=\"Русский\"")
      page.waitForFunction("() => document.body.textContent.includes('Страница 1 из 3')")
      page.locator("text=\"Планы (29)\"").scrollIntoViewIfNeeded()
      shot("plans-ru")
      assert(page.locator("text=\"Ответить\"").count() == 10 && page.locator("text=\"Решить\"").count() == 10)
      page.click("text=\"English\"")
      page.click("text=\"All (233)\"")
      page.waitForFunction("() => document.body.textContent.includes('Page 1 of 24')")
      page.locator("text=\"Answer\"").first.click()
      page.waitForFunction("() => document.body.textContent.includes('Your answer')")
      assert(!has("Logic tasks") && has("1. The zebra puzzle") && has("zebra?"))
      shot("answering", full = true)
      def answer(typed: String, verdict: String): Unit =
        page.locator("input:visible").last.fill(typed)
        page.click("text=\"Check\"")
        page.waitForFunction("t => document.body.textContent.includes(t)", verdict)
      answer("the Norwegian", "Not right ✗ — «the Norwegian»")
      assert(!has("the answer:"))
      shot("wrong", full = true)
      answer("Japanese", "Right ✓ — the answer: the Japanese")
      shot("right", full = true)
      page.click("text=\"← Back to the tasks\"")
      page.waitForFunction("() => document.body.textContent.includes('Page 1 of 24')")
      def solve(kind: String, name: String): Unit =
        if page.locator("text=\"Examples\"").count() > 0 then page.click("text=\"Examples\"")
        page.click(s"text=\"$kind\"")
        page.waitForFunction("() => document.body.textContent.includes('tasks 1–10 of')")
        page.locator("text=\"Solve\"").first.click()
        page.waitForFunction("() => document.querySelectorAll('.okay-mark-user').length > 0")
        assert(!has("Logic tasks"))
        page.waitForFunction("() => document.querySelectorAll('.okay-mark-check').length > 0 || " +
          "document.body.textContent.includes('Stay offline')")
        if page.locator("text=\"Stay offline\"").count() > 0 then page.click("text=\"Stay offline\"")
        page.waitForFunction("() => document.querySelectorAll('.okay-mark-check').length > 0")
        println(s"$width: $name: ${page.locator(".okay-mark-check").last.textContent()}")
        page.locator(".okay-mark-check").last.scrollIntoViewIfNeeded()
        shot(name)
        page.click("text=\"Clear chat\"")
      solve("Deduction (15)", "solved-deduction")
      solve("Data (22)", "solved-data")
      println(s"$width: errors ${errors.mkString("; ")}")
      context.close()
  finally { browser.close(); pw.close() }
