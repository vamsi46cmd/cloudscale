# ADR 0002: Redis for caching and distributed rate limiting

## Status
Accepted.

## Context
API instances are stateless and scaled horizontally. Two needs cross instances: a read cache, and rate limits that hold
no matter which instance a request reaches.

## Decision
- **Rate limiting:** a token bucket executed atomically in Redis (Lua script, Redis server time). Per-instance in-memory
  counters were rejected: with N instances the effective limit becomes N times larger and depends on load-balancer
  behavior. This is proven by an integration test that starts two instances and sends alternating requests.
- **Cache:** tenant-scoped keys (`cs:v1:ws:{workspaceId}:...`), 60 s TTL, eviction after the writing transaction commits.
  Only non-sensitive read models are cached; **authorization decisions are never cached**, so removing a member takes
  effect immediately.

## Failure behavior
Redis is an optimization, not a dependency of correctness. On any Redis error: reads fall back to PostgreSQL, and a
5-second circuit breaker avoids paying a timeout on every request. API rate limiting **fails open** (an outage should not
take the API down); auth endpoints fall back to a per-instance window so brute-force attempts remain throttled. After a
failed eviction, stale data can live until the TTL expires (at most 60 s).

## Consequences
- Observed on a 10-user test without logins: about 16% higher throughput and 15% lower median latency with the cache
  (see README), a modest gain because the reads are cheap primary-key lookups.
- A second stateful dependency to run and monitor; mitigated by it being optional for correctness.
- Clients behind a shared NAT share an auth-endpoint bucket (per-IP), which is the intended trade-off for credential-stuffing protection.
