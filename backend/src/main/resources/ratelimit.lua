-- Atomic token bucket. KEYS[1] = bucket key; ARGV[1] = capacity; ARGV[2] = refill tokens per second.
-- Uses Redis server time, so all API instances share one clock. Returns {allowed(1/0), retryAfterMs, tokensLeft}.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local t = redis.call('TIME')
local nowms = t[1] * 1000 + math.floor(t[2] / 1000)
local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
  tokens = capacity
  ts = nowms
end
local delta = math.max(0, nowms - ts)
tokens = math.min(capacity, tokens + delta * rate / 1000)
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / rate * 1000)
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', nowms)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) * 2)
return {allowed, retry, math.floor(tokens)}
