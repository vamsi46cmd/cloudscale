#!/usr/bin/env bash
# Reproducible benchmark. Usage: scripts/benchmark.sh <label> <vus> <duration> <cache: true|false>
# Restarts api+worker with rate limits raised (benchmarks share one user), runs k6, samples container CPU/memory and
# DB connections, and writes docs/benchmarks/<label>.json. Requires: docker compose stack, k6.
set -euo pipefail
label=${1:?label}; vus=${2:?vus}; duration=${3:-30s}; cache=${4:-true}
cd "$(dirname "$0")/.."
export CACHE_ENABLED=$cache RATELIMIT_API_CAPACITY=1000000 RATELIMIT_API_REFILL=1000000 RATELIMIT_AUTH_CAPACITY=1000000 RATELIMIT_AUTH_REFILL=100000
docker compose up -d --force-recreate api worker >/dev/null 2>&1
for _ in $(seq 1 60); do [ "$(docker compose ps api --format '{{.Health}}')" = healthy ] && break; sleep 3; done
sleep 15   # let workers and JIT settle
out=docs/benchmarks/$label.json; mkdir -p docs/benchmarks
( sleep $(( ${duration%s} / 2 )); docker stats --no-stream --format '{"name":"{{.Name}}","cpu":"{{.CPUPerc}}","mem":"{{.MemUsage}}"}' \
    $(docker compose ps -q api worker postgres redis) > "docs/benchmarks/$label.stats.jsonl" ;
  docker compose exec -T postgres psql -U cloudscale -d cloudscale -Atc "select count(*) from pg_stat_activity where datname='cloudscale'" > "docs/benchmarks/$label.dbconns.txt" ) &
k6 run --quiet -e LOGIN_RATE=${LOGIN_RATE:-0.02} -e VUS=$vus -e DURATION=$duration -e OUT=$out tests/performance/load.js >/dev/null
wait
python3 - "$out" "$label" "$cache" <<'PY'
import json, sys
p, label, cache = sys.argv[1:4]
r = json.load(open(p))
stats = [json.loads(l) for l in open(f"docs/benchmarks/{label}.stats.jsonl")]
r.update({"label": label, "cacheEnabled": cache == "true", "containerSamplesMidRun": stats,
          "dbConnectionsMidRun": int(open(f"docs/benchmarks/{label}.dbconns.txt").read().strip())})
json.dump(r, open(p, "w"), indent=2)
print(json.dumps({k: r[k] for k in ("label", "vus", "requests", "requestsPerSecond", "latencyMs", "errorRate", "dbConnectionsMidRun")}))
PY
rm -f "docs/benchmarks/$label.stats.jsonl" "docs/benchmarks/$label.dbconns.txt"
