#!/usr/bin/env bash
# Runs the app's real API client (LiveBackendContractTest) against a real backend on this machine.
# Needs: MySQL 8 running with root access over the local socket, backend/.venv with requirements installed,
# the Android SDK (ANDROID_HOME or android/local.properties) and Gradle. Uses a throwaway database `cs23c02_it`.
# Dev-only: plain HTTP on 127.0.0.1 is used here because the backend is local; the app itself only accepts https.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
PORT=${PORT:-8765}
DB=cs23c02_it
MYSQL=${MYSQL:-"mysql -uroot"}
GRADLE=${GRADLE:-gradle}

$MYSQL <<SQL
DROP DATABASE IF EXISTS $DB;
CREATE DATABASE $DB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'it_mig'@'localhost' IDENTIFIED BY 'it_mig_pw';
GRANT ALL ON $DB.* TO 'it_mig'@'localhost';
CREATE USER IF NOT EXISTS 'it_rt'@'localhost' IDENTIFIED BY 'it_rt_pw';
SQL

export ENV=dev EMAIL_BACKEND=console TRUST_PROXY_HEADERS=false CONTENT_DIR="$WORK/content"
export JWT_SECRET=$(openssl rand -hex 32) CODE_PEPPER=$(openssl rand -hex 32) AUX_PEPPER=$(openssl rand -hex 32)
export DATABASE_URL="mysql+pymysql://it_rt:it_rt_pw@127.0.0.1/$DB?charset=utf8mb4"
export MIGRATION_DATABASE_URL="mysql+pymysql://it_mig:it_mig_pw@127.0.0.1/$DB?charset=utf8mb4"

cd "$ROOT/backend"
PY=.venv/bin/python
$PY -m alembic upgrade head >/dev/null
$PY -m app.cli print-grants --schema $DB --user "'it_rt'@'localhost'" | $MYSQL
$PY -m app.cli create-edition --id it-edition --title "Contract test edition" >/dev/null
BATCH=$($PY -m app.cli create-batch --edition it-edition --count 1 --label it --out "$WORK/codes.csv" | sed -E 's/^batch ([0-9]+):.*/\1/')
$PY -m app.cli release-batch --batch "$BATCH" >/dev/null
CODE=$(tail -n 1 "$WORK/codes.csv" | cut -d, -f2)

# A signed content package (the clearly-marked backend fixture) imported and published for that edition (A2).
KEYS=$(cd "$ROOT/content-tools" && "$ROOT/backend/$PY" -m cs23c02_content.build keygen)
echo "$KEYS" | sed -n 's/^PRIVATE[^:]*: //p' > "$WORK/private.key"
export CONTENT_SIGNING_PUBKEY=$(echo "$KEYS" | sed -n 's/^PUBLIC[^:]*: //p')
(cd "$ROOT/content-tools" && "$ROOT/backend/$PY" -m cs23c02_content.build build fixtures/cs23c02-fixture "$WORK/pkg" --key-file "$WORK/private.key" --check-solutions >/dev/null)
rm -f "$WORK/private.key"
$PY -m app.cli import-content --edition it-edition --public "$WORK/pkg/public" --protected "$WORK/pkg/protected.json" --publish >/dev/null

# The sandbox as its own service (ADR 0006): the backend reaches it over HTTP with signed requests and verifies its signed answers.
export SANDBOX_KEY=$(openssl rand -hex 32) SANDBOX_MODE=http SANDBOX_URL="http://127.0.0.1:$((PORT + 1))"
(cd "$ROOT/sandbox" && "$ROOT/backend/$PY" -m uvicorn sandbox.service:app --host 127.0.0.1 --port $((PORT + 1)) > "$WORK/sandbox.log" 2>&1) &
SANDBOX=$!

PYTHONUNBUFFERED=1 $PY -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" > "$WORK/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER $SANDBOX 2>/dev/null; pkill -f "sandbox.service:app" 2>/dev/null; rm -rf "$WORK"' EXIT
for _ in $(seq 50); do curl -fsS "$SANDBOX_URL/healthz" >/dev/null 2>&1 && break; sleep 0.2; done
for _ in $(seq 50); do curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break; sleep 0.2; done

cd "$ROOT/android"
API_IT_URL="http://127.0.0.1:$PORT" API_IT_MAILLOG="$WORK/server.log" API_IT_CODE="$CODE" API_IT_CONTENT_PUBKEY="$CONTENT_SIGNING_PUBKEY" \
  $GRADLE --no-daemon --console=plain :app:testDebugUnitTest --tests 'app.cs23c02.companion.api.LiveBackendContractTest' --rerun-tasks
echo "contract test passed"
