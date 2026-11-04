-- Run once per environment as a MySQL admin. Replace passwords via your secret manager; do not commit real ones.
CREATE USER IF NOT EXISTS 'cs23c02_migrator'@'%' IDENTIFIED BY '<<set-from-secret-manager>>';
CREATE USER IF NOT EXISTS 'cs23c02_app'@'%'      IDENTIFIED BY '<<set-from-secret-manager>>';
GRANT ALL ON cs23c02.* TO 'cs23c02_migrator'@'%';
-- Runtime role: no DDL. Tables are granted after migrations (see grant_runtime.sql).
