# 0003 Activation codes
**Context.** One physical book → one student → one account → one activation. Codes are printed, so they exist before sale.
**Decision.**
- Format: 16 Crockford-base32 characters grouped `XXXX-XXXX-XXXX-XXXX`; 15 random characters (75 bits, CSPRNG) + 1 check character so typos are caught on-device without a server call. Ambiguous characters (I, L, O, U) excluded; input is case-insensitive and tolerant of spaces/hyphens.
- Storage: only `HMAC-SHA256(pepper, normalised_code)`; pepper held outside the database (secret manager). Plaintext exists only in the generation job's output file for the printer, encrypted, deleted after print confirmation.
- States: `unissued` (generated, not yet released from print/distribution) → `issued` → `redeemed` | `revoked`. Only `issued` codes redeem. Batches are tracked per print run, so a leak at the printer or distributor can be contained by revoking a batch.
- Redemption (single transaction): `UPDATE activation_codes SET state='redeemed', redeemed_by=:u, redeemed_at=NOW(6) WHERE code_hmac=:h AND state='issued';` and require `rowcount == 1` (MySQL has no RETURNING; the edition is then read in the same transaction;` then `INSERT entitlements`. Unique constraints on `entitlements(user_id, edition_id)` and `entitlements(activation_code_id)` are the backstop. Zero rows updated → generic failure.
- Preconditions: signed-in account with a **verified email**. Rate limits: 5 failed attempts/hour/account, 20/day/IP, global anomaly alert; every attempt audited (hashed code reference, account, IP, outcome).
- Responses never distinguish "wrong", "already used" or "revoked" to the client beyond one generic message plus a support reference; support sees the reason in audit.
- Android may read the code via camera (ML text scan) as a convenience; typing remains supported.
**Consequences.** Replay and double-submit are harmless; simultaneous redemption yields exactly one winner. A code shared *before* redemption goes to whoever redeems first (inherent; deterrent is commercial plus support recovery with proof of purchase). Resale of a used book transfers nothing.
