// WebSocket 客户端：连接后用 token 鉴权，把收到的消息写入文件，供验证脚本读取
// 用法: node bench/ws-listen.js <accessToken> <outputFile> [durationMs]
const fs = require('fs');

const token = process.argv[2];
const outFile = process.argv[3] || 'ws-out.txt';
const duration = Number(process.argv[4] || 20000);

if (!token) {
  console.error('用法: node bench/ws-listen.js <accessToken> <outputFile> [durationMs]');
  process.exit(2);
}

// Node 18+ 有内置 WebSocket（Node 22+ 稳定）
const url = `ws://127.0.0.1:8080/ws/orders?token=${encodeURIComponent(token)}`;
const lines = [];
const log = (s) => {
  const line = `[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${s}`;
  lines.push(line);
  console.log(line);
  fs.writeFileSync(outFile, lines.join('\n'));
};

log('connecting to ' + url.slice(0, 60) + '...');

const ws = new WebSocket(url);

ws.onopen = () => {
  log('OPEN 连接建立');
  // 发心跳
  ws.send('PING');
};

ws.onmessage = (e) => log('RECV ' + e.data);
ws.onerror = () => log('ERROR 连接失败（token 无效或服务未启动）');
ws.onclose = (e) => log('CLOSE code=' + e.code + ' reason=' + (e.reason || ''));

setTimeout(() => {
  log('超时退出');
  try { ws.close(); } catch {}
  process.exit(0);
}, duration);