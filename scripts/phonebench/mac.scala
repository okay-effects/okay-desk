// The phone bench on this Mac (specs/phone.md §5): Chromium as a phone (390×844, touch, no WebGPU — WebGL2 only),
// the same page and bench.js; its log lines are the reference the phone's answers are compared with.
// scala-cli run scripts/phonebench/mac.scala -- http://localhost:8750/
//> using scala 3.3.8
//> using dep com.microsoft.playwright:playwright:1.62.0
import com.microsoft.playwright.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
@main def macBench(url: String): Unit =
  val exe = sys.props("user.home") + "/Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/" +
    "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
  val pw = Playwright.create(Playwright.CreateOptions().setEnv(Map("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" -> "1").asJava))
  val b = pw.chromium().launch(BrowserType.LaunchOptions().setExecutablePath(Path.of(exe)))
  val c = b.newContext(Browser.NewContextOptions().setViewportSize(390, 844).setHasTouch(true))
  val p = c.newPage()
  p.addInitScript("Object.defineProperty(navigator, 'gpu', { get: () => undefined });" +
    "Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5 });")
  p.onConsoleMessage(m => if m.`type`() == "error" || m.text().contains("frame reader") || m.text().contains("ask") then println("console: " + m.text().take(300)))
  p.navigate(url)
  p.waitForFunction("() => (sessionStorage.getItem('phonebench') || '').includes('\"done\"')", null,
    Page.WaitForFunctionOptions().setTimeout(1800000).setPollingInterval(2000))
  b.close(); pw.close()
