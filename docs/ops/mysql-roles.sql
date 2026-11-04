-- Run once per environment as a MySQL admin. Replace passwords via your secret manager; do not commit real ones.
CREATE USER IF NOT EXISTS 'cs23c02_migrator'@'%' IDENTIFIED BY '<<set-from-secret-manager>>';
CREATE USER IF NOT EXISTS 'cs23c02_app'@'%'      IDENTIFIED BY '<<set-from-secret-manager>>';
GRANT ALL ON cs23c02.* TO 'cs23c02_migrator'@'%';
-- Runtime role: no DDL. Table grants come from `python -m app.cli print-grants` (see grant_runtime.md).
