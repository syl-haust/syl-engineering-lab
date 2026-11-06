# 课题④：Gateway 令牌桶限流 + 熔断降级

> 对应简历那句：「依托 Nacos 配置中心实现 Gateway 限流、熔断降级，对下单、商品接口做流量管控」

## 1. 问题从哪来

多数项目的网关只做路由转发，**没有任何流量保护**。实际后果：

| 场景 | 后果 |
|---|---|
| 某个用户脚本刷下单接口 | 单点打满下游，正常用户全部超时 |
| 大促流量突增 | 数据库连接池耗尽，全站雪崩 |
| 下游某服务响应变慢 | 网关线程被拖住，**一个慢服务拖垮整个网关** |
| 下游某服务完全挂掉 | 请求持续打过去，重试风暴把对方彻底压死 |

限流和熔断是两个不同的问题，很多人混着说：

- **限流**：控制**进来**的流量。保护自己。
- **熔断**：切断**出去**的调用。保护下游，也保护自己不被慢调用拖住。

---

## 2. 限流：为什么选令牌桶

### 三种常见算法对比

| 算法 | 原理 | 缺陷 |
|---|---|---|
| **固定窗口** | 每秒计数清零 | **边界双倍突发**：59 秒打满、61 秒又打满，2 秒处理了 2 倍 |
| **滑动窗口日志** | 记录每次请求时间戳，数最近 1 秒内有多少 | 精确但**内存占用大**，热点接口每秒记录上千条 |
| **滑动窗口计数** | 把 1 秒切成 N 个小格 | 折中方案，实现复杂 |
| **令牌桶** | 恒定速率补令牌，容量限制突发 | **本项目采用**：O(1) 内存，天然支持突发 |

令牌桶的两个参数：

```
capacity = 5        桶容量，允许的最大突发
refillPerSecond = 2 稳态速率

用户攒满 5 个令牌后，可以瞬间打出 5 个请求；
之后只能以每秒 2 个的速率继续。
```

### 为什么必须用 Lua

判定流程有四步：

```
读桶 → 按时间差补令牌 → 判断够不够 → 扣减写回
```

如果拆成多条 Redis 命令，并发请求会在中间插队：

```
线程 A: GET tokens → 1        （读到 1 个）
线程 B: GET tokens → 1        （也读到 1 个）
线程 A: 够，扣 1 → SET 0
线程 B: 够，扣 1 → SET -1     ← 超发！
```

放进 Lua 脚本，Redis 单线程执行，天然原子。

完整脚本见 `gateway/src/main/resources/lua/token_bucket.lua`，核心逻辑：

```lua
local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1]) or capacity
local last   = tonumber(bucket[2]) or now

-- 按经过的秒数补充令牌，但不超过容量
local elapsed = math.max(0, now - last) / 1000.0
tokens = math.min(capacity, tokens + elapsed * rate)

if tokens >= 1 then
    tokens = tokens - 1
    return 1        -- 放行
end
return 0            -- 拒绝
```

### 限流维度怎么选

只按维度选一个都不够：

| 维度 | 能防什么 | 防不住什么 |
|---|---|---|
| 只按用户 | 单用户刷接口 | 攻击者换小号；未登录场景没有 userId |
| 只按 IP | 单机刷 | 同一个出口 IP 的整个公司/学校会被一起限 |
| 只按接口 | 保护热点接口 | 一个用户就能打满别人的配额 |

所以本项目支持**组合**：key 是 `限流维度:路径`，不同规则的路径不共用桶。

---

## 3. 熔断：三个状态到底怎么切换

### 为什么不能只做"失败多了就打开"

很多人只知道 CLOSED 和 OPEN 两个状态，答不上来 **OPEN 之后怎么恢复**。

如果"过一段时间自动恢复（直接回 CLOSED）"，会发生什么：

```
下游刚恢复 30%，还没完全好
→ 熔断到时，放全部流量过去
→ 下游再次被打垮
→ 又熔断
→ 反复震荡
```

所以需要第三个状态 **HALF_OPEN**：

```
             失败率 ≥ 50%（且至少 5 次请求）
   CLOSED ───────────────────────────────▶ OPEN
      ▲                                     │
      │ 试探全部成功                          │ 等待 10 秒
      │                                     ▼
      └───────────────────────────── HALF_OPEN
                    试探失败 → 立刻回 OPEN
```

- **OPEN**：一个请求都不放给下游。这是关键 —— 熔断期间下游才有机会恢复。
- **HALF_OPEN**：只放行 `halfOpenPermits`（本项目 2 个）试探请求。
  - 全部成功 → CLOSED
  - 任何一个失败 → 立刻回 OPEN，重新计时

