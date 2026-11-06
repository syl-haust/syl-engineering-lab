# syl-engineering-lab

五个**生产级工程实践**的独立实现，每个都附设计论证与实测数据。

> **背景**：这个仓库起源于我做过的一个微服务商城项目（Spring Boot 3 + Spring Cloud Alibaba + MySQL + Redis）。
> 那个项目功能是全的，但在认证、消息可靠性、幂等、网关防护、实时通知这几块都是"能跑但工程上不成立"的实现。
> 我把这五处抽出来，**逐个从零重做**，并论证每个方案的设计取舍。
>
> 每个课题都写清了：原先的写法有什么问题 → 为什么这样改 → 代价是什么。

![业务能力演示](docs/screenshot-page-a.png)
![网关能力演示](docs/screenshot-page-b.png)

---

## 五个课题

| # | 课题 | 解决的问题 | 关键技术 |
|---|---|---|---|
| ① | **双 Token 认证** | JWT 签发后无法吊销、泄露后长期有效、多标签页并发刷新把用户踢下线 | Access/Refresh 分离、refresh 轮换、Redis 白名单、**Lua 原子占位** |
| ② | **可靠异步下单** | 写库与发 MQ 无法同事务、消息静默丢失 | **本地消息表（Outbox）**、publisher confirm/return、指数退避补偿 |
| ③ | **声明式幂等** | 重复点击 / 网关重试产生重复订单 | `@Idempotent` + **Redis Lua 三态** + DB 唯一索引 |
| ④ | **网关限流与熔断** | 接口被刷爆、下游故障导致雪崩 | **Redis Lua 令牌桶**、CLOSED/OPEN/HALF_OPEN 三态熔断、**Nacos 热更新** |
| ⑤ | **WebSocket 实时推送** | 订单状态只能轮询 | MQ → WS 桥接、按用户定向、**事务提交后推送** |

---

## 架构

```
                    ┌──────────────────────┐
   浏览器 ─────────▶│  gateway  :9000      │
                    │  · 令牌桶限流 (Lua)    │
                    │  · 三态熔断            │
                    │  · Nacos 热更新        │
                    └──────────┬───────────┘
                               │ lb://lab-app (Nacos 服务发现)
                               ▼
                    ┌──────────────────────┐
                    │  app  :8080          │
                    │  ┌────────────────┐  │
                    │  │ 认证 / 幂等 / 订单 │  │
                    │  └───────┬────────┘  │
                    │          ▼           │
                    │   Outbox ──▶ RabbitMQ │
                    │          │           │
                    │          ▼           │
                    │   超时取消消费者        │
                    │          │           │
                    │          ▼           │
                    │   WebSocket 推送 ────▶│──▶ 浏览器
                    └──────────────────────┘
             MySQL · Redis · RabbitMQ · Nacos
```

---

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d          # MySQL(3307) + Redis(6379) + RabbitMQ(5672) + Nacos(8848)
```

> 如果你本机已有 MySQL，可以只起 `docker compose up -d redis rabbitmq nacos`，
> 然后手工执行 `mysql -uroot -p < sql/init.sql`。

### 2. 启动两个服务

```bash
mvn -q -DskipTests package

# 业务服务
java -jar app/target/app-1.0.0.jar

# 网关（另开一个终端）
java -jar gateway/target/gateway-1.0.0.jar
```

### 3. 打开演示页

**http://localhost:8080** — 两个标签页，每个按钮都是真实调用后端。

| 入口 | 地址 |
|---|---|
| 业务服务 | http://localhost:8080 |
| 网关 | http://localhost:9000 |
| Nacos 控制台 | http://localhost:8848/nacos |
| RabbitMQ 管理台 | http://localhost:15672 （guest/guest） |

默认账号：`demo` / `123456`

---

## 实测数据

### ① 并发刷新防重放

```
3 个请求带同一个 refresh token 同时刷新：
  请求 1: ✅ 200 成功（获得刷新资格）
  请求 2: ⚠️ 409 已被使用（防重放生效）
  请求 3: ⚠️ 409 已被使用（防重放生效）
