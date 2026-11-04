# Open questions and proposed defaults
Resolved by the owner: Android builds on GitHub Actions (ADR 0012); MySQL 8 (not PostgreSQL); Gmail SMTP configured via .env; content layout and proposed defaults #2–#5 approved; web = landing page only; email + password with emailed recovery code; minSdk 26; execution planned for performance and security (hybrid); textbook repo at `BT-Rajan/CS23C02`.

| # | Question | Proposed default (needs approval) |
|---|---|---|
| 1 | Textbook repo now has an approved architecture. Approve the requests in `content-interface.md` §3 (public/hidden tests, trace data, option explanations, edition fields, non-MCQ items) for the textbook session to consider | Approve §3 as proposals; I will not edit the textbook repo |
| 2 | Assessment policy | 1 attempt per unit assessment per 24 h after the first; no time limit but a 3-hour session expiry; results and explanations shown after submit; review allowed afterwards |
| 3 | Hints/solutions policy | Hint 1 after 1 failed check, hint 2 after 3, solution after 5 failed checks or on completion |
| 4 | Support/recovery: lost code, damaged scratch panel, lost email access | Support form + photo of book/receipt; manual replacement code from a reserved `issued` pool; every action audited |
| 5 | Printing logistics: scratch-off panel? who handles files? | Scratch-off; codes handed to the printer as an encrypted file; batches stay `unissued` until shipped |
| 6 | Hosting region, budget, data-protection obligations | EU/India-region managed Postgres; separate small VM pool for the sandbox. Need your regions and budget |
| 7 | Allowed Python modules | Initial list in ADR 0006; extend per lesson via content |
| 8 | Domain, email sending domain (SPF/DKIM/DMARC), Play developer account | Needed before Phase 2 staging |
| 9 | Token hygiene | Replace the classic token with a fine-grained one scoped to the companion (and read-only textbook) repo; revoke the current one |
| 10 | Phase 3 slice order (backend content delivery → Android shell → home/navigation → lesson reader → progress) | Awaiting explicit approval |
| 11 | Defer search and bookmarks until after Phase 3 | Awaiting explicit approval |
| 12 | Reference low-end Android phone (model) for performance targets | Awaiting owner |
| 13 | Anomaly re-verification for credential sharing: before or after the Android shell | Awaiting owner (default: after) |
| 14 | Gmail credentials in `backend/.env` for real delivery testing | Awaiting owner |
