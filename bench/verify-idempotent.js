// 幂等验证 + 可靠消息链路验证
// 用法: node bench/verify-idempotent.js <accessToken>
const BASE = 'http://127.0.0.1:8080';
const token = process.argv[2];

if (!token) {
  console.error('用法: node bench/verify-idempotent.js <accessToken>');
  process.exit(2);
}

async function submit(requestKey) {
  const res = await fetch(`${BASE}/api/order/submit?requestKey=${encodeURIComponent(requestKey)}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + token,
      'Idempotency-Key': requestKey
    },
    body: JSON.stringify({ skuId: 1001, amount: 99 })
  });
  let body;
  const text = await res.text();
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body };
}

(async () => {
  const key = 'verify-' + Date.now();
  console.log('=== 幂等验证：使用同一个 Idempotency-Key 提交两次 ===');
  console.log('Idempotency-Key =', key);
  console.log('');

  const first = await submit(key);
  console.log('第 1 次: HTTP', first.status, JSON.stringify(first.body));
  const second = await submit(key);
  console.log('第 2 次: HTTP', second.status, JSON.stringify(second.body));
  console.log('');

  const id1 = first.body && first.body.id;
  const id2 = second.body && second.body.id;
  const same = id1 != null && id2 != null && String(id1) === String(id2);

  if (same) {
    console.log(`✅ 两次返回同一张订单 id=${id1}`);
    console.log('   第 2 次命中了 Redis 的 DONE 状态，直接回放第 1 次的结果，没有创建新订单。');
  } else if (second.status === 429) {
    console.log('⚠️ 第 2 次返回 429（PROCESSING 状态，第 1 次尚未执行完）');
  } else {
    console.log(`❌ 两次订单 id 不同（${id1} vs ${id2}），幂等未生效`);
  }

  // 换一个 key，应该是不同订单
  const key2 = 'verify-diff-' + Date.now();
  const third = await submit(key2);
  console.log('');
  console.log('=== 换一个 Idempotency-Key 提交（应创建新订单） ===');
  console.log('第 3 次: HTTP', third.status, JSON.stringify(third.body));
  const id3 = third.body && third.body.id;
  if (id3 != null && String(id3) !== String(id1)) {
    console.log(`✅ 新 key 创建了新订单 id=${id3}，说明幂等是按 key 生效而不是按用户生效`);
  } else {
    console.log('❌ 新 key 没有创建新订单');
  }

  console.log('');
  console.log('=== 订单返回字段里的 messageId / delayMinutes ===');
  console.log('messageId =', first.body.messageId);
  console.log('delayMinutes =', first.body.delayMinutes);

  const allOk = same && String(id3) !== String(id1);
  process.exit(allOk ? 0 : 1);
})();