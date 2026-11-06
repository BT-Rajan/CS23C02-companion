// Runs the phone's Python runner page in headless Chromium, the way the app hosts it: files are served only from the
// bundled assets at https://appassets.androidplatform.net/assets/ (like WebViewAssetLoader) and every other request is
// refused (like WebSettings.blockNetworkLoads). Usage: node android/runner-test/runner-test.cjs <runnerAssets dir>
"use strict";
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(process.argv[2] || "android/app/build/generated/assets/runnerAssets");
const ORIGIN = "https://appassets.androidplatform.net";
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json",
  ".zip": "application/zip", ".py": "text/plain" };
let failures = 0;
const check = (ok, what) => { console.log((ok ? "PASS " : "FAIL ") + what); if (!ok) failures++; };

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  const context = await browser.newContext();
  const blocked = [];
  await context.route("**/*", (route) => {
    const url = new URL(route.request().url());
    if (url.origin === ORIGIN && url.pathname.startsWith("/assets/")) {
      const file = path.join(ROOT, url.pathname.slice("/assets/".length));
      if (file.startsWith(ROOT) && fs.existsSync(file)) {
        return route.fulfill({ status: 200, body: fs.readFileSync(file),
          headers: { "content-type": TYPES[path.extname(file)] || "application/octet-stream" } });
      }
    }
    blocked.push(url.href);
    return route.fulfill({ status: 403, body: "" });
  });
  const page = await context.newPage();
  const csp = [];
  page.on("console", (m) => { if (/Content Security Policy/i.test(m.text())) csp.push(m.text()); });
  await page.addInitScript(() => {
    window.__results = {}; window.__ready = null; window.__states = [];
    window.Bridge = {
      onReady: (ms) => { window.__ready = Number(ms); },
      onResult: (id, json) => { window.__results[id] = JSON.parse(json); },
      onState: (s) => { window.__states.push(s); },
    };
  });
  await page.goto(ORIGIN + "/assets/runner/index.html");
  await page.waitForFunction(() => window.__ready !== null, null, { timeout: 120000 });
  const readyMs = await page.evaluate(() => window.__ready);
  check(readyMs > 0, `runner ready (load + warm-up) in ${readyMs} ms on this machine`);

  let next = 1;
  async function run(src, stdin = "", timeoutMs = 8000) {
    const id = next++;
    const t0 = Date.now();
    await page.evaluate(([id, src, stdin, t]) => window.runPython(id, src, stdin, t), [id, src, stdin, timeoutMs]);
    await page.waitForFunction((id) => window.__results[id] !== undefined, id, { timeout: timeoutMs + 120000 });
    const r = await page.evaluate((id) => window.__results[id], id);
    r.ms = Date.now() - t0;
    return r;
  }

  const demo = fs.readFileSync(path.join(__dirname, "../../runtime/demos/01_running_total.py"), "utf8");
  let r = await run(demo);
  check(r.recording.status === "ok" && r.recording.stdout === "Total: 221\nAverage: 73.7\n", `book program records (${r.ms} ms)`);
  check(r.lines.length === 7 && r.refs["CON-0010"] && r.refs["CON-0010"].title === "Repeating with for", "lines explained with book refs");
  const asset = JSON.parse(fs.readFileSync(path.join(__dirname, "../app/src/main/assets/stepper/demos.json"), "utf8"));
  check(JSON.stringify(r.recording) === JSON.stringify(asset[0].recording), "phone recording is identical to the build-time recording");

  r = await run("for i in range(3)\n    print(i)\n");
  check(r.recording.status === "syntax_error" && r.recording.error.line === 1, "syntax error reported with its line");

  r = await run("n = int(input('n? '))\nprint(n * 2)\n");
  check(r.recording.status === "needs_input", "input() pauses for input");
  r = await run("n = int(input('n? '))\nprint(n * 2)\n", "21");
  check(r.recording.stdout === "n? 21\n42\n", "re-run with input continues");

  r = await run("while True:\n    try:\n        x = 1\n    except:\n        pass\n");
  check(r.recording.status === "step_limit", `runaway loop stops at the step limit (${r.ms} ms)`);

  r = await run("import js\njs.fetch('https://example.com')\n");
  check(r.recording.status === "error" && r.recording.error.type === "ImportError", "Python cannot import the browser bridge (js)");
  r = await run("import pyodide\n");
  check(r.recording.status === "error" && r.recording.error.type === "ImportError", "Python cannot import pyodide");

  r = await run("x = 10 ** (10 ** 7)\n", "", 3000);  // one C-level operation: no line events to stop it
  check(r.recording.status === "killed" || r.recording.status === "time_limit", `C-level hang stopped by the watchdog (${r.recording.status}, ${r.ms} ms)`);
  r = await run("print('after restart')\n");
  check(r.recording.stdout === "after restart\n", `runner works again after a restart (${r.ms} ms incl. warm-up)`);

  const outside = blocked.filter((u) => !u.startsWith(ORIGIN));
  check(outside.length === 0, `no request left the app (${outside.length} attempted)`);
  check(csp.length === 0, `no Content-Security-Policy violations (${csp.length})`);

  // Fallback mode (Python on the page, used if a WebView cannot start the worker) must give the same answers.
  const page2 = await context.newPage();
  await page2.addInitScript(() => {
    window.__results = {}; window.__ready = null; window.__states = [];
    window.Bridge = { onReady: (ms) => { window.__ready = Number(ms); }, onResult: (id, j) => { window.__results[id] = JSON.parse(j); },
      onState: (s) => { window.__states.push(s); } };
  });
  await page2.goto(ORIGIN + "/assets/runner/index.html?mode=page");
  await page2.waitForFunction(() => window.__ready !== null, null, { timeout: 120000 });
  const states = await page2.evaluate(() => window.__states);
  await page2.evaluate((src) => window.runPython(1, src, "", 8000), demo);
  await page2.waitForFunction(() => window.__results[1] !== undefined, null, { timeout: 60000 });
  const r2 = await page2.evaluate(() => window.__results[1]);
  check(states.includes("mode:page") && JSON.stringify(r2.recording) === JSON.stringify(asset[0].recording),
    "fallback (Python on the page) gives the same recording");

  await browser.close();
  console.log(failures ? `${failures} FAILED` : "all runner checks passed");
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
