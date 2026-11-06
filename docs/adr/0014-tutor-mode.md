# 0014 Tutor mode: "Show me" and "I'll type it", grounded in the book
**Status.** Approved direction from the owner (2026-11-06). Builds on ADR 0013 (stepper).

**Context.** The owner wants the stepper to act as a tutor: (1) when a student asks for a program, the app fills in the code and
teaches it line by line; (2) when the student types it themselves, the tutor travels with them and guides each line;
(3) every explanation points back to the book.

**Decision.**
1. **Ask for a program.** A search box ("Which program do you want?") matches the student's words against each book program's
   title, keywords and topic (e.g. "program to find factorial" → *Factorial using recursion*). Local and offline; no AI model.
2. **Show me.** The code is filled in and opened in the stepper. Each line carries *what this line does* (derived from the code),
   the author's step note if any, and a book reference.
3. **I'll type it.** An editor beside a tutor panel that updates as the student types. Each typed line is compared with the
   book's line: *correct*, *correct but spaced/quoted differently* (accepted), *indentation wrong*, *small typo* (with the first
   differing column), or *different*. When the typed lines are right, the panel explains what the **next** line must do, with
   three hint levels: 1) the purpose, 2) the shape (`for _ in _:`), 3) the line itself. Hints used are recorded locally only.
   When the program is complete, **Step through my code** opens the stepper.
4. **Book references.** Each line's constructs (assignment, `for`, `def`, `return`, `input`…) are found with Python's `ast` and
   mapped through the book's construct registry (spec §8, `CON` IDs, one introducing topic each, S6) to *Unit · Section · Topic*.
   Page numbers come from a page map emitted by the print build (book decision X4). Until real chapters exist the registry is a
   **clearly labelled sample** and the app shows "sample reference".
5. **Where the work happens.** For book programs, explanations, shapes and references are computed **at build time** by
   `runtime/tutor/explain.py` and shipped in the content package, so tutoring works offline on any phone. Explaining code the
   student invents (no book target) needs Python on the phone (ADR 0013 slice 3) and comes after it.
6. **Not in this release:** free-text chat with an AI model. It would need a budget, a provider decision and a student-data
   review; it can be added later as a separate ADR without changing the above.

**Consequences.** Book programs need stable titles and optional `keywords`; `step_notes` stay optional. The tutor's checks are
practice only and never affect progress or scores (ADR 0009).

## Amendment (2026-11-06): the student's own code
With on-device Python in place, **Write your own code** runs whatever the student types after a short pause and explains the line
under the cursor (same explainer, same book references), shows plain-language error messages, and opens any version in the stepper,
including programs that ask for input (the stepper asks, then continues where it paused). "I'll type it" gained **Step through my
code as it is**, for stepping a half-finished or different attempt. A debug-only **Try the tutor (test build only)** entry on
Welcome lets the owner try all of this on a phone without a server; release builds do not show it.
