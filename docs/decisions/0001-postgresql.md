# ADR 0001: PostgreSQL as the system of record

## Status
Accepted.

## Context
The domain is relational and transactional: users, workspaces, memberships, projects, jobs and audit events reference
each other, and several operations must be atomic (create a workspace and its owner membership; perform an action and
write its audit record; enqueue a job).

## Decision
Use PostgreSQL with Flyway-versioned migrations, foreign keys and check constraints, optimistic locking (`@Version`) for
concurrent edits, and HikariCP with a configurable pool.

## Alternatives considered
- **MongoDB / DynamoDB:** flexible and horizontally scalable, but we would re-implement referential integrity and
  multi-entity transactions in application code, and tenant-scoped queries with joins (member list with emails) are
  natural in SQL. Scale limits that justify a NoSQL store are not present here.
- **MySQL:** viable; PostgreSQL was chosen for partial indexes (used for the job queue and idempotency keys),
  `FOR UPDATE SKIP LOCKED`, `jsonb` and `ON CONFLICT`.

## Consequences
- Strong consistency and simple correctness arguments (constraints enforce invariants even if application code has a bug).
- Vertical scaling first; read replicas or partitioning would be the next step. Multi-tenancy is a shared schema keyed by workspace id.
- The same database also hosts the job queue (see ADR 0003), so database capacity is shared between API traffic and jobs.
