// Python in a dedicated worker, so a runaway program can be stopped with worker.terminate() (ADR 0006 hard stop).
"use strict";
importScripts("pyodide/pyodide.js", "pycore.js");
const ready = initPython("./").then(
  (py) => { postMessage({ type: "ready" }); return py; },
  (err) => { postMessage({ type: "failed", message: String((err && err.message) || err) }); throw err; });
onmessage = async (e) => {
  const py = await ready;
  postMessage({ type: "result", id: e.data.id, json: runLive(py, e.data.source, e.data.stdin) });
};