```

### ② 幂等

```
第 1 次: HTTP 200 {"id":9,...}
第 2 次: HTTP 200 {"id":9,...}   ← 同一张订单，回放第一次结果
换 key 后: HTTP 200 {"id":10,...} ← 新订单，证明幂等按 key 生效
```

### ③ 延时队列

```
lab.order.delay.30m.queue   TTL=1800000ms   consumers=0
lab.order.expire.queue      consumers=1     ← 死信消费者
```

### ④ 限流（经网关连发 20 次，规则 2r/s 突发 5）

```
HTTP 200 (放行)   -> 8 次
HTTP 429 (被限流) -> 12 次

响应头: X-RateLimit-Limit: 5
        X-RateLimit-Policy: 2.0r/s, burst 5
        Retry-After: 1
```

### ⑤ 熔断三态（停掉 app 服务）

```
CLOSED → 12 次请求全 503 → OPEN
        → 降级响应 {"error":"SERVICE_DEGRADED"}
等 10s  → HALF_OPEN → 试探失败 → 回 OPEN
恢复 app → HALF_OPEN → 3 次 200 → CLOSED
```

### ⑥ WebSocket 推送

```
[18:18:48] OPEN 连接建立
[18:18:48] RECV {"type":"CONNECTED","userId":1}
[18:18:57] RECV {"type":"ORDER_STATUS","orderId":15,"status":2,"message":"订单超时未支付，已自动取消"}
```

---

## 目录结构

```
├── app/                     # 业务服务 :8080
│   └── src/main/java/com/syl/lab
│       ├── auth/            # 课题① 双 Token
│       ├── mq/              # 课题② Outbox + 延时队列
│       ├── idempotent/      # 课题③ 声明式幂等
│       ├── ws/              # 课题⑤ WebSocket
│       ├── order/           # 串起各课题的演示业务
│       └── common/
├── gateway/                 # 网关 :9000
│   └── src/main/java/com/syl/lab/gateway
│       ├── ratelimit/       # 课题④ 令牌桶（含 Lua 脚本）
│       └── circuitbreaker/  # 课题④ 三态熔断
├── bench/                   # 验证脚本（Node / PowerShell）
├── docs/                    # 设计文档
├── sql/init.sql
└── docker-compose.yml
```

---

## 从原项目到本仓库：改了什么

| 原项目 | 本仓库 | 为什么 |
|---|---|---|
| 单 JWT，拦截器验签放行 | Access(15m) + Refresh(7d)，Redis 白名单 | 无法踢人、无法吊销 |
| 下单同步串行，无事务 | 订单与消息同事务落库，MQ 异步下游 | 下游变慢会拖垮下单 |
| 无超时取消 | 延时队列 + 死信 | 扫表在大数据量下索引失效 |
| 无幂等控制 | `@Idempotent` + Redis + DB 唯一键 | 重复提交产生重复订单 |
| 网关只做路由 | 令牌桶限流 + 三态熔断 + Nacos 热更新 | 一个慢服务拖垮整个网关 |
| 订单状态只能刷新页面看 | WebSocket 实时推送 | 轮询浪费 3300 QPS |

> 上表每一行都是**独立重写**，没有复用原项目的业务代码。
> 本仓库只保留了"有真实业务背景的问题场景"，实现、包结构、表结构全部自建。

---

## 文档

| 文档 | 内容 |
|---|---|
| [docs/01-auth.md](docs/01-auth.md) | 双 Token：白名单 vs 黑名单、轮换、**并发刷新防重放** |
| [docs/02-mq.md](docs/02-mq.md) | 本地消息表、延时队列队头阻塞、可靠性全链路 |
| [docs/03-idempotent.md](docs/03-idempotent.md) | 三层防护、**为什么 SETNX 不够** |
| [docs/04-gateway.md](docs/04-gateway.md) | 令牌桶 vs 固定窗口、**三态熔断**、Nacos 热更新 |
| [docs/05-websocket.md](docs/05-websocket.md) | 握手鉴权、多端会话、**事务边界** |

---

## 测试

```bash
mvn test
```

## License

MIT © 2026 宋雨乐

本仓库所有代码为独立实现，仅作学习与技术演示用途。