### 实现上的几个坑

**坑 1：4xx 不应该算失败**

客户端参数错误（400）不是下游的锅。如果算进失败率，
一个前端 bug 就能把整个服务熔断。所以只统计 **5xx** 和异常。

**坑 2：滚动窗口不能用固定时间片**

本项目用的是"累计 N 次后重新统计"的简化实现（N=5），
足以演示和面试。生产环境建议直接用 Resilience4j 的
`COUNT_BASED` / `TIME_BASED` 滑动窗口。

**坑 3：熔断打开状态要能被外部观测**

否则线上出问题根本不知道熔断了。所以加了 `/gateway/state` 接口，
前端状态条会实时显示。

---

## 4. Nacos 配置热更新：改阈值不重启

这是本课题最容易被面试追问的点：「线上流量突增，你怎么临时调大限流阈值？」

答案是 **`@RefreshScope` + Nacos 配置监听**：

```java
@Component
@RefreshScope   // ← 加了这个，配置变更时 Bean 会重新创建/重新绑定
@ConfigurationProperties(prefix = "lab.ratelimit")
public class RateLimitProperties { ... }
```

`@RefreshScope` 的原理：Bean 被包成一个 **动态代理**，
配置刷新时销毁真实对象，下次调用时重新创建并重新绑定属性。

配置中心里改：

```yaml
# Nacos 控制台 → 配置管理 → lab-gateway-ratelimit.yaml
lab:
  ratelimit:
    rules:
      - path: /api/order/**
        dimension: USER
        capacity: 50          # 原来是 5，改成 50
        refill-per-second: 20 # 原来是 2
```

**不用重启网关，下一次请求立即生效。**

> 实测日志里能看到：
> `[Nacos Config] Listening config: dataId=lab-gateway-ratelimit.yaml, group=DEFAULT_GROUP`

---

## 5. 实测数据

### 限流

经网关连发 20 次 `/api/order/my`（规则：2 次/秒，突发 5）：

```
HTTP 200 (放行)   -> 8 次
HTTP 429 (被限流) -> 12 次
```

被限流的响应头：

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 5
X-RateLimit-Policy: 2.0r/s, burst 5
Retry-After: 1
```

响应体：

```json
{"error":"RATE_LIMITED","path":"/api/order/my","message":"请求过于频繁，请稍后重试"}
```

3 秒后再发单请求 → 恢复 200（令牌桶按时间补充，符合预期）。

### 熔断

| 阶段 | 操作 | 结果 |
|---|---|---|
| 初始 | - | `CLOSED` |
| 停掉 app 服务 | 发 12 次请求 | 全部 503，状态变 `OPEN` |
| 熔断中 | 再发请求 | `{"error":"SERVICE_DEGRADED","message":"服务已熔断，请稍后重试"}`，**请求没有打到下游** |
| 等 10 秒 | 发请求 | 状态变 `HALF_OPEN`，试探失败 → 立刻回 `OPEN` |
| 恢复 app | 等 10 秒后发 3 次 | 状态 `HALF_OPEN` → 3 次 200 → `CLOSED` |

**三态完整验证通过。**

---

## 6. 代价与取舍

| 取舍 | 说明 |
|---|---|
| Redis 成为依赖 | 限流依赖 Redis。本实现做了 **fail-open**：Redis 挂了降级为放行，保证可用性。安全要求高的场景应改成 fail-closed |
| 每个请求多一次 Redis 往返 | 约 0.2ms。可以用本地缓存令牌减少，但多实例下会不准确 |
| 手写熔断器不如 Resilience4j 完整 | 本项目是为了讲清原理。生产直接用 Resilience4j，它还有慢调用率、半开自动重试等 |
| Nacos 增加运维成本 | 单机 Nacos 约 300MB 内存。小项目可以用 application.yml，但就失去了热更新能力 |

## 7. 对应代码

| 文件 | 职责 |
|---|---|
| `gateway/.../lua/token_bucket.lua` | 令牌桶 Lua 脚本 |
| `gateway/.../ratelimit/TokenBucketRateLimitFilter.java` | 限流全局过滤器 |
| `gateway/.../ratelimit/RateLimitProperties.java` | 规则配置 + `@RefreshScope` 热更新 |
| `gateway/.../ratelimit/RateLimitRule.java` | 单条规则、维度枚举、Ant 匹配 |
| `gateway/.../circuitbreaker/SimpleCircuitBreakerFilter.java` | 三态熔断器 |
| `gateway/.../circuitbreaker/CircuitBreakerProperties.java` | 熔断参数 |
| `gateway/src/main/resources/application.yml` | 路由 + Nacos + 默认规则 |