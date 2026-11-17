// THE PHONE BENCH (specs/phone.md §5), put into the published page by server.scala. It answers four questions with
// no press: does the page run on this device, how many seconds a question, are the answers the Mac's, how much
// room the device has (GPU memory, speed) for a larger model. Every step is POSTed to /log at once, so a reload or
// a killed tab still leaves what was reached.
(() => {
  const QUESTIONS = [
    "How many orders are unpaid?",
    "What is the total amount of paid orders?",
    "Which city has the most orders?",
    "Show the three largest orders.",
    "What did ann spend in November?",
    "Сколько заказов из Львова?",
    "Какая средняя сумма оплаченного заказа?",
    "Покажи заказы клиентов из Польши.",
    "Кто из клиентов сделал больше всего заказов?",
    "Почему bob не оплатил заказ?",
  ];
  // on http over the local network the page is no secure context and has no Cache API; the page's path without one
  // (OnDisk/Engine `caches` undefined) never finishes opening a table (found 2026-11-17 by this bench) — so here a
  // cache in memory: the opened tables kept, the model's files not (they come from the Mac over Wi-Fi each time)
  if (!window.isSecureContext) {
    const stores = new Map();
    const store = name => {
      if (!stores.has(name)) {
        const m = new Map(), keep = name !== "okay-desk-model";
        stores.set(name, {
          // the caller's clone of a model file is cancelled: a clone never read holds a second copy of the file
          put: async (k, r) => { if (keep) m.set(String(k), await r.text()); else if (r.body) r.body.cancel().catch(() => {}); },
          match: async k => m.has(String(k)) ? new Response(m.get(String(k))) : undefined,
          delete: async k => m.delete(String(k)),
        });
      }
      return stores.get(name);
    };
    try { Object.defineProperty(window, "caches", { value: { open: async n => store(n) }, configurable: true }); }
    catch (e) {}
  }
  // ?trace: every model read to the log, with a weighing's best three (the page prints them; the console hook sends)
  if (location.search.includes("trace")) window.okayTraceReads = true;
  const t0 = performance.now();
  const ms = () => Math.round(performance.now() - t0);
  const saved = JSON.parse(sessionStorage.getItem("phonebench") || "null");
  const state = saved || { run: Date.now().toString(36), step: "start", loads: 0 };
  state.loads += 1;
  const keep = () => sessionStorage.setItem("phonebench", JSON.stringify(state));
  keep();
  const log = (kind, data = {}) => fetch("/log", {
    method: "POST", keepalive: true,
    body: JSON.stringify({ run: state.run, load: state.loads, ms: ms(), kind, ...data }),
  }).catch(() => {});

  // WebGL textures made and deleted: a leak shows as made − deleted growing question by question
  const P = WebGL2RenderingContext.prototype;
  const mk = P.createTexture, rm = P.deleteTexture;
  window.texMade = 0; window.texGone = 0;
  P.createTexture = function () { window.texMade++; return mk.apply(this, arguments); };
  P.deleteTexture = function () { window.texGone++; return rm.apply(this, arguments); };
  window.addEventListener("error", e => log("error", { message: String(e.message), at: `${e.filename}:${e.lineno}` }));
  window.addEventListener("unhandledrejection", e => log("error", { message: String(e.reason) }));

  const panel = document.createElement("div");
  panel.style.cssText = "position:fixed;left:0;right:0;top:0;z-index:99999;background:#14213d;color:#fff;" +
    "font:14px/1.4 system-ui;padding:8px 12px;white-space:pre-wrap";
  const show = s => { panel.textContent = "Тест телефона — " + s; };
  // THE PANEL, A SECOND AT A TIME (the operator could not tell what the phone was doing): the question, its seconds,
  // the model's step, the answers so far — ✅ understood, ❓ asked back, ⛔ refused (as the page should)
  const now = { i: -1, q: "", t: 0 };
  const tally = () => (state.results || []).map(r => `${r.i}${r.mark}`).join(" ");
  const mark = said => /^(Understood as|Понял так)/.test(said) ? "✅" : /не вычисляется|not computed|cannot/i.test(said)
    ? "⛔" : "❓";
  setInterval(() => {
    if (now.i < 0) return;
    const read = (localStorage.getItem("okay-desk.read") || "").replace(/^read \d+: /, "").replace(/, GPU.*$/, "");
    const stage = localStorage.getItem("okay-desk.stage") || "";
    panel.textContent = `Тест телефона · вопрос ${now.i + 1} из ${QUESTIONS.length} · ${Math.round((performance.now() -
      now.t) / 1000)} с\n«${now.q}»\nмодель: ${stage || read || "…"}\nответы: ${tally() || "пока нет"}` +
      "\n(экран не гасить; цифры обновляются, когда телефон между шагами)";
  }, 1000);
  // the page's own words about the model (the frame reader's program, a budget refused): kept with the run
  for (const level of ["log", "error", "warn"]) {
    const orig = console[level].bind(console);
    console[level] = (...a) => {
      const text = a.map(String).join(" ");
      if (level !== "log" || /frame reader|budget|WebGL|model/i.test(text))
        log("console", { level, text: text.slice(0, 600) });
      orig(...a);
    };
  }
  const sleep = n => new Promise(r => setTimeout(r, n));
  const memory = () => {
    const m = window.okayModelProgress;
    return m ? { gpuMB: Math.round(m.gpuBytes / 1048576), peakMB: Math.round(m.peakGpuBytes / 1048576), stage: m.stage }
      : {};
  };

  // ---- 1. the device
  function device() {
    const c = document.createElement("canvas");
    const gl = c.getContext("webgl2");
    const out = {
      ua: navigator.userAgent, screen: `${screen.width}x${screen.height}@${devicePixelRatio}`,
      cores: navigator.hardwareConcurrency, deviceMemory: navigator.deviceMemory, webgpu: !!navigator.gpu,
      webgl2: !!gl,
    };
    if (gl) {
      const dbg = gl.getExtension("WEBGL_debug_renderer_info");
      out.renderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
      out.maxTexture = gl.getParameter(gl.MAX_TEXTURE_SIZE);
      out.maxTextureUnits = gl.getParameter(gl.MAX_TEXTURE_IMAGE_UNITS);
      out.floatTarget = !!gl.getExtension("EXT_color_buffer_float");
      out.extensions = gl.getSupportedExtensions().length;
      gl.getExtension("WEBGL_lose_context")?.loseContext();
    }
    return out;
  }

  // ---- 2. the GPU's speed: an n×n by n×n product in a fragment shader (R32F), best of three
  function speed(n) {
    const c = document.createElement("canvas"); c.width = c.height = 1;
    const gl = c.getContext("webgl2");
    if (!gl || !gl.getExtension("EXT_color_buffer_float")) return { error: "no WebGL2 float target" };
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, `#version 300 es
      void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); gl_Position = vec4(p * 2.0 - 1.0, 0, 1); }`));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, `#version 300 es
      precision highp float; uniform highp sampler2D A, B; uniform int N; out float o;
      void main() { ivec2 p = ivec2(gl_FragCoord.xy); float s = 0.0;
        for (int k = 0; k < N; k++) s += texelFetch(A, ivec2(k, p.y), 0).r * texelFetch(B, ivec2(p.x, k), 0).r;
        o = s; }`));
    gl.linkProgram(prog); gl.useProgram(prog);
    const tex = data => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32F, n, n, 0, gl.RED, gl.FLOAT, data);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST); return t; };
    const rnd = () => { const a = new Float32Array(n * n); for (let i = 0; i < a.length; i++) a[i] = Math.random(); return a; };
    const a = tex(rnd()), b = tex(rnd()), out = tex(null);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, out, 0);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, a);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, b);
    gl.uniform1i(gl.getUniformLocation(prog, "A"), 0); gl.uniform1i(gl.getUniformLocation(prog, "B"), 1);
    gl.uniform1i(gl.getUniformLocation(prog, "N"), n);
    gl.viewport(0, 0, n, n);
    const px = new Float32Array(4);
    let best = Infinity;
    for (let i = 0; i < 4; i++) {
      const s = performance.now();
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.FLOAT, px);
      const t = performance.now() - s;
      if (i > 0) best = Math.min(best, t);   // the first draw compiles
    }
    gl.getExtension("WEBGL_lose_context")?.loseContext();
    return { n, bestMs: Math.round(best), gflops: +(2 * n ** 3 / best / 1e6).toFixed(1) };
  }

  // ---- 3. the page's own questions, typed into the chat as a person would
  const asking = () => [...document.querySelectorAll("button")].find(b => b.textContent.trim() === "Ask");
  const marks = () => [...document.querySelectorAll(
    ".okay-mark-user, .okay-mark-model, .okay-mark-system, .okay-mark-result, .okay-mark-check")];
  async function until(test, limit) {
    const s = performance.now();
    while (!test()) { if (performance.now() - s > limit) return false; await sleep(200); }
    return true;
  }
  async function ask(i, text) {
    const field = [...document.querySelectorAll("textarea")].filter(t => t.offsetParent !== null).pop();
    const before = marks().length;
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
    set.call(field, text);
    field.dispatchEvent(new Event("input", { bubbles: true }));
    field.dispatchEvent(new Event("change", { bubbles: true }));
    await sleep(100);
    const s = performance.now();
    asking().click();
    await until(() => !asking(), 5000);                 // thinking: the button is gone
    const done = await until(() => asking() && marks().length > before + 1, 600000);
    const seconds = +((performance.now() - s) / 1000).toFixed(1);
    await sleep(300);
    const said = marks().slice(before).map(m => m.innerText.trim()).filter(t => t !== text).join(" ‖ ");
    return { i: i + 1, q: text, seconds, done, said: said.slice(0, 1500),
      textures: window.texMade - window.texGone, ...memory() };
  }

  // ---- 4. the room left: GPU memory taken in 64 MB steps beside the loaded model until the device refuses
  async function room() {
    const c = document.createElement("canvas"); c.width = c.height = 1;
    const gl = c.getContext("webgl2");
    if (!gl) return { error: "no WebGL2" };
    const side = 4096, step = side * side * 4 / 1048576;   // RGBA8: 64 MB a texture
    const kept = [];
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    for (let i = 1; i <= 48; i++) {                        // at most 3 GB
      const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, side, side);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
      gl.viewport(0, 0, side, side); gl.clearColor(i / 48, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
      const px = new Uint8Array(4); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);   // made real
      const err = gl.getError();
      if (err !== gl.NO_ERROR || gl.isContextLost()) { return { extraMB: (i - 1) * step, stop: `error ${err}` }; }
      kept.push(t);
      state.step = `room ${i * step} MB`; keep();
      if (i % 4 === 0) { log("room", { extraMB: i * step }); show(`room: ${i * step} MB more taken`); await sleep(50); }
    }
    kept.forEach(t => gl.deleteTexture(t));
    gl.getExtension("WEBGL_lose_context")?.loseContext();
    return { extraMB: 48 * step, stop: "the bench's own limit (3 GB)" };
  }

  // THE SCREEN KEPT ON (the iPhone locked mid-load and the download died): the Wake Lock where the page may have it
  // (a secure context — not http on the local network), else a silent video of a second played in a loop, which
  // iOS does not sleep over (NoSleep.js's way and its video, MIT). Both need a press: the bench starts with one
  const NOSLEEP = "data:video/mp4;base64,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";
  async function awake() {
    let how = "none";
    try { if (navigator.wakeLock) { await navigator.wakeLock.request("screen"); how = "wake lock"; } } catch (e) {}
    if (how === "none") {
      const v = document.createElement("video");
      v.setAttribute("playsinline", ""); v.setAttribute("muted", ""); v.muted = true; v.loop = true;
      v.setAttribute("title", "awake"); v.src = NOSLEEP;
      v.style.cssText = "position:fixed;width:1px;height:1px;opacity:0.01;bottom:0;left:0";
      document.body.appendChild(v);
      try { await v.play(); how = "video"; } catch (e) { how = "video refused: " + e; }
    }
    log("awake", { how });
    return how;
  }
  async function pressToStart() {
    const b = document.createElement("button");
    b.textContent = "Нажмите, чтобы начать тест\n(экран не будет гаснуть)";
    b.style.cssText = "position:fixed;left:10%;right:10%;top:35%;z-index:100000;padding:28px;" +
      "font:600 20px system-ui;" +
      "white-space:pre-wrap;border-radius:16px;border:0;background:#2b5bd7;color:#fff";
    document.body.appendChild(b);
    // after a reload the operator may be away: on by itself after 20 s (the screen may sleep then)
    await new Promise(done => {
      b.addEventListener("click", () => awake().then(done)); setTimeout(done, saved ? 20000 : 1e9); });
    b.remove();
  }

  async function main() {
    await until(() => document.body, 10000);
    document.body.appendChild(panel);
    // a press on a phone (a driven browser on the Mac has none to give)
    if (!navigator.webdriver) { show("нажмите синюю кнопку"); await pressToStart(); }
    // the device dropped the page: where the bench was, the page's last stage and GPU bytes (the beat below)
    if (saved) log("reloaded", { after: saved.step, lastBeat: sessionStorage.getItem("phonebench-beat"),
      pageStage: localStorage.getItem("okay-desk.stage"), lastRead: localStorage.getItem("okay-desk.read") });
    // THE BEAT: the page's stage and GPU bytes kept every second — a killed tab tells them on its reload
    let beat = "";
    setInterval(() => {
      const g = window.okayGpu ? window.okayGpu() : [0, 0];
      const now = (localStorage.getItem("okay-desk.stage") || "-") + " | GPU " + Math.round(g[0] / 1048576) +
        " MB, peak " + Math.round(g[1] / 1048576) + " MB | " + (localStorage.getItem("okay-desk.read") || "");
      if (now !== beat) { beat = now; sessionStorage.setItem("phonebench-beat", ms() + " ms: " + now); log("beat", { now }); }
    }, 1000);
    if (state.step === "start") {
      show("the device…");
      log("device", device());
      for (const n of (location.search.includes("speed") ? [512, 1024] : [])) {   // known: 34 GFLOPS (phone.md §4.1)
        state.step = `speed ${n}`; keep();
        try { log("speed", speed(n)); } catch (e) { log("speed", { n, error: String(e) }); }
      }
      state.step = location.search.includes("room") ? "room" : "q 0"; keep();
    }
    if (state.step.startsWith("q ")) {
      await until(() => asking(), 60000);
      // the page's sample tables (orders, customers, edges): Examples → the sample's Open → back in the chat
      const named = words => [...document.querySelectorAll("button, a")].filter(b => b.textContent.trim() === words);
      // an example's block: the nearest ancestor of its Open button that carries its "Source:" line
      const block = b => { let e = b; while (e && !(e.innerText || "").includes("Source:")) e = e.parentElement; return e; };
      const sampleOpen = () => named("Open").find(b => (block(b)?.innerText || "").includes("sample/orders.csv"));
      named("Examples")[0].click();
      await until(sampleOpen, 20000);
      const open = sampleOpen();
      if (!open) { log("error", { message: "the sample's Open button not found" }); return; }
      open.click();
      // opened: the chat's data link says how many tables are open ("Data: 3 open")
      const dataLink = () => [...document.querySelectorAll("a, button")].map(b => b.textContent.trim())
        .find(t => t.startsWith("Data:"));
      const opened = await until(() => asking() && /Data: \d+ open/.test(dataLink() || ""), 30000);
      log("tables", { opened, data: dataLink(), page: document.body.innerText.slice(0, 600), ...memory() });
      if (!opened) { show("the sample's tables did not open — see the Mac's log"); return; }
      let from = +state.step.slice(2);
      if (saved && saved.step.startsWith("q ")) { log("skipped", { i: from + 1, why: "the page reloaded on it" }); from++; }
      for (let i = from; i < (location.search.includes("one") ? from + 1 : QUESTIONS.length); i++) {
        state.step = `q ${i}`; keep();
        show(`вопрос ${i + 1} из ${QUESTIONS.length}${i === 0 ? " (сначала загрузка модели, ~3.5 мин)" : ""}…`);
        now.i = i; now.q = QUESTIONS[i]; now.t = performance.now();
        const r = await ask(i, QUESTIONS[i]);
        log("answer", r);
        state.results = (state.results || []).concat([{ i: i + 1, mark: mark(r.said) }]); keep();
        if (!r.done) { log("stuck", { i: i + 1 }); break; }
      }
      state.step = "room"; keep();
    }
    if (state.step.startsWith("room")) {
      if (saved && saved.step.startsWith("room ")) log("room", { killedAt: saved.step });
      else {
        show("measuring the room left on the GPU…");
        log("room", await room());
      }
    }
    state.step = "done"; keep();
    log("done", memory());
    now.i = -1;
    show(`готово, спасибо — результаты на Mac.\nответы: ${tally()}\n(ещё раз: открыть с ?again)`);
  }
  if (location.search.includes("nobench")) return;
  if (location.search.includes("again")) { sessionStorage.removeItem("phonebench"); location.search = location.search.replace(/[?&]?again/, "").replace(/^&/, "?"); }
  else if (saved && saved.step === "done") { document.addEventListener("DOMContentLoaded", () => {
    document.body.appendChild(panel); show("already done on this tab — open with ?again to run once more"); }); }
  else main();
})();
