//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import com.microsoft.playwright.options.{SelectOption, WaitForSelectorState}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Real model regression for the reported river request; no natural-language parser or mocked inference. */
@main def river(url: String): Unit =
  val expectPlan = sys.env.get("EXPECT_PLAN").contains("1")
  val pw = Playwright.create()
  val chrome = sys.props("user.home") + "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(chrome)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  context.route("https://esm.run/**", r => r.fulfill(Route.FulfillOptions().setContentType("text/javascript")
    .setHeaders(Map("access-control-allow-origin" -> "*").asJava).setBody("export {};")))
  val page = context.newPage()
  page.setDefaultTimeout(180000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  var navigations = 0
  page.onPageError(error => { errors += error; () })
  try
    page.navigate(url + (if url.contains("?") then "&" else "?") + "models")
    page.waitForSelector("select")
    page.locator("select").selectOption(SelectOption().setLabel(
      "Qwen3 0.6B, 28 layers + program adapter (~305 MiB, experimental)"))
    page.click("button:text-is('Load the model')")
    page.locator("text=\"Unload model\"").waitFor()
    page.onFrameNavigated(frame => { if frame == page.mainFrame() then navigations += 1; () })
    val question = Files.readString(Path.of("core/src/test/resources/puzzles/river-ru.txt")).trim
    page.locator("textarea").fill(question)
    page.click("button:text-is('Ask')")
    page.locator("button:text-is('Ask')").waitFor(
      com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN))
    var ticks = 0
    while page.locator("button:text-is('Ask')").count() == 0 && ticks < 180 do
      page.waitForTimeout(1000)
      ticks += 1
      if ticks % 10 == 0 then println(page.evaluate("() => window.okayModelProgress"))
    assert(page.locator("button:text-is('Ask')").count() == 1)
    val body = page.locator("body").innerText()
    println(body)
    val peak = page.evaluate("() => window.okayModelProgress.peakGpuBytes").asInstanceOf[Number].doubleValue()
    assert(peak > 0 && peak <= 512.0 * 1048576)
    assert(body.contains("Крестьянин купил на базаре козу"))
    if expectPlan then
      assert(body.contains("Shortest plan: 7 steps") && body.contains("compiled inlay Horn"))
      assert(page.evaluate("""() => {
        const a = window.okayUnderstanding, p = JSON.parse(a.program).plan;
        return a.api === 'WebGL2' && a.layers === 28 && !a.parserFallback &&
          a.author === 'native-language-model' && p.safe.length === 2 &&
          p.moves.length === 1 && p.moves[0].choose.max === 1;
      }""").asInstanceOf[Boolean])
      println(page.evaluate("() => window.okayUnderstanding"))
    else assert(body.contains("not loaded yet") && !body.contains("Answer:"))
    assert(navigations == 0 && errors.isEmpty, errors.mkString("\n"))
    context.setOffline(true)
    if expectPlan then
      page.click("text=\"Clear chat\"")
      page.locator("textarea").fill(question.replace("могла поместится только одна из его покупок",
        "могли поместиться не более двух его покупок"))
      page.click("button:text-is('Ask')")
      page.waitForFunction("() => document.body.textContent.includes('Shortest plan: 3 steps')")
      assert(page.evaluate("() => JSON.parse(window.okayUnderstanding.program).plan.moves[0].choose.max === 2")
        .asInstanceOf[Boolean])
      page.click("text=\"Clear chat\"")
    page.locator("textarea").fill("У Лены 8 яблок. Она отдала 3 яблока. Сколько яблок осталось?")
    page.click("button:text-is('Ask')")
    page.waitForFunction("() => document.body.textContent.toLowerCase().includes('answer: 5')")
    assert(page.evaluate("() => window.okayUnderstanding.api === 'WebGL2' && " +
      "window.okayUnderstanding.layers === 28 && !window.okayUnderstanding.parserFallback").asInstanceOf[Boolean])
    assert(navigations == 0 && errors.isEmpty)
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-river-plan.png")).setFullPage(true))
    println((if expectPlan then "Model-authored plan and capacity mutation passed; " else "River stopped safely; ") +
      s"peak GPU ${peak / 1048576} MiB; next question passed offline")
    page.evaluate("q => localStorage.setItem('okay-desk.pending-author-question', q)", question)
    context.setOffline(false)
    page.reload()
    page.waitForSelector("textarea")
    assert(page.locator("body").innerText().contains("A question was recovered after a page restart"))
    assert(page.locator("body").innerText().contains("Крестьянин купил на базаре козу"))
    assert(page.locator("select option:checked").innerText().contains("28 layers"))
    println("Interrupted-question recovery passed without automatically rerunning generation")
  finally
    context.close()
    browser.close()
    pw.close()
