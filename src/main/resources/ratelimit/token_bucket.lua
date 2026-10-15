-- Atomic token bucket. One script call reads, refills, consumes and persists a bucket,
-- so concurrent API instances can never spend the same token twice.
--
-- KEYS[1]  bucket hash: tokens (float) and ts (Redis server time, microseconds)
-- ARGV[1]  capacity: maximum burst size
-- ARGV[2]  refill rate: tokens per second
-- ARGV[3]  requested tokens
-- Returns  {allowed 0|1, retry-after microseconds, remaining whole tokens}
--
-- Redis server time is used instead of the caller's clock so that API instances with
-- drifting clocks share one timeline. Redis 7 replicates script effects, so TIME is allowed.

local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- Never move the bucket timeline backwards, e.g. after a failover to a lagging replica.
if now > ts then
  tokens = math.min(capacity, tokens + (now - ts) * rate / 1000000)
  ts = now
end

local allowed = 0
local retry = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry = math.ceil((requested - tokens) * 1000000 / rate)
end

-- tostring keeps only 14 significant digits; a 16-digit microsecond timestamp needs %.0f.
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', string.format('%.0f', ts))
-- A full refill makes the bucket indistinguishable from a missing one, so it can expire then.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)

return { allowed, retry, math.floor(tokens) }
