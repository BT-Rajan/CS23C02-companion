#!/bin/bash
# Brings the whole stack up on this machine with throwaway secrets (DOMAIN=localhost, Caddy's own CA, console email), imports the
# signed content fixture, runs smoke.py through Caddy, takes a backup and restores it into a scratch database (restore drill),
# then tears everything down (volumes included). Needs Docker with compose. Usage: deploy/local-test.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
WORK=$(mktemp -d)
ENVF="$WORK/env"
export COMPOSE_PROJECT_NAME=cs23c02-localtest
DC=(docker compose --env-file "$ENVF")
cleanup() { [ "${1:-}" = keep ] || "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$WORK" 2>/dev/null || sudo -n rm -rf "$WORK" 2>/dev/null || true; }
trap 'cleanup ${KEEP:-}' EXIT
[ "${1:-}" = --keep ] && KEEP=keep

PY="$ROOT/backend/.venv/bin/python"; [ -x "$PY" ] || PY=python3
KEYS=$(cd "$ROOT/content-tools" && "$PY" -m cs23c02_content.build keygen)
echo "$KEYS" | sed -n 's/^PRIVATE[^:]*: //p' > "$WORK/private.key"
PUB=$(echo "$KEYS" | sed -n 's/^PUBLIC[^:]*: //p')
(cd "$ROOT/content-tools" && "$PY" -m cs23c02_content.build build fixtures/cs23c02-fixture "$WORK/pkg" --key-file "$WORK/private.key" >/dev/null)
rm -f "$WORK/private.key"
r() { openssl rand -hex 32; }
cat > "$ENVF" <<ENV
DOMAIN=localhost
APP_ENV=dev
EMAIL_BACKEND=console
HTTP_PORT=18080
HTTPS_PORT=18443
MYSQL_ROOT_PASSWORD=$(r)
MIGRATOR_PASSWORD=$(r)
APP_DB_PASSWORD=$(r)
JWT_SECRET=$(r)
CODE_PEPPER=$(r)
AUX_PEPPER=$(r)
SANDBOX_KEY=$(r)
CONTENT_SIGNING_PUBKEY=$PUB
BACKUP_INTERVAL_S=86400
BACKUP_DIR=$WORK/backups
ENV

echo "== starting the stack"
"${DC[@]}" up -d --build --wait --quiet-pull
echo "== sandbox container: isolation and no route out"
"${DC[@]}" exec -T sandbox /venv/bin/python -c "import json,urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8090/healthz').read().decode())"
if "${DC[@]}" exec -T sandbox /venv/bin/python -c "import socket;socket.create_connection(('1.1.1.1',443),3)" 2>/dev/null; then
  echo "FAIL the sandbox container can reach the internet"; exit 1; fi
if "${DC[@]}" exec -T sandbox /venv/bin/python -c "import socket;socket.create_connection(('mysql',3306),3)" 2>/dev/null; then
  echo "FAIL the sandbox container can reach the database"; exit 1; fi
echo "PASS sandbox container reaches neither the internet nor the database"

echo "== edition, activation code, content"
API=("${DC[@]}" exec -T api)
"${API[@]}" python -m app.cli create-edition --id stage-ed1 --title "Staging edition" >/dev/null
BATCH=$("${API[@]}" python -m app.cli create-batch --edition stage-ed1 --count 1 --label smoke --out /tmp/codes.csv | sed -E 's/^batch ([0-9]+):.*/\1/')
"${API[@]}" python -m app.cli release-batch --batch "$BATCH" >/dev/null
CODE=$("${API[@]}" sh -c 'tail -1 /tmp/codes.csv | cut -d, -f2; rm /tmp/codes.csv')
API_CID=$("${DC[@]}" ps -q api)
docker cp "$WORK/pkg" "$API_CID:/tmp/pkg" >/dev/null
"${API[@]}" python -m app.cli import-content --edition stage-ed1 --public /tmp/pkg/public --protected /tmp/pkg/protected.json --publish
"${DC[@]}" exec -T -u 0 api rm -rf /tmp/pkg

echo "== smoke test through Caddy"
CADDY_CID=$("${DC[@]}" ps -q caddy)
for _ in $(seq 30); do docker cp "$CADDY_CID:/data/caddy/pki/authorities/local/root.crt" "$WORK/root.crt" 2>/dev/null && break; sleep 1; done
python3 smoke.py --base https://localhost:18443 --cafile "$WORK/root.crt" --email "smoke-$(date +%s)@example.org" \
  --activation-code "$CODE" --otp-cmd "docker compose --env-file $ENVF logs api"

echo "== backup and restore drill"
mkdir -p "$WORK/drill"
"${DC[@]}" run --rm --no-deps -T -e BACKUP_ONCE=1 -v "$WORK/drill:/backups" backup >/dev/null
B=$(ls -d "$WORK"/drill/cs23c02-* | tail -1)
(cd "$B" && sha256sum -c SHA256SUMS >/dev/null) && echo "PASS backup written and checksummed ($B)"
./restore-drill.sh "$ENVF" "$B"
echo "LOCAL TEST PASSED"
