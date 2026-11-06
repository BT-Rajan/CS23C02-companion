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

PYTHONUNBUFFERED=1 $PY -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" > "$WORK/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$WORK"' EXIT
for _ in $(seq 50); do curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break; sleep 0.2; done

cd "$ROOT/android"
API_IT_URL="http://127.0.0.1:$PORT" API_IT_MAILLOG="$WORK/server.log" API_IT_CODE="$CODE" \
  $GRADLE --no-daemon --console=plain :app:testDebugUnitTest --tests 'app.cs23c02.companion.api.LiveBackendContractTest' --rerun-tasks
echo "contract test passed"
