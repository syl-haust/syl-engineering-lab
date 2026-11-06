-- ============================================================
-- 令牌桶限流（Redis Lua 实现）
--
-- 为什么必须用 Lua：
--   判定流程是「读桶 → 按时间差补令牌 → 判断够不够 → 扣减 → 写回」，
--   如果拆成多条命令，并发请求之间会插队，导致超发。
--   Redis 执行 Lua 期间是单线程的，整个脚本原子完成。
--
-- 参数：
--   KEYS[1] = 桶的 key
--   ARGV[1] = capacity        桶容量（允许的最大突发）
--   ARGV[2] = refillPerSecond 每秒补充的令牌数（稳态速率）
--   ARGV[3] = nowMillis       当前时间戳（毫秒，由调用方传入，避免多实例时钟差异）
--
-- 存储结构：Redis Hash { tokens = 当前令牌数, ts = 上次补充时间 }
-- 返回值：1 = 放行（已扣 1 个令牌），0 = 拒绝
-- ============================================================

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])

-- 每秒最多补满一次桶，防止 TTL 到期时令牌全丢导致"桶突然变空"
local ttl = math.ceil(capacity / rate) + 1

local bucket = redis.call('HMGET', key, 'tokens', 'ts')

-- 补令牌：elapsed 秒 * rate。首次访问时桶是满的。
local tokens = tonumber(bucket[1]) or capacity
local last   = tonumber(bucket[2]) or now
local elapsed = math.max(0, now - last) / 1000.0
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    allowed = 0
end

redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', key, ttl)

return allowed