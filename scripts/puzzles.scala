//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** One chat on desktop and phone: arithmetic, finite constraints and live knowledge, without task modes. WORLD_LIVE=1
  * enables the real source services. `scala-cli run scripts/puzzles.scala -- <page url>`
  */
@main def puzzles(url: String): Unit =
  val executable = sys.env.getOrElse(
    "CHROME",
    sys.props("user.home") +
      "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
      "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  )
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(executable)))
  val live = sys.env.contains("WORLD_LIVE")
  val task = """Countries: France, Germany
    |Pets: dog, cat
    |France is in house 1.
    |The one whose countries/capital is Paris goes with dog.
    |Which country owns dog?
    |""".stripMargin
  try
    for (width, height) <- Vector((1100, 900), (390, 844)) do
      val ctx = browser.newContext(Browser.NewContextOptions().setViewportSize(width, height))
      val page = ctx.newPage()
      // No generating model is needed for the exact tools; omit the optional CDN module in this smoke test.
      page.route(
        "https://esm.run/**",
        route =>
          route.fulfill(
            com.microsoft.playwright.Route.FulfillOptions().setContentType("text/javascript").setBody("export {};")
          )
      )
      val errors = scala.collection.mutable.ArrayBuffer.empty[String]
      var queries = 0
      page.onPageError(e => { errors += e; () })
      page.onRequest(r => if r.url().startsWith("https://query.wikidata.org/") then queries += 1)
      page.onResponse(r =>
        if r.url().startsWith("https://query.wikidata.org/") then println(s"Wikidata HTTP ${r.status()}")
      )
      page.setDefaultTimeout(90000)
      page.navigate(url)
      def ask(text: String): Unit =
        page.locator("textarea").fill(text)
        page.click("button:text-is('Ask')")
      try
        ask("0.1 + 0.2")
        page.waitForFunction("() => document.body.textContent.includes('0.1 + 0.2 = 0.3')")
        assert(page.locator("textarea").count() == 1, "more than one question input")
        assert(page.locator("text=\"Puzzles\"").count() == 0, "a separate question mode")
        val text =
          if live then task
          else task.replace("The one whose countries/capital is Paris goes with dog.", "France goes with dog.")
        ask(text)
        if live then page.click("text=\"Use online sources\"")
        page.waitForFunction("() => document.body.textContent.includes('France — dog.')")
        assert(queries == (if live then 2 else 0), s"$queries research requests")
        ask(text)
        page.waitForFunction(
          "() => Array.from(document.querySelectorAll('p')).filter(" +
            "p => p.textContent === 'France — dog.').length >= 2"
        )
        assert(queries == (if live then 2 else 0), "cached facts were fetched again")
        if live && width < 700 then
          ask("What is rain?")
          page.waitForFunction("() => document.body.textContent.includes('These excerpts are evidence')")
          assert(page.locator("a[href*='wikipedia.org/w/index.php']").count() > 0, "no general evidence")
        assert(errors.isEmpty, errors.mkString("\n"))
        val output = s"/tmp/okay-desk-chat-$width.png"
        page.screenshot(Page.ScreenshotOptions().setPath(Path.of(output)).setFullPage(true))
        println(s"$width x $height: one chat, exact arithmetic, France; $queries world requests; $output")
      catch
        case e: Throwable =>
          println(page.locator("body").textContent().takeRight(3500))
          println(errors.mkString("\n"))
          throw e
      finally ctx.close()
  finally
    browser.close()
    pw.close()
