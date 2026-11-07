#!/bin/sh
# Nightly logical backup (ADR 0010): a consistent mysqldump of the schema + the content files, kept for BACKUP_KEEP_DAYS.
# Copy deploy/backups off the machine as well (see README): a backup on the same disk is not a backup.
set -eu
KEEP="${BACKUP_KEEP_DAYS:-14}"
while true; do
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  out="/backups/cs23c02-$stamp"
  mkdir -p "$out"
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -h mysql -uroot --single-transaction --routines --triggers --set-gtid-purged=OFF \
    --databases cs23c02 | gzip > "$out/db.sql.gz"
  tar -C /data -czf "$out/content.tar.gz" content
  (cd "$out" && sha256sum db.sql.gz content.tar.gz > SHA256SUMS)
  echo "backup $out done"
  find /backups -maxdepth 1 -name 'cs23c02-*' -mtime +"$KEEP" -exec rm -rf {} +
  [ "${BACKUP_ONCE:-0}" = 1 ] && exit 0
  sleep "${BACKUP_INTERVAL_S:-86400}"
done
