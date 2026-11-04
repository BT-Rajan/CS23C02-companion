# 0002 Backend: modular FastAPI monolith + PostgreSQL
**Context.** Zigora's backend is one 9.5k-line file on MariaDB. The product has a small API (auth, activation, content, progress, assessment, exec).
**Decision.** Python 3.12, FastAPI, SQLAlchemy 2 + Alembic migrations, PostgreSQL. Modules: `auth`, `activation`, `entitlements`, `content`, `progress`, `assessment`, `exec_client`, `audit`. One deployable; the execution sandbox is a *separate* service.
**Why Postgres.** Row-level atomicity primitives we rely on (`UPDATE … RETURNING`, partial unique indexes, `SELECT … FOR UPDATE SKIP LOCKED`), better constraints than MariaDB defaults. MariaDB remains acceptable if the owner prefers; the design only needs transactional atomic updates and unique indexes.
**Consequences.** Not a rewrite of Zigora; selected patterns are re-implemented with tests. No microservices beyond the sandbox.
