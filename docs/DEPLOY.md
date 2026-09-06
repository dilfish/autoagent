# AutoAgent 部署与使用文档

## 1. 构建 APK（开发机 macOS）

环境只装一次：

```bash
export ALL_PROXY=socks5h://localhost:1140; brew install openjdk@17 android-commandlinetools
export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
/usr/local/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

日常构建（仓库根目录）：

```bash
JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装）。
依赖下载走 socks5h://localhost:1140（见 `gradle.properties`），Gradle 发行版位于 `~/tools/gradle-8.9`（wrapper 会自动下载自己的副本）。

## 2. 安装到手机

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / 网盘 / USB 拷贝均可），点开安装，允许"未知来源"
2. **Android 13+ 传 APK 自装会有"受限设置"限制**，先做这一步：
   设置 → 应用 → AutoAgent → 右上角菜单 → **允许受限设置**
3. 设置 → 无障碍 → AutoAgent → **开启「AutoAgent 执行引擎」**
4. **保活**（否则国产 ROM 会杀服务）：
   设置 → 电池优化 → AutoAgent → 不优化；部分 ROM 还有"自启动管理"里允许自启动
5. 打开 App 确认主页显示"无障碍服务：已开启"

### （可选）启用截图功能

设置 → 屏幕截图 → 授权录屏 → 确认。手机重启后需要重新授权一次。

## 3. 部署远程指挥中转（ddeb 服务器）

```bash
ssh root@your.server.com
apt install -y nodejs npm   # 服务器已有 Node 22 可跳过
mkdir -p /opt/autoagent-relay/public
# 上传 relay.js 和 public/index.html（或用 git 拉取）
cd /opt/autoagent-relay && npm install ws --omit=dev
TOKEN=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')   # 生成随机 token，记下来
PORT=8787 TOKEN=$TOKEN nohup node relay.js > relay.log 2>&1 &
```

验证：`curl "http://127.0.0.1:8787/?token=$TOKEN" | head` 能返回 HTML。

防火墙放行 8787（云厂商安全组也要放行）。建议后续加 nginx + TLS：
手机端 WebSocket 走 `wss://`，浏览器走 `https://`。

## 4. 配置远程指挥

**手机端**（AutoAgent → 设置 → 远程指挥）：
- 中转 WebSocket：`ws://your.server.com:8787/ws/phone`
- Token：部署时生成的 `$TOKEN`
- 点「连接」，状态变"已连接"即成功

**操作端**（你的电脑浏览器）：
- 打开 `http://your.server.com:8787/?token=$TOKEN`
- 左侧：实时节点树快照（手机端每 350ms 防抖推送）、「刷新元素」、截图按钮
- 右侧：命令行，支持 `click [03]`、`click 540 1200`、`inputText [02] 你好`、
  `back`、`home`、`scroll up`、`wait 1000`、`getElements`，或直接粘贴协议 JSON
- 每条命令的执行结果实时回显（✓/✗+错误原因）

## 5. 配置 AI 大脑

### 5.1 内置 LLM（OpenAI 兼容）

设置 → LLM API：
- Base URL：如 `https://open.qiniu.com/v1`（OpenAI 兼容端点）
- API Key、模型名
- 保存后回主页 → AI 任务卡 → 大脑选「内置 LLM」→ 输入任务 → 「AI 执行」

### 5.2 pi agent（ddeb 服务器）✅ 已实现

设置 → pi agent：
- 主机 `your.server.com`、端口 22、用户 `root`、密码
- pi 路径：`/root/.local/share/pi-node/node-v22.23.2-linux-x64/bin/pi`
  （App 会自动把 pi 所在目录加进 PATH，服务器 PATH 只在 .zshrc）
- 保存后主页 AI 任务 → 大脑选「pi agent」→ 输入任务 → 「AI 执行」

模型/Provider 跟随服务器上 `~/.pi/agent` 的配置（你在服务器上配置的模型）。
已在 ddeb 实测：`echo "<快照>" | pi -p --no-session --no-tools` 按协议返回 JSON 命令数组。

## 6. 本地自动点击器（不需要网络和 AI）

主页 → 脚本选择 → 开始执行；或悬浮球 → 启动脚本。
脚本制作：脚本页 → 新建 → 「选点添加」（点哪记哪）→ 调整顺序/延迟/轮次 → 保存。

## 7. 常见问题

| 现象 | 处理 |
|---|---|
| 无障碍开关灰色不可开 | Android 13+ 受限设置：应用信息 → 允许受限设置 |
| 服务一会儿就掉 | 电池白名单 + 自启动允许；锁屏后部分 ROM 仍可能冻结，可加锁后台 |
| 命令报"元素已失效" | 页面刚变过，先发 getElements 用新编号 |
| 银行/支付类 App 读不到内容 | 系统强制（FLAG_SECURE + 无障碍检测），无法绕过，也不应绕过 |
| 远程显示"离线" | 检查中转进程 `node relay.js` 是否存活、安全组是否放行、手机网络 |
| 截图失败 | 手机重启后录屏授权失效，需重新授权 |
| LLM 一直解析不出命令 | 换支持指令跟随更好的模型；或看日志里模型原始回复调整提示词 |

## 8. 安全须知

- 中转 Token 请保密； possession of token = 完全控制手机
- LLM API Key 存在手机本地 SharedPreferences，不上传
- 快照文本会发送给所选 LLM/服务器；密码框内容已强制隐藏，但仍建议不把手机交给任务期间涉及敏感页面的自动化
