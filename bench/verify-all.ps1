# 一键验证三个亮点（需要应用已启动在 8080）
# 用法: powershell -ExecutionPolicy Bypass -File bench/verify-all.ps1

$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:8080'

Write-Host '=== 检查服务 ===' -ForegroundColor Cyan
$code = & curl.exe -s -o NUL -w '%{http_code}' "$base/"
if ($code -ne '200') {
    Write-Host '服务未启动，请先运行 mvn spring-boot:run' -ForegroundColor Red
    exit 1
}
Write-Host "服务正常 ($code)" -ForegroundColor Green

function Login {
    $body = '{"username":"demo","password":"123456"}'
    $bodyFile = Join-Path $env:TEMP 'lab-login.json'
    $outFile = Join-Path $env:TEMP 'lab-login.out'
    [System.IO.File]::WriteAllText($bodyFile, $body, (New-Object System.Text.UTF8Encoding($false)))
    & curl.exe -s -X POST "$base/api/auth/login" -H 'Content-Type: application/json' --data-binary "@$bodyFile" -o $outFile
    return (Get-Content $outFile -Raw | ConvertFrom-Json)
}

Write-Host ''
Write-Host '=== 登录 ===' -ForegroundColor Cyan
$login = Login
$access = $login.accessToken
Write-Host ('登录成功，access token 长度 ' + $access.Length) -ForegroundColor Green

Write-Host ''
Write-Host '=== ① 并发刷新防重放 ===' -ForegroundColor Cyan
$refreshFile = Join-Path $env:TEMP 'lab-refresh.txt'
[System.IO.File]::WriteAllText($refreshFile, $login.refreshToken, (New-Object System.Text.UTF8Encoding($false)))
$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    & node "$PSScriptRoot/concurrent-refresh.js" $refreshFile
} else {
    Write-Host '未安装 node，跳过（可在浏览器点「并发刷新 ×3」）' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== ② 接口幂等 ===' -ForegroundColor Cyan
# token 已被上一轮刷新轮换，重新登录
$login2 = Login
$access2 = $login2.accessToken
if ($node) {
    & node "$PSScriptRoot/verify-idempotent.js" $access2
} else {
    Write-Host '未安装 node，跳过（可在浏览器点「连续提交 2 次」）' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== ③ 延时队列 ===' -ForegroundColor Cyan
$qFile = Join-Path $env:TEMP 'lab-queues.json'
& curl.exe -s -u guest:guest 'http://127.0.0.1:15672/api/queues/%2F' -o $qFile
$raw = Get-Content $qFile -Raw -ErrorAction SilentlyContinue
if ($raw) {
    $queues = $raw | ConvertFrom-Json
    foreach ($q in $queues) {
        $ttl = ''
        if ($q.arguments.'x-message-ttl') { $ttl = ' TTL=' + $q.arguments.'x-message-ttl' + 'ms' }
        Write-Host ('  {0,-32} messages={1,-3} consumers={2}{3}' -f $q.name, $q.messages, $q.consumers, $ttl)
    }
    Write-Host '提示：30 分钟太长，想快速看效果把 MqConfig 的 x-message-ttl 改成 5000，重启后下单一单，5 秒后自动取消' -ForegroundColor Yellow
} else {
    Write-Host '无法访问 RabbitMQ 管理接口（15672）' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '验证完成' -ForegroundColor Green