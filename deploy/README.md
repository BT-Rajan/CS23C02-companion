# Staging and production runbook (A8)

One Linux VM runs the whole service with Docker Compose. Nothing here is tied to a hosting provider.

```
internet ──443──► caddy ──edge──► api ──data (internal)──► mysql ◄── backup (nightly)
                   │               └──sandbox (internal)──► sandbox  (bubblewrap per run, per-job UID, no route out)
                   └─ landing page (web/), static, no scripts
```

| Service | What it is | Reachable from |
|---|---|---|
| `caddy` | TLS (Let's Encrypt, automatic), HSTS, landing page, proxies only `/v1/*` and `/healthz` | the internet (80, 443) |
| `api` | FastAPI, 2 workers, `ENV=prod`, `TRUST_PROXY_HEADERS=true` | caddy only |
| `migrate` | one-shot: creates the DB users, `alembic upgrade head` as the migrator, per-table grants for the runtime user | runs at every `up`, then exits |
| `mysql` | MySQL 8.0, binary log on (7 days), data in volume `mysql` | api, migrate, backup (internal network, no ports published) |
| `sandbox` | graded runs (ADR 0006); starts as root only to give each job its own UID | api only (internal network, no internet, no database) |
| `backup` | `mysqldump --single-transaction` + content files every 24 h into `deploy/backups/`, kept 14 days | — |

## 1. Machine
- Ubuntu 24.04 LTS, 2 vCPU, 4 GB RAM, 40 GB disk is enough for a pilot (sandbox: up to 4 parallel runs × 256 MB).
- A region close to the students (India: Mumbai/Hyderabad/Chennai) keeps latency low.
- Firewall: allow 22 (your IP only), 80, 443. Nothing else.
- Install Docker Engine + compose plugin, then allow bubblewrap's user namespaces (Ubuntu 24.04 restricts them):
  ```bash
  echo 'kernel.apparmor_restrict_unprivileged_userns=0' | sudo tee /etc/sysctl.d/60-cs23c02-userns.conf && sudo sysctl --system
  ```
  The `sandbox` container runs with `seccomp=unconfined`, `apparmor=unconfined` and `systempaths=unconfined` so bubblewrap
  can build its own namespaces. That is why it sits on a network with no route anywhere except the API, has a read-only root
  filesystem, a PID cap and a memory cap. ADR 0006 prefers a **separate VM** for the sandbox in production; to do that, run
  only `sandbox` there, set `SANDBOX_URL` to its private address, and allow port 8090 from the API host only.

## 2. DNS and email
- `A`/`AAAA` record for the domain (e.g. `app.example.org`) → the VM. Caddy obtains the certificate on first start.
- Gmail: follow `docs/ops/gmail-smtp.md` (dedicated account, 2-Step Verification, App Password). Using a Gmail address,
  SPF/DKIM are Google's; if you send **from your own domain**, add Google's SPF include and DKIM key to that domain first, or
  the codes land in spam (K6). Consumer Gmail sends at most ~500 messages/day: enough for staging, not for a whole cohort
  registering on one day (ADR 0011).

## 3. First start
```bash
git clone https://github.com/BT-Rajan/CS23C02-companion.git && cd CS23C02-companion/deploy
./init-secrets.sh app.example.org "<CONTENT_SIGNING_PUBKEY>"   # writes deploy/.env, mode 600, random secrets
nano .env                                                   # SMTP_USER, SMTP_PASSWORD, SMTP_FROM, SUPPORT_EMAIL
docker compose up -d --build
docker compose ps                                           # all healthy; migrate "exited (0)"
docker compose exec api python -m app.cli send-test-email you@example.org
```
`.env` holds every server secret. It is never committed; keep an offline copy (password manager). Losing `CODE_PEPPER`
makes every printed activation code unusable.

## 4. Content and activation codes
On your own computer (the private signing key never goes to the server, K14):
```bash
cd content-tools && python -m cs23c02_content.build build <book package dir> /tmp/pkg --key-file <private.key> --check-solutions
scp -r /tmp/pkg server:/tmp/pkg
```
On the server:
```bash
docker compose cp /tmp/pkg api:/tmp/pkg
docker compose exec api python -m app.cli create-edition --id ed1 --title "CS23C02 First Edition"
docker compose exec api python -m app.cli import-content --edition ed1 --public /tmp/pkg/public --protected /tmp/pkg/protected.json --publish
docker compose exec api python -m app.cli create-batch --edition ed1 --count 50 --label staging --out /tmp/codes.csv
docker compose exec api python -m app.cli release-batch --batch <n>
docker compose cp api:/tmp/codes.csv . && docker compose exec api rm /tmp/codes.csv   # protect, print, delete
```

## 5. Smoke test (every deploy)
```bash
python3 deploy/smoke.py --base https://app.example.org --email you+smoke1@example.org --activation-code XXXX-XXXX-XXXX-XXXX
```
It asks for the code from the real verification email, then checks HTTPS, landing page headers, sign-up → activation →
signed content → a graded run in the sandbox (and that student code has no network) → an assessment → progress. The
content must contain the fixture IDs it uses (EXR-0001, ASM-0002) — import `content-tools/fixtures/cs23c02-fixture` into a
staging-only edition for this. It creates one account and uses one code.

`deploy/local-test.sh` does all of this on a laptop or CI with throwaway secrets (`DOMAIN=localhost`, console email), plus a
backup and a restore drill, then removes everything. CI runs it on every change (`.github/workflows/deploy.yml`).

## 6. The Android build for this server
Set these as GitHub repository variables (or in `android/.env`); see `docs/ops/android-config.md`:
| Variable | Value |
|---|---|
| `API_BASE_URL` | `https://app.example.org` |
| `API_CERT_PINS` | `sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=,sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=` |
| `CONTENT_SIGNING_PUBKEY` | the public key from `keygen` |

The pins are Let's Encrypt's two roots (ISRG Root X1 and X2), not the server's own certificate, so Caddy's automatic
renewals every ~60 days never break the app. Check them before release:
```bash
openssl x509 -in /etc/ssl/certs/ISRG_Root_X1.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```
Changing certificate authority means shipping an app update with the new pin **first**.

## 7. Operations
| Task | Command |
|---|---|
| Update | `git pull && docker compose up -d --build` (migrations run first; the API waits for them) |
| Logs | `docker compose logs -f api` (JSON access logs: `docker compose logs caddy`) |
| Backups | `deploy/backups/cs23c02-<UTC time>/{db.sql.gz,content.tar.gz,SHA256SUMS}`; copy them **off the machine** daily (e.g. `rsync` to another provider or an encrypted bucket) — a backup on the same disk is not a backup |
| Restore drill (monthly) | `deploy/restore-drill.sh .env deploy/backups/<dir>` loads the backup into a scratch MySQL with no network and compares schema version and row counts with live |
| Real restore | `docker compose stop api backup`; `gunzip -c db.sql.gz \| docker compose exec -T mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot'`; restore `content.tar.gz` into the `content` volume; `docker compose up -d` |
| Point-in-time | the binary log (7 days) covers the gap since the last dump |
| Revoke a printed batch | `docker compose exec api python -m app.cli revoke-batch --batch <n>` |
| Purge old rate-limit rows | `docker compose exec api python -m app.cli purge-rate-events` (weekly; cron) |

## 8. Not done yet (see docs/known-issues.md)
Off-site backup copy is the owner's choice of destination; monitoring/alerting (uptime check on `/healthz`); sandbox on its
own VM; seccomp filter inside bubblewrap (K32); key rotation procedure (K14).
