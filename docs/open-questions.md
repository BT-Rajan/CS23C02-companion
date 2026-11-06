# Open questions and decisions
**2026-11-06: the owner accepted every recommendation.** ADRs 0001–0009 are approved; questions #1–#5 and #7 are approved as
proposed below; textbook decisions P1–P9 are approved (see the book repo's decision register, which also records X1 typesetting,
X2 line-by-line stepping and X3 acceptance of this repo's content requests). Earlier resolutions: Android builds on GitHub
Actions (ADR 0012); MySQL 8; Gmail SMTP via .env; web = landing page only; email + password with emailed recovery code; minSdk 26;
hybrid execution; textbook repo at `BT-Rajan/CS23C02`.

| # | Question | Decision / status |
|---|---|---|
| 1 | Content requests to the textbook (public/hidden tests, trace data, option explanations, edition fields, non-MCQ items) | **Approved** and recorded in the book repo as X3 |
| 2 | Assessment policy | **Approved:** 1 attempt per unit assessment per 24 h after the first; no time limit but a 3-hour session expiry; results and explanations after submit; review allowed afterwards |
| 3 | Hints/solutions policy | **Approved:** hint 1 after 1 failed check, hint 2 after 3, solution after 5 failed checks or on completion |
| 4 | Support/recovery | **Approved:** support form + photo of book/receipt; manual replacement code from a reserved `issued` pool; every action audited |
| 5 | Printing logistics | **Approved:** scratch-off; codes handed to the printer as an encrypted file; batches stay `unissued` until shipped |
| 6 | Hosting region, budget, data protection | **Approved in principle:** managed MySQL 8 (ADR 0010) in an India region; separate small VM pool for the sandbox. **Still needed: budget and provider** |
| 7 | Allowed Python modules | **Approved:** initial list in ADR 0006; extend per lesson via content |
| 8 | Domain, email sending domain (SPF/DKIM/DMARC), Play developer account | **Open — owner to provide** (needed before staging, roadmap A8) |
| 9 | Token hygiene | **Open — owner action:** replace the classic token with a fine-grained one; revoke the old one |
| 10 | Phase 3 slice order | Approved |
| 11 | Defer search and bookmarks | Confirmed |
| 12 | Reference low-end phone | Redmi A7, 3 GB, Android 14 (confirm exact model) |
| 13 | Anomaly re-verification | **Resolved** 2026-11-06: built per ADR 0004 (see its amendment); thresholds to tune on staging (K43) |
| 14 | Gmail credentials | Left untested for now |
| 15 | Line-by-line stepping of real code | **Approved (owner request 2026-11-06):** ADR 0013 |
