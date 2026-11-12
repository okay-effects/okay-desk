//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The real page: examples navigation, full transcript copy, failure reporting and scoped clearing. */
@main def chat(url: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  try
    for width <- Vector(390, 1100) do
      val context = browser.newContext(Browser.NewContextOptions().setViewportSize(width, 844))
      context.grantPermissions(List("clipboard-read", "clipboard-write").asJava)
      context.route("https://esm.run/**", route => route.fulfill(Route.FulfillOptions()
        .setContentType("text/javascript").setHeaders(Map("access-control-allow-origin" -> "*").asJava)
        .setBody("export {};")))
      val page = context.newPage()
      page.setDefaultTimeout(30000)
      val errors = scala.collection.mutable.ArrayBuffer.empty[String]
      page.onPageError(e => { errors += e; () })
      try
        page.navigate(url + (if url.contains("?") then "&" else "?") + "models")
        assert(page.locator("text=\"Try an inference\"").count() == 0)
        assert(page.locator("text=\"Copy chat\"").count() == 0)
        page.click("text=\"Examples\"")
        assert(page.locator("text=\"Try an inference\"").count() == 1)
        assert(page.locator("text=\"Examples of open data\"").count() == 1)
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"/tmp/okay-desk-examples-$width.png")))
        page.click("text=\"Try an inference\"")
        page.waitForFunction("() => document.body.textContent.includes('Proved: moby is an animal.')")
        assert(page.locator("textarea").count() == 1 && page.locator("text=\"Try an inference\"").count() == 0)
        assert(page.evaluate("""() => {
          const history = [...document.querySelectorAll(
            '.okay-mark-user,.okay-mark-model,.okay-mark-system,.okay-mark-result,.okay-mark-source')];
          const actions = document.querySelector('.okay-mark-chat-actions').getBoundingClientRect();
          const input = document.querySelector('textarea').closest('form').getBoundingClientRect();
          return actions.top >= Math.max(...history.map(e => e.getBoundingClientRect().bottom)) - 1 &&
            actions.bottom <= input.top + 1;
        }""").asInstanceOf[Boolean], "chat actions must sit between the history and question form")
        page.click("text=\"Examples\"")
        page.click("text=\"← Back to the chat\"")
        assert(page.locator("body").innerText().contains("Proved: moby is an animal."))
        page.locator("textarea").fill("An unsent question")
        page.click("text=\"Copy chat\"")
        page.waitForFunction("() => document.body.textContent.includes('Chat copied.')")
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"/tmp/okay-desk-chat-actions-$width.png")))
        val copied = page.evaluate("() => navigator.clipboard.readText()").asInstanceOf[String]
        assert(copied.contains("Proved: moby is an animal.") && copied.contains("Every whale is a mammal."))
        assert(!copied.contains("An unsent question") && !copied.contains("Load the model"))
        assert(page.locator("textarea").inputValue() == "An unsent question", "copy discarded the question draft")
        page.evaluate("""() => Object.defineProperty(navigator, 'clipboard', { configurable: true,
          value: { writeText: () => Promise.reject(new Error('denied')) } })""")
        page.click("text=\"Copy chat\"")
        page.waitForFunction("() => document.body.textContent.includes('Could not copy the chat.')")
        assert(!page.locator("body").innerText().contains("Chat copied."))
        page.evaluate("() => delete navigator.clipboard")
        page.click("text=\"Clear chat\"")
        assert(page.locator(".okay-mark-user, .okay-mark-model, .okay-mark-source").count() == 0)
        assert(page.locator("text=\"Copy chat\"").count() == 0 && page.locator("text=\"Clear chat\"").count() == 0)
        assert(page.locator("textarea").inputValue().isEmpty)
        page.click("text=\"Data: open files\"")
        assert(page.locator("text=\"Examples of open data\"").count() == 0)
        page.click("text=\"Examples\"")
        page.locator(".okay-mark-example").first().locator("text=\"Open\"").click()
        page.waitForFunction("() => document.body.textContent.includes('the data: customers, edges, orders')")
        page.click("text=\"Clear chat\"")
        assert(page.locator("body").innerText().contains("the data: customers, edges, orders"))
        assert(page.locator(".okay-mark-system").count() == 0)
        assert(page.evaluate("() => document.documentElement.scrollWidth <= innerWidth").asInstanceOf[Boolean])
        assert(errors.isEmpty, errors.mkString("\n"))
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(s"/tmp/okay-desk-chat-$width.png")))
        println(s"$width px: examples, clipboard, rejected write, preserved draft/data and clear passed")
      catch
        case e: Throwable =>
          println(page.locator("body").innerText().takeRight(7000))
          throw e
      finally context.close()
  finally
    browser.close()
    pw.close()
