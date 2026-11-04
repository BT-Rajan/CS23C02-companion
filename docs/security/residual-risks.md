# Residual-risk register (initial)
| Risk | Why it remains | Mitigation | Owner decision |
|---|---|---|---|
| Content copied after display (screenshots, photos, retyping) | Physically unpreventable | Keep valuable items (keys, hidden tests, solutions) server-side; detect bulk scraping | Accept |
| Credential sharing by one buyer with friends | Email+password is shareable | Device cap, anomaly re-verification, support flags; commercial unattractiveness (progress and history tied to one account) | Accept, monitor |
| Code shared before redemption | First redeemer wins | Scratch panel, batch states, support recovery with proof of purchase | Accept; physical protection choice open |
| Insider leak of codes at print/distribution | Plaintext must reach the printer | `unissued` state, batch revocation, encrypted handoff, destruction record | Process decision needed |
| Rooted-device extraction of offline lessons | Keys live on device | Per-account encryption, key expiry; keys/tests not downloadable | Accept |
| Practice-mode answer enumeration | Immediate feedback is the feature | Per-question calls, rate limits, anomaly alerts; assessments use different items than practice where the bank allows | Accept; monitor |
| Sandbox escape | Defence in depth, not proof | gVisor/nsjail, isolated host, no secrets, patching, independent review before release | Review required |
| Email account compromise | Out of our control | Re-verification on anomalies; sessions revoked on reset | Accept |
