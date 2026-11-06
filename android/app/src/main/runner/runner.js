// Main page of the Python runner (ADR 0013 slice 3). Preferred mode: Python in a Web Worker, stopped by terminating the
// worker. Fallback mode (if a worker cannot start on this WebView): Python on this page; then the app's own watchdog
// rebuilds the whole WebView if a run hangs. Talks to the app only through window.Bridge:
//   onReady(loadMs) · onResult(id, json) · onState(text)       — nothing else is exposed in either direction.
"use strict";
(function () {
  const bridge = window.Bridge || { onReady() {}, onResult() {}, onState() {} };
  const forcePage = location.search.indexOf("mode=page") >= 0;   // used by tests
  let engine = null, startedAt = 0;
  const queue = [], timers = new Map();

  const stoppedJson = JSON.stringify({ recording: { version: 1, status: "killed", steps: [], stdout: "",
    error: { type: "Stopped", message: "The program did not finish in time and was stopped.", line: null } }, lines: [], refs: {} });

  function ready(mode) {
    engine.ready = true;
    bridge.onState("mode:" + mode);
    bridge.onReady(String(Math.round(performance.now() - startedAt)));
    while (queue.length) engine.send(queue.shift());
  }

  function workerEngine() {
    const w = new Worker("worker.js");
    const e = { ready: false, kind: "worker" };
    e.send = (req) => { timers.set(req.id, setTimeout(() => kill(req.id), req.timeoutMs)); w.postMessage(req); };
    e.stop = () => w.terminate();
    w.onmessage = (m) => {
      const d = m.data;
      if (d.type === "ready") ready("worker");
      else if (d.type === "result") { clearTimeout(timers.get(d.id)); timers.delete(d.id); bridge.onResult(String(d.id), d.json); }
      else if (d.type === "failed") { w.terminate(); pageEngineStart("worker failed: " + d.message); }
    };
    w.onerror = (ev) => { ev.preventDefault && ev.preventDefault(); w.terminate(); if (!e.ready) pageEngineStart("worker error"); };
    return e;
  }

  function kill(id) {
    engine.stop();
    timers.forEach((t) => clearTimeout(t)); timers.clear();
    bridge.onResult(String(id), stoppedJson);
    queue.length = 0;
    start();
  }

  function pageEngineStart(reason) {
    bridge.onState("fallback: " + reason);
    const e = { ready: false, kind: "page", py: null };
    e.send = (req) => setTimeout(() => bridge.onResult(String(req.id), runLive(e.py, req.source, req.stdin)), 0);
    e.stop = () => {};
    engine = e;
    const s = document.createElement("script");
    s.src = "pyodide/pyodide.js";
    s.onload = () => {
      const c = document.createElement("script");
      c.src = "pycore.js";
      c.onload = () => initPython("./").then((py) => { e.py = py; ready("page"); },
        (err) => bridge.onState("failed: " + String((err && err.message) || err)));
      document.head.appendChild(c);
    };
    s.onerror = () => bridge.onState("failed: cannot load pyodide.js");
    document.head.appendChild(s);
  }

  function start() {
    startedAt = performance.now();
    bridge.onState("starting");
    if (forcePage || typeof Worker === "undefined") { pageEngineStart(forcePage ? "forced" : "no Worker"); return; }
    try { engine = workerEngine(); } catch (err) { pageEngineStart("worker threw"); }
  }

  // Called by the app: window.runPython(id, source, stdin, timeoutMs)
  window.runPython = function (id, source, stdin, timeoutMs) {
    const req = { id: id, source: String(source), stdin: String(stdin || ""), timeoutMs: timeoutMs || 8000 };
    if (engine && engine.ready) engine.send(req); else queue.push(req);
  };

  start();
})();
