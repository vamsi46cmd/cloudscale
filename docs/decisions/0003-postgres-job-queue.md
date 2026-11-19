# ADR 0003: Durable PostgreSQL job queue instead of a message broker

## Status
Accepted.

## Context
We need durable background processing with retries, cancellation and idempotent submission, for workloads measured in
jobs per second to thousands per minute, not millions per second.

## Decision
Store jobs in PostgreSQL. Workers claim rows with `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)`, which
lets many workers run concurrently without claiming the same row. Leases detect dead workers; `attempt_count` acts as a
fencing token so a stale worker cannot overwrite the current owner's result. Partial indexes keep the claim query off
finished jobs.

## Why not Kafka / RabbitMQ / SQS
- **Transactional enqueue:** the job row is created in the same transaction as the business change and audit record,
  with no dual-write problem (the classic "DB committed but message not sent").
- **Idempotency and cancellation** are simple SQL (unique partial index; conditional UPDATE).
- **One less system** to deploy, secure, monitor and pay for.

## Trade-offs
- Workers poll (500 ms by default) rather than receive pushes, and queue traffic shares database capacity with the API.
  At much higher job rates, a broker is the right move.
- **Delivery is at-least-once.** A worker crash or lease expiry can cause a re-run; handlers must be safe to repeat.
  Exactly-once is not claimed. Verified by killing workers mid-job: the job was reclaimed and completed on attempt 2.
- No built-in fan-out or replay of past jobs; that would need a broker or an outbox + log.
