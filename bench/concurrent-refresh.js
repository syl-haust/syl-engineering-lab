// 并发刷新验证：3 个请求共用同一个 refresh token，应只有 1 个成功（200），其余 409
const fs = require('fs');

const refreshToken = fs.readFileSync(process.argv[2], 'utf8').trim();
const url = 'http://127.0.0.1:8080/api/auth/refresh';

async function one(i) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken })
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { i, status: res.status, ok: res.ok, error: body.error, accessLen: body.accessToken ? body.accessToken.length : 0 };
}

(async () => {
  const results = await Promise.all([1, 2, 3].map(one));
  const okCount = results.filter(r => r.ok).length;
  const conflictCount = results.filter(r => r.status === 409).length;
  console.log('并发 3 个刷新请求结果：');
  for (const r of results) {
    const tag = r.ok ? '✅ 200 成功（获得刷新资格）'
      : (r.status === 409 ? '⚠️ 409 已被使用（防重放生效）' : '❌ ' + r.status + ' ' + (r.error || ''));
    console.log(`  请求 ${r.i}: ${tag}`);
  }
  console.log('');
  console.log(`成功 ${okCount} 个，409 ${conflictCount} 个`);
  console.log(okCount === 1 && conflictCount === 2
    ? '✅ 结论符合预期：并发刷新只放行一个，其余返回 409，用户不会被误踢下线。'
    : '⚠️ 结果不符合预期，需要检查');
  process.exit(okCount === 1 ? 0 : 1);
})();