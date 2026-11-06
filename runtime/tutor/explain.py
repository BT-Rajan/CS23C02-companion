"""Book-grounded line explanations for the tutor (ADR 0014). Standard library only, so it runs in the book build and,
later, on the phone in Pyodide.

    explain_program(source, registry) -> {"lines": [...], "refs": {...}}

For every non-blank line: what it does (plain English), which constructs it uses (CON IDs from the book's construct
registry), a "shape" hint with names and values blanked out (`for _ in _:`), and the book references for those constructs.
No AI model: everything is derived from the code and the registry, so it is repeatable and always agrees with the book.
"""
from __future__ import annotations

import ast
import io
import json
import keyword
import pathlib
import tokenize

SAMPLE_REGISTRY = pathlib.Path(__file__).with_name("constructs.sample.json")
KEEP_NAMES = {"print", "input", "range", "len", "int", "float", "str", "round", "sum", "min", "max", "abs", "list",
              "sorted", "True", "False", "None", "self"}
CONVERSIONS = {"int": "a whole number", "float": "a decimal number", "str": "text"}


def load_registry(path=SAMPLE_REGISTRY) -> dict:
    return {k: v for k, v in json.loads(pathlib.Path(path).read_text()).items() if k.startswith("CON-")}


def shape(line: str) -> str:
    """Blank out names and literals but keep keywords, common built-ins, operators and indentation."""
    indent = line[: len(line) - len(line.lstrip())]
    out, prev_end = [], None
    try:
        toks = list(tokenize.generate_tokens(io.StringIO(line.strip() + "\n").readline))
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return indent + "…"
    for t in toks:
        if t.type in (tokenize.NEWLINE, tokenize.NL, tokenize.ENDMARKER, tokenize.INDENT, tokenize.DEDENT, tokenize.COMMENT):
            continue
        text = t.string
        if t.type == tokenize.NAME and not keyword.iskeyword(text) and text not in KEEP_NAMES:
            text = "_"
        elif t.type in (tokenize.NUMBER, tokenize.STRING) or (t.type == tokenize.FSTRING_START if hasattr(tokenize, "FSTRING_START") else False):
            text = "_"
        if prev_end is not None and t.start[1] > prev_end:
            out.append(" ")
        out.append(text)
        prev_end = t.end[1]
    return indent + "".join(out)


def _u(node) -> str:
    return ast.unparse(node)


