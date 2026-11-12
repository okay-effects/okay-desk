//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0

import com.microsoft.playwright.{BrowserType, Page, Playwright}
import com.microsoft.playwright.options.ScreenshotType
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** A QUESTION'S STEPS, WATCHED (specs/data.md §22): the page driven as a person would — the readers loaded, the
 * sample's files opened — then each question asked and the steps block (`.okay-mark-thinking`) read every 0.3 s
 * until the answer: every new state printed, a screenshot in the middle of it.
 * `scala-cli run scripts/thinking.scala -- <page url> <sample dir> <out.png>` */
@main def thinking(url: String, sample: String, out: String): Unit =
  val exe = sys.env.getOrElse("CHROME", sys.props("user.home") +
    "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val browser = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)).setHeadless(false)
    .setArgs(List("--enable-unsafe-webgpu", "--enable-features=WebGPU").asJava))
  val ctx = browser.newContext(com.microsoft.playwright.Browser.NewContextOptions().setViewportSize(1100, 900)
    .setDeviceScaleFactor(2))
  val p = ctx.newPage()
  // `GL=1`: WebGPU hidden, WebGL2 runs (slower: the steps worth watching)
  if sys.env.contains("GL") then p.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });")
  p.onPageError(e => println("page error: " + e.take(600)))
  p.setDefaultTimeout(600000)
  // every state of the steps block as the page wrote it, timed — even while a forward keeps the driver waiting
  p.addInitScript("""window.__steps = []; new MutationObserver(() => {
    const b = document.querySelector('.okay-mark-thinking');
    const t = b ? b.innerText.replace(/\s*\n\s*/g, ' | ').replace(/ — [0-9.]+ s/, '') : '';
    const last = window.__steps.length ? window.__steps[window.__steps.length - 1][1] : null;
    if (t && t !== last) window.__steps.push([Math.round(performance.now()), t]);
  }).observe(document, { subtree: true, childList: true, characterData: true });""")
  p.navigate(url + (if url.contains("?") then "&" else "?") + "models")
  p.waitForSelector("button:text-is('Load the model')")
  p.click("button:text-is('Load the model')")
  p.waitForFunction("() => /readers on|cannot load/.test(document.body.textContent)")
  println("model: " + "readers on [A-Za-z0-9]+".r.findFirstIn(p.locator("body").textContent()).getOrElse("not loaded"))
  p.click("text=\"Data: open files\"")
  p.waitForSelector("input[type=file]")
  p.locator("input[type=file]:not([webkitdirectory])").setInputFiles(
    Seq("orders.csv", "customers.csv", "edges.csv").map(f => Path.of(sample, f)).toArray)
  p.waitForFunction("() => /==> /.test(document.querySelector('textarea').value)")
  p.click("button:text-is('Open the files')")
  p.waitForFunction("() => document.querySelectorAll('.okay-mark-tree-node, .okay-mark-tree-node-open').length >= 3")
  p.click("text=\"← Back to the chat\"")
  p.waitForSelector("textarea[id^=f-ask]")
  var shot = false
  for q <- Seq("How many orders are unpaid?", "Total amount per country.") do
    val before = p.locator(".okay-mark-result").count()
    p.fill("textarea[id^=f-ask]", q); p.click("button:text-is('Ask')")
    println(s"Q: $q"); val t0 = System.nanoTime()
    var last = ""
    var states = 0
    while p.locator(".okay-mark-result").count() <= before do
      val now = if p.locator(".okay-mark-thinking").count() > 0 then
        p.locator(".okay-mark-thinking").first().innerText().replaceAll("\\s*\\n\\s*", " | ") else ""
      // the seconds change every moment: a new state is new steps
      val steps = now.replaceAll(" — [0-9.]+ s", "")
      if now.nonEmpty && steps != last then
        println(s"  $now"); last = steps; states += 1
        if !shot && states >= 2 then
          p.screenshot(Page.ScreenshotOptions().setPath(Path.of(out)).setType(ScreenshotType.PNG)); shot = true
      p.waitForTimeout(300)
    println(f"  (${(System.nanoTime() - t0) / 1e9}%.1f s) the steps as the page wrote them:")
    val written = p.evaluate("() => { const s = window.__steps; window.__steps = []; return s.map(x => x[0] + ' ms: ' + x[1].split(' | ').pop()).join('\\n') }").toString
    written.split("\n").foreach(l => println(s"    $l"))
    println(s"  → ${p.locator(".okay-mark-result").last().textContent().take(120).replace("\n", " ⏎ ")}")
  browser.close(); pw.close()
  println(s"written $out")
