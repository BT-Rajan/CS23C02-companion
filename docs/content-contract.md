# Content contract (proposed — textbook repo `CS23C02` is currently empty)
Nothing here is written into the textbook repo until approved. This is the contract the content tools will validate against.

## Repository layout (proposal)
```
book.yaml                      # edition id, title, version, min_app_version, python_version
units/u01-<slug>/
  unit.yaml                    # id, title, order, objectives
  topics/t01-<slug>/
    lesson.md                  # front matter: id, title, objectives[], keywords[], est_minutes (≤10)
    examples/ex01.py + ex01.out.txt
    figures/*.svg|png          # with alt text in front matter
    mcq.yaml                   # items: id, stem, options[], key, explanation, option_explanations?
    trace.yaml                 # id, program, inputs?, steps/answer, explanation
    exercises/e01-<slug>/
      problem.md  starter.py
      tests.yaml               # public[] and hidden[] (stdin, expected stdout)
      solution.py  hints.md  policy.yaml
  assessment.yaml              # unit assessment: question ids drawn from mcq/trace, counts, rules
```
All IDs are stable and never reused. Terminology and examples live only here.

## Validation build (runs in the textbook repo's CI)
1. JSON-Schema validation of every YAML/front matter.
2. Lesson length ≤ 10 minutes explanation estimate; MCQ count rule (5 per normal lesson unless justified in `mcq.yaml`).
3. Every exercise's `solution.py` is executed in the same sandbox image against **all** tests; it must pass. Every example's output must equal `.out.txt`.
4. Every MCQ has exactly one key and an explanation; duplicate/near-duplicate stems are flagged.
5. Link/ID integrity, image alt text, allowed-import check against the Python allow-list.
6. Emit **public package** (no keys/hidden tests/solutions) and **protected set** (for the server), plus a signed manifest. CI fails if any protected field appears in the public package.

## Versioning
`book.yaml.version` is semver. Content changes that alter IDs, keys or tests bump minor/major and are recorded so progress maps forward (retired IDs mapped or archived). An edition change is a new edition id, not a patch.
