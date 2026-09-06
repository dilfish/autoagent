// AutoAgent 远程指挥中转服务
// 部署在 ddeb 上：手机(WebSocket 客户端) ←→ 本服务 ←→ 浏览器控制页
//
//   手机端连接:  ws://<host>:PORT/ws/phone?token=<TOKEN>
//   控制页连接:  ws://<host>:PORT/ws/operator?token=<TOKEN>
//   控制页地址:  http://<host>:PORT/?token=<TOKEN>
//
// 协议（JSON 消息）:
//   phone -> relay : {type:"hello",device} {type:"snapshot",text} {type:"result",cmdId,ok,error} {type:"screenshot",data}
//   relay -> phone : {type:"command",cmd} {type:"screenshotRequest",reqId}
//   operator -> relay : {type:"command",cmd} {type:"screenshotRequest"}
//   relay -> operator : {type:"device",device} {type:"snapshot",text} {type:"result",cmdId,ok,error} {type:"screenshot",data} {type:"status",phoneConnected}

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8787;
const TOKEN = process.env.TOKEN || 'autoagent';

const server = http.createServer((req, res) => {
  if (new URL(req.url, 'http://x').searchParams.get('token') !== TOKEN) {
    res.writeHead(403);
    return res.end('forbidden');
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(fs.readFileSync(path.join(__dirname, 'public', 'index.html')));
});

const wss = new WebSocketServer({ server });
let phone = null; // 手机连接（单设备）
const operators = new Set();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x');
  if (url.searchParams.get('token') !== TOKEN) return ws.close();

  const isPhone = url.pathname === '/ws/phone';
  if (isPhone) {
    if (phone) phone.close();
    phone = ws;
    ws.isPhone = true;
    broadcastOperators({ type: 'status', phoneConnected: true });
    console.log('[+] phone connected');
  } else {
    operators.add(ws);
    if (phone) ws.send(JSON.stringify({ type: 'status', phoneConnected: true }));
    console.log('[+] operator connected, total', operators.size);
  }

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (ws.isPhone) {
      // 手机消息 → 所有操作者
      for (const op of operators) {
        if (op.readyState === 1) op.send(raw.toString());
      }
    } else {
      // 操作者消息 → 手机（命令 / 截图请求）
      if (phone && phone.readyState === 1) phone.send(raw.toString());
    }
  });

  ws.on('close', () => {
    if (ws.isPhone) {
      if (phone === ws) phone = null;
      broadcastOperators({ type: 'status', phoneConnected: false });
      console.log('[-] phone disconnected');
    } else {
      operators.delete(ws);
      console.log('[-] operator disconnected, total', operators.size);
    }
  });
});

function broadcastOperators(obj) {
  const s = JSON.stringify(obj);
  for (const op of operators) {
    if (op.readyState === 1) op.send(s);
  }
}

server.listen(PORT, () => {
  console.log(`relay listening on :${PORT}, token=${TOKEN}`);
});
