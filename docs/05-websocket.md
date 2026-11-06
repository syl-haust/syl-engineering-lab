# 课题⑤：WebSocket 实时推送

## 1. 问题从哪来

没有实时推送时，订单状态变化（支付成功、超时取消）用户只能**刷新页面**才能看到。
如果要做"订单取消时通知用户"，最常见的糟糕做法是**轮询**：

```javascript
setInterval(() => fetch('/api/order/my'), 3000);   // 每 3 秒查一次
```

| 问题 | 数据 |
|---|---|
| 大量无效请求 | 1 万在线用户 × 每 3 秒 = **3300 QPS 纯浪费** |
| 状态延迟 | 最坏情况延迟 3 秒 |
| 数据库压力 | 每 3 秒扫一次订单表，和正常业务抢资源 |

WebSocket 把「服务器主动通知」变成可能，一次推送只消耗一个帧。

---

## 2. 为什么是 WebSocket 而不是 SSE

| 方案 | 方向 | 适用 |
|---|---|---|
| 轮询 | 客户端拉 | 简单但浪费 |
| 长轮询 | 客户端拉（挂起） | 兼容性最好，但占连接 |
| **SSE** | 服务端推 | 单向、基于 HTTP、自动重连，**只需要推送时更优** |
| **WebSocket** | 双向 | **本项目采用**：后续可以加客户端指令（订阅、标记已读） |

选 WebSocket 的理由：订单场景后续大概率要双向（比如用户点"我知道了"，服务端要记录）。

---

## 3. 三个关键设计点

### 3.1 握手鉴权：为什么必须做，怎么做

**WebSocket 建立后是一条裸 TCP 长连接，后续帧不经过 Spring MVC 拦截器。**

如果握手时不校验身份：
- 任何人都能连上来
- 连接会一直存在直到主动断开
- 比 HTTP 的越权危险得多（HTTP 至少每次请求还能拦）

浏览器的 `new WebSocket()` **不能自定义 Header**，所以 token 只能放 query：

```javascript
new WebSocket('ws://localhost:8080/ws/orders?token=' + accessToken)
```

实现见 `WsAuthInterceptor`：复用亮点①的 Access Token，
走的是同一套 `authService.authenticatedUserId()`，校验签名 + Redis 白名单。

**好处**：被踢下线的用户（logout 删了白名单）连 WS 也连不上 ——
两个亮点就此串起来了。

### 3.2 会话管理：一用户多端

```java
Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
```

三个注意点：

1. **用 Set 不是单个 Session**：同一用户可能同时开手机和电脑。
2. **必须线程安全**：WebSocket 回调在 Netty IO 线程执行，
   推送又来自 MQ 消费线程，必然并发访问。
3. **session 不是线程安全的**：并发 `sendMessage` 会抛
   `IllegalStateException: The remote endpoint was in state [TEXT_FULL_WRITING]`，
   所以发送时要 `synchronized (session)`。

4. **断开必须清理**：否则内存泄漏，推送到已断开的连接会抛 IOException。

### 3.3 MQ → WebSocket：事务边界是最大的坑

这是本课题**最值得讲**的一点。

订单超时取消的流程：

```java
@RabbitListener(queues = EXPIRE_QUEUE)
public void onExpire(OrderMessage message) {
    tx.executeWithoutResult(status -> {      // ← 事务
        consumedMapper.tryConsume(...);
        orderMapper.cancelIfUnpaid(...);
        pusher.pushOrderStatus(...);         // ❌ 如果写在这里就会出问题
    });
}
```

**如果把推送写在事务里**：
- 事务后续步骤抛异常 → 回滚
- 但用户已经收到"订单已取消"的推送
- 用户看到取消，数据库里其实还是待支付 —— **数据不一致**

正确做法：**先提交事务，再推送**。

```java
boolean[] cancelled = {false};
tx.executeWithoutResult(status -> {
    ...
    cancelled[0] = affected > 0;
});
// 事务已提交，此时推送才安全
if (cancelled[0]) {
    pusher.pushOrderStatus(...);
}
```

> 这个思路和"MQ 消息要在事务提交后再发"是完全一致的。
> 面试时可以把这两点串起来讲，说明你理解"事务边界"这件事。

---

## 4. 完整链路

```
下单 ──▶ Outbox 表（同事务）
              │
              ▼
        RabbitMQ 延时队列（30 分钟）
              │  到期
              ▼
        过期队列 ──▶ OrderConsumer.onExpire()
                          │  ① 事务：幂等检查 + 取消订单
                          │  ② 提交
                          ▼
                  OrderEventPusher.pushOrderStatus()
                          │
                          ▼
              OrderWebSocketHandler.pushToUser(userId)
                          │
                          ▼
                   浏览器实时显示
```

这是一条完整的 **事件驱动** 链路，串起了 出口策略、消息可靠性、实时推送 三个课题。

---

## 5. 实测

**连接阶段**（Node 客户端）：

```
[18:18:48] OPEN 连接建立
[18:18:48] RECV {"type":"CONNECTED","userId":1}
[18:18:48] RECV {"type":"PONG"}            ← 心跳
```

**推送阶段**（下单后触发超时取消）：

```
[18:18:57] RECV {"type":"ORDER_STATUS","orderId":15,"status":2,"message":"订单超时未支付，已自动取消"}
```

服务端日志：

```
订单超时自动取消 orderId=15 affected=1
订单状态推送 userId=1 orderId=15 status=2 送达连接数=1
```

---

## 6. 代价与取舍

| 取舍 | 说明 |
|---|---|
| 长连接占资源 | 每个连接占内存；1 万连接需要调大 Netty 线程数和文件句柄 |
| 多实例需要广播 | 用户连在 A 实例，但消息在 B 实例消费。生产环境需要 **Redis Pub/Sub 或广播队列** 做跨实例路由。本项目单实例演示 |
| 断线期间消息会丢 | 本项目没有离线消息。生产环境应存一份"未读通知表"，用户重连后拉取 |
| 需要心跳保活 | 中间代理（Nginx 默认 60s）会断开空闲连接。本项目前端可发 PING，服务端回 PONG |

## 7. 如果继续做

1. **跨实例广播**：Redis Pub/Sub 把消息发到所有实例，各自检查本地有没有该用户的连接。
2. **离线消息**：未送达时落库，用户连接后补推。
3. **自动重连 + 指数退避**：前端断线后 1s、2s、4s... 重连。
4. **消息 ACK**：客户端确认收到，未 ACK 的重新投递。

## 8. 对应代码

| 文件 | 职责 |
|---|---|
| `app/.../ws/WebSocketConfig.java` | 注册端点 `/ws/orders` |
| `app/.../ws/WsAuthInterceptor.java` | 握手鉴权，复用 Access Token |
| `app/.../ws/OrderWebSocketHandler.java` | 会话管理、推送、心跳 |
| `app/.../ws/OrderEventPusher.java` | 领域事件 → WS 消息 |
| `app/.../mq/OrderConsumer.java` | 事务提交后推送（关键） |
| `bench/ws-listen.js` | Node 命令行客户端，用于自动化验证 |