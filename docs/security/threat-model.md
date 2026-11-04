# Threat model (initial)

## Assets
1. Paid content (assessment keys, explanations, hidden tests, solutions are the crown jewels; lessons are lower value).
2. Commercial integrity (one book → one account).
3. Assessment integrity and progress records.
4. Student accounts (email, progress).
5. Backend and execution infrastructure availability.
6. Secrets: HMAC pepper, signing keys, DB credentials, email provider keys.

## Adversaries
| Actor | Capability | Motivation |
|---|---|---|
| Casual sharer | Shares credentials/codes with friends | Avoid paying |
| Account farmer | Multiple emails, scripts | Free access, resell codes |
| Cheater | Inspects traffic/app, edits local data | Better scores |
| Scraper | Authenticated bulk download | Redistribute content |
| Sandbox attacker | Malicious Python | Escape, DoS, mine, attack others |
| Network attacker | MITM on public Wi-Fi | Steal tokens |
| Insider (print/distribution) | Sees codes before sale | Sell codes |
| External API attacker | Standard web attacks | Data/ DoS |

## Trust boundaries
Device (untrusted) | Network | API (trusted) | Database | Execution service (isolated, assumed hostile inputs) | Email provider | Textbook CI.
**Principle:** the client is never an authority for entitlement, correctness, score or progress that matters.

## Out of scope (stated, not ignored)
Preventing screenshots/photos of displayed content; stopping a determined user from sharing their own credentials; rooted-device extraction of downloaded lessons (raised cost only). See `residual-risks.md`.

## Standards used
OWASP MASVS/MASTG (Android), OWASP ASVS and API Security Top 10 (backend), OWASP Cheat Sheets for auth/session. No certification is claimed.
