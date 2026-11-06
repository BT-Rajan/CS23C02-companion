#!/bin/bash
# Restore drill (A8/A9): loads a backup into a SCRATCH MySQL container (never the live one) and checks it is complete.
# Usage: deploy/restore-drill.sh <env file> <backup dir>
set -euo pipefail
ENVF=$1; B=$2
(cd "$B" && sha256sum -c SHA256SUMS >/dev/null) || { echo "FAIL checksum mismatch in $B"; exit 1; }
PW=$(openssl rand -hex 16)
NAME=cs23c02-restore-drill-$$
docker run -d --rm --name "$NAME" -e MYSQL_ROOT_PASSWORD="$PW" --network none mysql:8.0.46 >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1' EXIT
# The image runs a temporary server while it initialises; the real one logs "ready for connections" on port 3306.
for _ in $(seq 120); do docker logs "$NAME" 2>&1 | grep -q "ready for connections.*port: 3306" && break; sleep 1; done
gunzip -c "$B/db.sql.gz" | docker exec -i -e MYSQL_PWD="$PW" "$NAME" mysql -uroot
q() { docker exec -e MYSQL_PWD="$PW" "$NAME" mysql -uroot -N -e "$1" cs23c02 2>/dev/null; }
LIVE() { docker compose --env-file "$ENVF" exec -T mysql sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot -N -e \"$1\" cs23c02" 2>/dev/null; }
for t in users activation_codes content_releases content_files exercise_attempts assessment_attempts audit_log; do
  a=$(q "SELECT COUNT(*) FROM $t"); b=$(LIVE "SELECT COUNT(*) FROM $t")
  [ "$a" = "$b" ] || { echo "FAIL $t: restored $a rows, live $b"; exit 1; }
done
[ "$(q 'SELECT version_num FROM alembic_version')" = "$(LIVE 'SELECT version_num FROM alembic_version')" ] || { echo "FAIL schema version differs"; exit 1; }
tar -tzf "$B/content.tar.gz" | grep -q '^content/files/' || { echo "FAIL content archive has no files"; exit 1; }
echo "PASS restore drill: schema version and row counts match the live database; content archive complete"
