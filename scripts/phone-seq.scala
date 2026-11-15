// The page as a phone (390×844, touch, no WebGPU): types each file's text into the chat, prints the answer, the GPU
// memory (window.okayModelProgress) and WebGL textures made/deleted. scala-cli run scripts/phone-seq.scala -- <url> <files…>
//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0
import com.microsoft.playwright.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
@main def phoneSeq(url: String, files: String*): Unit =
  val exe = sys.props("user.home") + "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val b = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val c = b.newContext(Browser.NewContextOptions().setViewportSize(390, 844).setHasTouch(true))
  c.route("https://esm.run/**", r => r.fulfill(Route.FulfillOptions().setContentType("text/javascript").setBody("export {};")))
  val p = c.newPage()
  p.addInitScript("(() => { const P = WebGL2RenderingContext.prototype; const c = P.createTexture, d = P.deleteTexture; window.texMade = 0; window.texGone = 0; P.createTexture = function() { window.texMade++; return c.apply(this, arguments) }; P.deleteTexture = function() { window.texGone++; return d.apply(this, arguments) } })()");
  p.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined }); Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5 });")
  p.setDefaultTimeout(240000); p.onConsoleMessage(m => println("console: " + m.text().take(200)))
  p.navigate(url)
  def mem: String = p.evaluate("() => { const m = window.okayModelProgress; return m ? Math.round(m.gpuBytes/1048576) + ' MB now, ' + Math.round(m.peakGpuBytes/1048576) + ' MB peak' : 'none' }").toString
  for f <- files do
    val before = p.locator(".okay-mark-model").count()
    p.locator("textarea:visible").last.fill(Files.readString(Path.of(f)))
    p.click("text=\"Ask\"")
    p.waitForFunction("n => document.querySelectorAll('.okay-mark-model').length > n", before)
    p.waitForTimeout(800)
    println(s"$f: ${p.locator(".okay-mark-model").last.innerText().take(40).replace("\n", " ")} | GPU $mem | textures made ${p.evaluate("() => window.texMade")} deleted ${p.evaluate("() => window.texGone")}")
  b.close(); pw.close()
