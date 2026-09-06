// pi-stub：桌面 pi 中间层。假装自己是 pi，按剧本文件 step-by-step 回固定
// JSON 命令，供手机 App（HttpStubBackend）调试，不依赖真 pi/SSH。
//
// 启动:
//   node pi-stub.js --script ./pi-scripts/demo.json        按剧本 serve
//   node pi-stub.js --script ./pi-scripts/demo.json --port 8788
//   node pi-stub.js --replay <traceDir>                     把 PiTrace 录制的
//       stepNN-reply.txt 序列当剧本 serve（复现某次真机运行）
//
// 端点:
//   POST /v1/next   body {"payload":"..."} → {"reply":"[...]"}（step 计数+1，
//       收到的 payload 原样写入 .pi-stub-log/<ts>-stepNN-payload.txt）
//   GET  /v1/status → {step, scriptName, total}
//   POST /v1/reset  → step 清零
//   GET  /          → 简易调试页（步数 / 上次 payload 前 500 字 / 下次 reply）

const http = require('http');
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
}

const PORT = Number(process.env.PI_STUB_PORT || arg('--port', '8788'));
const TOKEN = process.env.TOKEN || 'autoagent';
const LOG_DIR = path.join(__dirname, '.pi-stub-log');

// ---- 剧本加载 ----
function loadScriptFromFile(file) {
  const raw = fs.readFileSync(file, 'utf8');
  const parsed = JSON.parse(raw);
  const steps = (parsed.steps || []).map((s) => String(s.reply));
  if (steps.length === 0) throw new Error(`剧本无 steps: ${file}`);
  return { name: path.basename(file), steps };
}

// --replay <traceDir>：按 stepNN-reply.txt 文件名排序组成剧本
function loadReplay(traceDir) {
  const files = fs.readdirSync(traceDir)
    .filter((f) => /^step\d+-reply\.txt$/.test(f))
    .sort();
  if (files.length === 0) throw new Error(`目录下无 stepNN-reply.txt: ${traceDir}`);
  return { name: `replay:${path.basename(traceDir)}`, steps: files.map((f) => fs.readFileSync(path.join(traceDir, f), 'utf8')) };
}

const replayDir = arg('--replay', null);
const scriptFile = arg('--script', path.join(__dirname, 'pi-scripts', 'demo.json'));
const script = replayDir ? loadReplay(path.resolve(replayDir)) : loadScriptFromFile(path.resolve(scriptFile));

let step = 0;
let lastPayload = '';
let lastPayloadAt = '';

function replyFor(i) {
  return script.steps[Math.min(i, script.steps.length - 1)];
}

function logPayload(payload) {
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    fs.writeFileSync(path.join(LOG_DIR, `${ts}-step${String(step).padStart(2, '0')}-payload.txt`), payload);
  } catch (_) {
    // 日志落盘失败不影响 serve
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.searchParams.get('token') !== TOKEN && url.pathname !== '/') {
    res.writeHead(403);
    return res.end('forbidden');
  }

  if (req.method === 'POST' && url.pathname === '/v1/next') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let payload = '';
      try {
        payload = JSON.parse(body).payload || '';
      } catch (_) {
        payload = body;
      }
      lastPayload = payload;
      lastPayloadAt = new Date().toLocaleString();
      logPayload(payload);
      const reply = replyFor(step);
      step++;
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ reply }));
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/v1/status') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ step, scriptName: script.name, total: script.steps.length }));
    return;
  }

  if (req.method === 'POST' && url.pathname === '/v1/reset') {
    step = 0;
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // 简易调试页
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  res.end(`<html><body><h2>pi-stub</h2>
<p>剧本: ${esc(script.name)}（共 ${script.steps.length} 步）当前步: ${step}</p>
<p>上次 payload (${esc(lastPayloadAt)}):</p><pre>${esc(lastPayload.slice(0, 500))}</pre>
<p>下次 reply:</p><pre>${esc(replyFor(step).slice(0, 500))}</pre>
</body></html>`);
});

server.listen(PORT, () => {
  console.log(`pi-stub listening on :${PORT}, script=${script.name} (${script.steps.length} steps), token=${TOKEN}`);
});
