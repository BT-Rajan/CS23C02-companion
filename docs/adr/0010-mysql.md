# 0010 Database: MySQL 8 (supersedes the PostgreSQL part of ADR 0002)
**Context.** The owner works with MySQL. The design needs only transactional atomic updates and unique constraints.
**Decision.** MySQL **8.0+** (target 8.4 LTS), InnoDB, `utf8mb4` / `utf8mb4_0900_ai_ci`, SQLAlchemy 2 + PyMySQL, Alembic migrations. MariaDB is not a supported target (JSON and collation differences).
**Adaptations from the Postgres-flavoured design.**
- No `RETURNING`: redemption runs `UPDATE … WHERE code_hmac=:h AND state='issued'` and checks `rowcount == 1`, then reads the edition inside the same transaction. The row lock taken by the UPDATE makes concurrent redemptions serialise; exactly one sees `rowcount == 1`.
- No partial indexes: uniqueness uses plain unique indexes (MySQL permits multiple NULLs in a unique index, which is what `entitlements.activation_code_id` and nullable tokens need).
- `jsonb` → `JSON`; `citext` → emails stored lower-cased and normalised, unique, ai_ci collation.
- IDs: `CHAR(36)` UUID strings for users/sessions (simple, safe); autoincrement `BIGINT` for log-like tables.
- Append-only audit: the runtime MySQL user gets `SELECT, INSERT` only on `audit_log`; migrations run as a separate user with DDL rights (`docs/ops/mysql-roles.sql`).
- Backups/PITR: binary logs + scheduled logical dumps; restore drill in Phase 8.
- Tests run against a real MySQL (never SQLite) because the acceptance criteria depend on InnoDB locking semantics.
