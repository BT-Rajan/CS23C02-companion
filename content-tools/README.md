# content-tools
Builds the **App Content Package v0** (see `docs/content-interface.md`) from a source tree and signs it.
```
python -m cs23c02_content.build keygen                       # once; keep the private key out of Git
python -m cs23c02_content.build build SRC OUT --key-file private.key
python -m pytest content-tools                               # from the repo root with the backend venv active
```
`OUT/public/` is what gets imported and served; `OUT/protected.json` holds answers, explanations, hidden tests, hints and
solutions and goes **only** into the server database. The build fails on Release-validation errors and on any leak.
`fixtures/` is **test data that follows the textbook's ID structures; it is not book content.** Regenerate with `fixtures/make_fixture.py`.
Not yet checked here: running each `reference_solution` against its tests in the sandbox (arrives with Phase 5).
`samples/reader-sample/` is SAMPLE content for trying the app's reader (debug builds only); build it into the app with
`backend/.venv/bin/python android/scripts/make-sample-content.py` (throwaway signing key, code blocks checked with the recorder).
