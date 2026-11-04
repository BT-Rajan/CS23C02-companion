# Runtime grants
After every migration, run as a MySQL admin:
```
python -m app.cli print-grants --schema cs23c02 --user "'cs23c02_app'@'%'" | mysql -u <admin> -p
```
This grants the runtime user per-table privileges derived from the models: full DML on every table **except** `audit_log`
(SELECT, INSERT only) and `alembic_version` (none). The runtime user has no DDL rights; migrations run as `cs23c02_migrator`.
(MySQL cannot REVOKE a table privilege granted at database level, which is why grants are per table.)
