#!/bin/sh
# Creates deploy/.env with fresh random secrets (never committed: .env is in .gitignore). Run once per environment.
# Usage: ./init-secrets.sh <domain> [content-signing-public-key]
set -eu
cd "$(dirname "$0")"
[ -e .env ] && { echo ".env already exists; refusing to overwrite secrets" >&2; exit 1; }
DOMAIN="${1:?usage: ./init-secrets.sh <domain> [content signing public key]}"
r() { openssl rand -hex 32; }
umask 077
cat > .env <<ENV
DOMAIN=$DOMAIN
APP_ENV=prod
MYSQL_ROOT_PASSWORD=$(r)
MIGRATOR_PASSWORD=$(r)
APP_DB_PASSWORD=$(r)
JWT_SECRET=$(r)
CODE_PEPPER=$(r)
AUX_PEPPER=$(r)
SANDBOX_KEY=$(r)
CONTENT_SIGNING_PUBKEY=${2:-}
# Gmail (docs/ops/gmail-smtp.md): an App Password, never the account password.
EMAIL_BACKEND=smtp
SMTP_USER=
SMTP_PASSWORD=
SMTP_FROM=
BACKUP_KEEP_DAYS=14
# Landing page: leave PLAY_URL empty until the app is on Google Play.
PLAY_URL=
SUPPORT_EMAIL=
ENV
echo "wrote deploy/.env (mode 600). Fill in SMTP_* and CONTENT_SIGNING_PUBKEY, then: docker compose up -d --build"
