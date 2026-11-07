// Shared by the worker and the in-page fallback: load Pyodide + our Python files, then run requests.
"use strict";
const PY_FILES = ["stepper.py", "explain.py", "live.py", "constructs.sample.json"];

async function initPython(base) {
  const py = await loadPyodide({ indexURL: base + "pyodide/", fullStdLib: false });
  for (const f of PY_FILES) {
    const res = await fetch(base + "py/" + f);
    if (!res.ok) throw new Error("missing " + f);
    py.FS.writeFile("/home/pyodide/" + f, await res.text());
  }
  py.runPython("import sys; sys.path.insert(0, '/home/pyodide'); import live");
  py.runPython("live.run('x = 1\\n')"); // warm-up: pay one-time costs before the student presses anything
  return py;
}

function runLive(py, source, stdin, echo) {
  py.globals.set("_SRC", source);
  py.globals.set("_STDIN", stdin);
  py.globals.set("_ECHO", echo !== false);
  try {
    return py.runPython("live.run(_SRC, _STDIN, _ECHO)");
  } catch (err) {
    return JSON.stringify({ recording: { version: 1, status: "error", steps: [], stdout: "",
      error: { type: "RunnerError", message: String((err && err.message) || err).slice(0, 200), line: null } }, lines: [], refs: {} });
  }
}