class _Explainer:
    def __init__(self, tree: ast.Module):
        self.tree = tree
        self.assigned: set[str] = set()
        self.functions = {n.name for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)}
        self.parents: dict = {}
        for p in ast.walk(tree):
            for c in ast.iter_child_nodes(p):
                self.parents[c] = p

    def enclosing_function(self, node):
        while node in self.parents:
            node = self.parents[node]
            if isinstance(node, ast.FunctionDef):
                return node.name
        return None

    # ---- constructs used by the parts of a statement that sit on its first line --------------------------------------
    def constructs(self, stmt) -> list[str]:
        found: list[str] = []

        def add(c):
            if c not in found:
                found.append(c)

        heads = {
            ast.For: lambda s: [s.target, s.iter], ast.While: lambda s: [s.test], ast.If: lambda s: [s.test],
            ast.FunctionDef: lambda s: [], ast.Return: lambda s: [s.value] if s.value else [],
        }
        parts = heads[type(stmt)](stmt) if type(stmt) in heads else [stmt]
        kind = {ast.Assign: "CON-0001", ast.AnnAssign: "CON-0001", ast.AugAssign: "CON-0002", ast.If: "CON-0008",
                ast.While: "CON-0009", ast.For: "CON-0010", ast.FunctionDef: "CON-0014", ast.Return: "CON-0015",
                ast.Import: "CON-0018", ast.ImportFrom: "CON-0018", ast.Break: "CON-0020", ast.Continue: "CON-0020"}
        if type(stmt) in kind:
            add(kind[type(stmt)])
        here = self.enclosing_function(stmt) if not isinstance(stmt, ast.FunctionDef) else stmt.name
        for part in parts:
            for n in ast.walk(part):
                if isinstance(n, ast.BinOp):
                    add("CON-0006")
                elif isinstance(n, ast.Compare):
                    add("CON-0007")
                elif isinstance(n, ast.BoolOp):
                    add("CON-0019")
                elif isinstance(n, (ast.List, ast.Subscript)):
                    add("CON-0012")
                elif isinstance(n, ast.Call) and isinstance(n.func, ast.Name):
                    f = n.func.id
                    if f == "print":
                        add("CON-0003")
                    elif f == "input":
                        add("CON-0004")
                    elif f in CONVERSIONS:
                        add("CON-0005")
                    elif f == "range":
                        add("CON-0011")
                    elif f == "len":
                        add("CON-0013")
                    elif f in self.functions:
                        add("CON-0017" if f == here else "CON-0016")
        return found

    # ---- one plain sentence per statement ----------------------------------------------------------------------------
    def sentence(self, stmt, line_text: str) -> str:
        s = stmt
        if isinstance(s, ast.Assign) and len(s.targets) == 1:
            t = s.targets[0]
            if isinstance(t, ast.Tuple):
                return f"Works out the right-hand side first, then stores the values in {', '.join(_u(e) for e in t.elts)} at the same time."
            name = _u(t)
            v = s.value
            if isinstance(v, ast.Call) and isinstance(v.func, ast.Name) and v.func.id in CONVERSIONS and v.args \
                    and isinstance(v.args[0], ast.Call) and getattr(v.args[0].func, "id", "") == "input":
                return f"Asks the user to type something, turns it into {CONVERSIONS[v.func.id]}, and stores it in {name}."
            if isinstance(v, ast.Call) and getattr(v.func, "id", "") == "input":
                return f"Asks the user to type something and stores it in {name} as text."
            uses_itself = isinstance(t, ast.Name) and any(isinstance(n, ast.Name) and n.id == t.id for n in ast.walk(v))
            first = isinstance(t, ast.Name) and t.id not in self.assigned
            if isinstance(t, ast.Name):
                self.assigned.add(t.id)
            if isinstance(v, ast.Call) and getattr(v.func, "id", "") in self.functions:
                return f"Calls {v.func.id}({', '.join(_u(a) for a in v.args)}) and stores the value it returns in {name}."
            if uses_itself:
                return f"Updates {name}: works out {_u(v)} using the current value of {name}, then stores the result back in {name}."
            if isinstance(v, ast.Constant) or isinstance(v, (ast.List, ast.Tuple)):
                return f"{'Creates' if first else 'Changes'} the variable {name} and stores {_u(v)} in it."
            return f"Works out {_u(v)}, then stores the result in {name}."
        if isinstance(s, ast.AugAssign):
            op = {ast.Add: "Adds", ast.Sub: "Subtracts", ast.Mult: "Multiplies", ast.Div: "Divides"}.get(type(s.op), "Updates")
            t, v = _u(s.target), _u(s.value)
            sym = ast.unparse(ast.BinOp(s.target, s.op, s.value))
            how = {"Adds": f"Adds {v} to {t}", "Subtracts": f"Subtracts {v} from {t}", "Multiplies": f"Multiplies {t} by {v}",
                   "Divides": f"Divides {t} by {v}"}.get(op, f"Updates {t}")
            return f"{how} (the same as {t} = {sym})."
        if isinstance(s, ast.For):
            it = s.iter
            if isinstance(it, ast.Call) and getattr(it.func, "id", "") == "range":
                return f"Repeats the indented lines for each number in {_u(it)}; each time round, {_u(s.target)} holds the next number."
            return f"Repeats the indented lines once for each item in {_u(it)}; each time round, {_u(s.target)} holds the next item."
        if isinstance(s, ast.While):
            return f"Checks {_u(s.test)}. While it is True, the indented lines run again; when it becomes False, the loop ends."
        if isinstance(s, ast.If):
            word = "elif" if line_text.lstrip().startswith("elif") else "if"
            if word == "elif":
                return f"Only if the earlier conditions were False: checks {_u(s.test)} and runs the indented lines if it is True."
            return f"Checks whether {_u(s.test)} is True. The indented lines below run only if it is."
        if isinstance(s, ast.FunctionDef):
            params = ", ".join(a.arg for a in s.args.args)
            return (f"Defines a function called {s.name}" + (f" that takes {params}" if params else "") +
                    ". Its indented body does not run now; it runs each time the function is called.")
        if isinstance(s, ast.Return):
            if s.value is None:
                return "Ends the function here."
            rec = any(isinstance(n, ast.Call) and getattr(n.func, "id", "") == self.enclosing_function(s) for n in ast.walk(s.value))
            extra = " It first has to call the same function with a smaller value and wait for that answer." if rec else ""
            return f"Ends the function and sends {_u(s.value)} back to where it was called.{extra}"
        if isinstance(s, ast.Expr) and isinstance(s.value, ast.Call):
            c = s.value
            f = getattr(c.func, "id", None)
            if f == "print":
                return "Prints " + (", ".join(_u(a) for a in c.args) or "an empty line") + " to the output."
            if f in self.functions:
                return f"Calls the function {f}({', '.join(_u(a) for a in c.args)}); its body runs now."
            return f"Calls {_u(c.func)}."
        if isinstance(s, (ast.Import, ast.ImportFrom)):
            mod = s.module if isinstance(s, ast.ImportFrom) else ", ".join(a.name for a in s.names)
            return f"Makes the {mod} module available to this program."
        if isinstance(s, ast.Break):
            return "Leaves the loop immediately."
        if isinstance(s, ast.Continue):
            return "Skips the rest of this round of the loop and starts the next round."
        if isinstance(s, ast.Pass):
            return "Does nothing; Python needs at least one line here."
        return "Runs this statement."


def explain_program(source: str, registry: dict | None = None) -> dict:
    registry = load_registry() if registry is None else registry
    tree = ast.parse(source)
    ex = _Explainer(tree)
    lines = source.splitlines()
    by_line: dict[int, ast.stmt] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.stmt) and node.lineno not in by_line:
            by_line[node.lineno] = node
    # ast.walk is breadth-first; explanations must follow reading order so "Creates" vs "Changes" is right.
    out, used = [], []
    for n, text in enumerate(lines, start=1):
        if not text.strip() or text.lstrip().startswith("#"):
            continue
        stmt = by_line.get(n)
        if stmt is None:
            if text.strip() == "else:":
                item = {"n": n, "explain": "Otherwise (when the condition above was False), the indented lines below run.",
                        "constructs": ["CON-0008"]}
            else:
                item = {"n": n, "explain": "Continues the line above.", "constructs": []}
        else:
            item = {"n": n, "explain": ex.sentence(stmt, text), "constructs": ex.constructs(stmt)}
        item["shape"] = shape(text)
        out.append(item)
        used += [c for c in item["constructs"] if c not in used]
    refs = {c: registry[c] for c in used if c in registry}
    return {"lines": out, "refs": refs}
