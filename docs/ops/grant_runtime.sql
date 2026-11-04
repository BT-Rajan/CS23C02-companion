-- Run after each migration as cs23c02_migrator/admin.
GRANT SELECT, INSERT, UPDATE, DELETE ON cs23c02.* TO 'cs23c02_app'@'%';
REVOKE UPDATE, DELETE ON cs23c02.audit_log FROM 'cs23c02_app'@'%';
REVOKE ALL ON cs23c02.alembic_version FROM 'cs23c02_app'@'%';
