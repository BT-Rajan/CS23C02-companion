import pathlib
import sys
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tutor"))
import explain  # noqa: E402

REG = explain.load_registry()


def lines(src):
    return {l["n"]: l for l in explain.explain_program(textwrap.dedent(src), REG)["lines"]}


def test_every_construct_has_a_book_reference():
    assert len(REG) >= 20
    for cid, ref in REG.items():
        assert cid.startswith("CON-") and ref["topic"].startswith("TOP-") and ref["title"] and ref["section"]
        assert ref["sample"] is True  # must flip to False only when the real book registry replaces this file


def test_assignment_first_time_and_update():
    l = lines("total = 0\nm = 5\ntotal = total + m\ntotal = 9\n")
    assert l[1]["explain"] == "Creates the variable total and stores 0 in it."
    assert l[3]["explain"].startswith("Updates total:")
    assert l[4]["explain"].startswith("Changes the variable total")
    assert l[3]["constructs"] == ["CON-0001", "CON-0006"]


def test_loops_and_conditions():
    l = lines("""
        for i in range(3):
            if i % 2 == 0:
                print(i)
            elif i == 1:
                continue
            else:
                break
        while i > 0:
            i -= 1
    """)
    assert "each number in range(3)" in l[2]["explain"] and l[2]["constructs"] == ["CON-0010", "CON-0011"]
    assert set(l[3]["constructs"]) == {"CON-0008", "CON-0006", "CON-0007"}
    assert l[5]["explain"].startswith("Only if the earlier conditions were False")
    assert l[7]["explain"].startswith("Otherwise") and l[7]["constructs"] == ["CON-0008"]
    assert l[8]["explain"] == "Leaves the loop immediately."
    assert l[9]["constructs"] == ["CON-0009", "CON-0007"]
    assert l[10]["explain"] == "Subtracts 1 from i (the same as i = i - 1)."


def test_input_and_conversion():
    l = lines("name = input('Name? ')\nage = int(input('Age? '))\n")
    assert l[1]["explain"] == "Asks the user to type something and stores it in name as text."
    assert "turns it into a whole number" in l[2]["explain"]
    assert l[2]["constructs"] == ["CON-0001", "CON-0005", "CON-0004"]


def test_functions_calls_and_recursion():
    l = lines("""
        def fact(n):
            if n <= 1:
                return 1
            return n * fact(n - 1)
        x = fact(3)
        fact(2)
    """)
    assert l[2]["explain"].startswith("Defines a function called fact that takes n.")
    assert "CON-0017" in l[5]["constructs"] and "smaller value" in l[5]["explain"]
    assert l[6]["constructs"] == ["CON-0001", "CON-0016"]
    assert l[7]["explain"].startswith("Calls the function fact(2)")


def test_shape_blanks_names_and_values_only():
    assert explain.shape("for m in marks:") == "for _ in _:"
    assert explain.shape("    total = total + m") == "    _ = _ + _"
    assert explain.shape('print("Total:", round(avg, 1))') == "print(_, round(_, _))"
    assert explain.shape("if n <= 1:") == "if _ <= _:"
    assert explain.shape("x = [") == "…"  # unfinished line: no crash


def test_refs_cover_exactly_the_constructs_used():
    r = explain.explain_program("x = 1\nprint(x)\n", REG)
    assert set(r["refs"]) == {"CON-0001", "CON-0003"}


def test_blank_and_comment_lines_are_skipped():
    assert set(lines("# start\nx = 1\n\nprint(x)\n")) == {2, 4}
