"""Regenerates fixtures/cs23c02-fixture. FIXTURE DATA ONLY: follows the textbook's approved ID structures, is not book content."""
import json, pathlib
R = pathlib.Path(__file__).parent / "cs23c02-fixture"
def w(rel, obj): (R/rel).write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")
w("book.json", {"id": "BOK-0001", "title": "FIXTURE — not book content", "edition": "fixture-1", "version": "0.1.0",
                "min_app_version": "0.1.0", "python": "3.12", "note": "Test data that follows the textbook's approved ID structures. Not the textbook."})
w("nav.json", {"units": [{"id": "UNT-0001", "title": "Fixture unit: getting started", "assessment": "ASM-0002", "topics": [
    {"id": "TOP-0001", "title": "Values and variables", "lessons": ["LSN-0001", "LSN-0002"]},
    {"id": "TOP-0002", "title": "Making decisions", "lessons": ["LSN-0003"]}]}]})
w("lessons/LSN-0001.json", {"id": "LSN-0001", "title": "What is a variable?", "topic": "TOP-0001", "minutes": 6, "blocks": [
    {"type": "paragraph", "text": "A variable is a name that refers to a value."},
    {"type": "definition", "term": "variable", "text": "A name bound to a value."},
    {"type": "code", "code": "age = 19\nprint(age)", "expected_output": "19"},
    {"type": "syntax", "text": "name = expression"}],
    "quiz": "ASM-0001", "practice": {"items": ["ITM-0001", "ITM-0002", "ITM-0003"], "exercises": []}})
w("lessons/LSN-0002.json", {"id": "LSN-0002", "title": "Changing a variable", "topic": "TOP-0001", "minutes": 5, "blocks": [
    {"type": "paragraph", "text": "Assigning again replaces the old value."},
    {"type": "code", "code": "x = 1\nx = x + 1\nprint(x)", "expected_output": "2"}],
    "quiz": None, "practice": {"items": [], "exercises": ["EXR-0001"]}})
w("lessons/LSN-0003.json", {"id": "LSN-0003", "title": "if and else", "topic": "TOP-0002", "minutes": 8, "blocks": [
    {"type": "paragraph", "text": "An if statement runs code only when a condition is true."},
    {"type": "code", "code": "n = 7\nif n % 2 == 0:\n    print('even')\nelse:\n    print('odd')", "expected_output": "odd"}],
    "quiz": None, "practice": {"items": ["ITM-0004"], "exercises": ["EXR-0002"]}})
def mcq(i, stem, opts, ans, expl, oexp, type="mcq", purpose="practice", code=None):
    obj = {"id": f"ITM-{i:04d}", "type": type, "purpose": purpose, "level": 1, "stem": stem,
           "options": [{"id": k, "text": v} for k, v in opts.items()], "answer": ans, "explanation": expl, "option_explanations": oexp}
    if code is not None:
        obj["code"] = code
    w(f"items/ITM-{i:04d}.json", obj)
mcq(1, "What does `x = 5` do?", {"a": "Compares x with 5", "b": "Binds the name x to the value 5", "c": "Prints 5"}, "b",
    "FIXTURE-EXPLAIN-1: assignment binds a name to a value; it does not compare or print.", {"a": "That would be ==.", "c": "Assignment prints nothing."})
mcq(2, "After `x = 3` then `x = x * 2`, what is x?", {"a": "3", "b": "5", "c": "6"}, "c",
    "FIXTURE-EXPLAIN-2: the right side is evaluated first using the old x (3), giving 6.", {"a": "x was reassigned.", "b": "Multiplication, not addition."})
mcq(3, "Which is a valid variable name?", {"a": "2nd_place", "b": "total_score", "c": "my-var"}, "b",
    "FIXTURE-EXPLAIN-3: names cannot start with a digit or contain hyphens.", {"a": "Starts with a digit.", "c": "Hyphen is subtraction."})
mcq(4, "What does this program print?", {"a": "even", "b": "odd", "c": "7"}, "b",
    "FIXTURE-EXPLAIN-4: 7 % 2 is 1, so the condition is False and the else branch prints odd.",
    {"a": "7 % 2 is 1, not 0.", "c": "print shows the string, not the number."}, type="trace",
    code="n = 7\nif n % 2 == 0:\n    print('even')\nelse:\n    print('odd')")
mcq(5, "FIXTURE assessment-only question: what is 2 + 3?", {"a": "5", "b": "23"}, "a",
    "FIXTURE-EXPLAIN-5: integer addition.", {}, purpose="assessment")
mcq(6, "FIXTURE assessment-only: which name is valid?", {"a": "1st", "b": "first", "c": "fir-st"}, "b",
    "FIXTURE-EXPLAIN-6: names start with a letter or _ and contain no hyphen.", {"a": "Starts with a digit.", "c": "Hyphen."},
    purpose="assessment")
mcq(7, "FIXTURE assessment-only: what does this print?", {"a": "3", "b": "4", "c": "12"}, "b",
    "FIXTURE-EXPLAIN-7: x becomes 3 + 1.", {"a": "x changed on line 2.", "c": "Addition, not concatenation."},
    type="trace", purpose="assessment", code="x = 3\nx = x + 1\nprint(x)")
w("assessments/ASM-0002.json", {"id": "ASM-0002", "title": "Unit 1 assessment", "purpose": "unit_assessment",
                                 "items": ["ITM-0005", "ITM-0006", "ITM-0007"], "rules": {"shuffle_items": True, "shuffle_options": True}})
w("assessments/ASM-0001.json", {"id": "ASM-0001", "title": "Lesson quiz: variables", "purpose": "lesson_quiz",
                                 "items": ["ITM-0001", "ITM-0002", "ITM-0003"], "rules": {"shuffle": True}})
def exr(i, title, statement, starter, tests, hints, approach, ref):
    w(f"exercises/EXR-{i:04d}.json", {"id": f"EXR-{i:04d}", "title": title, "statement": statement, "io_spec": "Read from input, print to output.",
      "starter": starter, "python": "3.12", "timeout_s": 2, "tests": tests, "hints": hints, "solution_approach": approach, "reference_solution": ref})
exr(1, "Double it", "Read an integer and print twice its value.", "n = int(input())\n# print twice n\n",
    [{"stdin": "4\n", "expect": "8\n", "visibility": "public"}, {"stdin": "-3\n", "expect": "-6\n", "visibility": "hidden"},
     {"stdin": "0\n", "expect": "0\n", "visibility": "hidden"}],
    ["FIXTURE-HINT-1: convert the input to an integer first.", "FIXTURE-HINT-2: multiply by 2 and print."],
    "FIXTURE-APPROACH: read, convert, multiply, print.", "n = int(input())\nprint(n * 2)  # FIXTURE-REFERENCE-SOLUTION-1\n")
exr(2, "Odd or even", "Read an integer. Print even or odd.", "n = int(input())\n",
    [{"stdin": "10\n", "expect": "even\n", "visibility": "public"}, {"stdin": "7\n", "expect": "odd\n", "visibility": "hidden"}],
    ["FIXTURE-HINT-3: use the remainder operator."], "FIXTURE-APPROACH-2: n % 2.", "n = int(input())\nprint('even' if n % 2 == 0 else 'odd')  # FIXTURE-REFERENCE-SOLUTION-2\n")
