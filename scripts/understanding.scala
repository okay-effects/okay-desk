//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright, Route}
import com.microsoft.playwright.options.SelectOption
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Native model authors every program on WebGL2. No WebLLM, task dictionary or baseline text reader. */
@main def understanding(url: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val context = browser.newContext(Browser.NewContextOptions().setViewportSize(390, 844))
  context.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  context.addInitScript("""window.okayGlContexts = [];
    const originalContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(kind) {
      const result = originalContext.apply(this, arguments);
      if (kind === 'webgl2' && result) window.okayGlContexts.push(result);
      return result;
    };""")
  context.route("https://esm.run/**", r => r.fulfill(Route.FulfillOptions().setContentType("text/javascript")
    .setHeaders(Map("access-control-allow-origin" -> "*").asJava).setBody("export {};")))
  val page = context.newPage()
  page.setDefaultTimeout(300000)
  val errors = scala.collection.mutable.ArrayBuffer.empty[String]
  var workers = 0
  page.onPageError(e => { errors += e; () })
  page.onWorker(_ => { workers += 1; () })
  def ask(question: String, expected: String): Unit =
    page.locator("textarea").fill(question)
    val start = System.nanoTime()
    page.click("button:text-is('Ask')")
    page.waitForFunction("q => window.okayUnderstanding?.question === q", question)
    page.locator("button:text-is('Ask')").waitFor()
    assert(page.locator("body").innerText().toLowerCase.contains(expected.toLowerCase),
      s"The model finished without the expected consequence: $expected")
    val audit = page.evaluate("() => window.okayUnderstanding")
    val raw = page.evaluate("() => window.okayUnderstanding.program").asInstanceOf[String]
    assert(page.evaluate("""() => window.okayUnderstanding.author === 'native-language-model' &&
      window.okayUnderstanding.api === 'WebGL2' && window.okayUnderstanding.layers === 28 &&
      !window.okayUnderstanding.parserFallback""").asInstanceOf[Boolean])
    assert(page.evaluate("""() => window.okayUnderstanding.programs.some(p =>
      p.includes('"formal"') || p.includes('"eval"'))""").asInstanceOf[Boolean] && errors.isEmpty,
      raw + "\n" + errors.mkString("\n"))
    println(f"${(System.nanoTime() - start) / 1e9}%.1fs: $question\n$audit")
  try
    page.navigate(url + (if url.contains("?") then "&" else "?") + "models")
    assert(page.locator("text=\"Try the 28-layer task interpreter (experimental)\"").count() == 0)
    assert(page.locator("select option").count() == 5)
    page.locator("select").selectOption(SelectOption().setLabel(
      "Qwen3 0.6B, 28 layers + program adapter (~305 MiB, experimental)"))
    page.click("button:text-is('Load the model')")
    page.locator("text=\"Unload model\"").waitFor()
    ask("У Лены 8 яблок. Она отдала 3 яблока. Сколько яблок осталось?", "answer: 5")
    assert(page.locator("body").innerText().contains("model program authoring on WebGL2"))
    assert(workers > 0)
    context.setOffline(true)
    page.click("text=\"Clear chat\"")
    // The model may eliminate x and author 7 - 2; a goal's display label is not part of its meaning.
    ask("Целое x от 1 до 8. При увеличении x на 2 получаем 7. Чему равно x?", ": 5")
    page.click("text=\"Clear chat\"")
    // A training phrasing: integration check for compiled inlay; this is not held-out accuracy evidence.
    ask("p и q — логические значения. p истинно. Если p, то q. Истинно ли q?", ": true")
    assert(page.locator("body").innerText().contains("compiled inlay Horn"))
    page.click("text=\"Unload model\"")
    page.waitForFunction("() => window.okayGlContexts.some(gl => gl.isContextLost())",
      null, Page.WaitForFunctionOptions().setTimeout(10000))
    println("WebGL2 device released")
    page.waitForFunction("() => document.body.textContent.includes('not loaded yet')",
      null, Page.WaitForFunctionOptions().setTimeout(10000))
    assert(page.locator("body").innerText().contains("q: true"))
    assert(page.locator("text=\"Unload model\"").count() == 0)
    // Still offline: all model assets must come from the persistent cache after the old device was closed.
    page.click("button:text-is('Load the model')")
    println("Reloading from cache offline")
    page.locator("text=\"Unload model\"").waitFor()
    assert(page.locator("body").innerText().contains("model program authoring on WebGL2"))
    assert(page.locator("body").innerText().contains("q: true"))
    context.setOffline(false)
    page.locator("select").selectOption(SelectOption().setLabel(
      "Qwen3 0.6B student, 12 layers + readers (122 MB, no generation)"))
    page.click("button:text-is('Load the model')")
    page.waitForFunction("() => document.querySelector('.okay-mark-model-note').textContent" +
      ".includes('(12 layers) and the readers on WebGL2')")
    assert(page.locator("body").innerText().contains("q: true"))
    context.setOffline(true)
    page.locator("select").selectOption(SelectOption().setLabel(
      "Qwen3 0.6B, 28 layers + program adapter (~305 MiB, experimental)"))
    page.click("button:text-is('Load the model')")
    page.waitForFunction("() => document.querySelector('.okay-mark-model-note').textContent" +
      ".includes('(28 layers) and model program authoring on WebGL2')")
    assert(page.locator("body").innerText().contains("q: true"))
    page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-understanding-webgl2.png"))
      .setFullPage(true))
    println("native model author -> exact CPU Worker -> compiled inlay proof: WebGL2 and offline passed")
    println("model unload released its WebGL2 context; offline reload preserved the chat")
    println("shared model picker switched 28 -> 12 -> 28 layers without navigation or chat loss")
  catch
    case e: Throwable =>
      println(page.locator("body").innerText().takeRight(15000))
      page.screenshot(Page.ScreenshotOptions().setPath(Path.of("/tmp/okay-desk-understanding-failure.png"))
        .setFullPage(true))
      throw e
  finally
    context.close()
    browser.close()
    pw.close()
