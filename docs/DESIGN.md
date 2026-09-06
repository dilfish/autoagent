# AutoAgent 设计文档

> 装在家人手机上的「手机 GUI Agent」：本地自动点击/输入、AI 自主执行任务、远程人工指挥，三合一。
> **感知只依赖无障碍节点树（纯文本），不使用截图、不依赖大模型读图能力。**

## 1. 总体架构

```
┌────────────────────────────────────────────────────────────┐
│ 模块 A  感知/执行引擎（手机端本地能力，AccessibilityService）      │
│   感知: 节点树 → 带编号可读文本快照                                │
│   执行: click/longClick/inputText/swipe/scroll/back/home/...   │
├────────────────────────────────────────────────────────────┤
│ 模块 B  命令生成器（统一 CommandSource 接口，可插拔的"大脑"）       │
│   B1 ScriptSource   手动脚本（纯本地连点器）                      │
│   B2 ConsoleSource  指令控制台（人工逐条下发）                    │
│   B3 LlmSource      内置 LLM（OpenAI 兼容 API，纯文本）           │
│   B4 PiSource       pi agent（SSH → ddeb → pi -p）              │
│   B5 RemoteClient   远程人工指挥（WebSocket → ddeb 中转 → 浏览器） │
├────────────────────────────────────────────────────────────┤
│ 模块 C  UI（Compose：主页 / 脚本 / 控制台 / 设置 + 悬浮球）        │
│ 模块 D  可选按需单帧截图（MediaProjection，默认关闭）              │
└────────────────────────────────────────────────────────────┘
```

**解耦原则**：A 与 B 之间只通过 JSON 命令协议通信。任何"大脑"（含人工、AI）都不接触
AccessibilityService 细节；新增一种大脑只需实现 `CommandSource` 接口。

## 2. 模块 A：感知/执行引擎

### 2.1 感知：节点树快照

`ClickAccessibilityService.refreshSnapshot()` 遍历无障碍节点树，由
`NodeTreeSerializer` 序列化为带编号的缩进文本（GKD 同源数据，面向人和 LLM 阅读）：

```
包名: com.example  元素数: 42
[01] Frame id=android:id/content (0,0,1080,2400)
  [02] EditText "请输入手机号" id=com.app:id/phone 输入 (540,900)
  [03] Button "下一步" id=com.app:id/next 点 (540,1100)
  [04] TextView "忘记密码？" 点 (700,1250)
```

- **elementId**：快照内全局编号（1..400），命令按编号引用元素
- 输出字段：类型（短类名）、text、contentDescription、resource-id、
  可交互标记（点/长按/输入/滚动/勾选/密码框）、bounds 中心坐标
- 过滤规则：仅输出"可交互或有文本/描述"的节点，纯布局节点跳过（子节点仍会遍历）
- **密码框**：`isPassword` 的节点不输出文本内容（标记"密码框(内容隐藏)"）
- 触发时机：窗口/内容变化事件（350ms 防抖）、`getElements` 命令、服务连接时
- 快照同时发布到 `AgentBus.snapshot`（StateFlow），UI/远程/LLM 共享

### 2.2 执行：命令协议（A/B 间契约）

定义于 `engine/Protocol.kt`（kotlinx-serialization 多态，`type` 为判别字段）：

| type | 参数 | 实现 |
|---|---|---|
| click / longClick | elementId 或 x,y | 元素可点时 ACTION_CLICK，否则 dispatchGesture（50ms/600ms） |
| swipe | x1,y1,x2,y2,durationMs | dispatchGesture |
| inputText | elementId, text | 优先 ACTION_SET_TEXT；失败回退：聚焦+剪贴板+长按→点"粘贴"菜单 |
| scroll | direction up/down, elementId? | 屏幕或元素范围内手势滑动（35% 高度） |
| globalAction | back / home / recents | performGlobalAction |
| getElements | — | 重新抓取快照，结果附在 `elements` 字段 |
| wait | ms | 延时（上限 60s） |
| done / fail | summary / reason | 任务结束标记 |

每条命令返回 `{cmdId, ok, error?, elements?}`；引用的 elementId 失效时报错
"元素已失效，请先 getElements"。

### 2.3 执行循环：TaskRunner

`engine/TaskRunner`（object，单例）驱动所有 CommandSource：

```
start(source, task):
  循环（步数上限 60；unlimited 来源不受限）:
    快照 = AgentBus.snapshot
    commands = source.nextStep(快照, history)     // 生成器决策
    串行执行每条命令，结果写入 history 并打日志
    遇 done/fail/空列表/手动停止 → 结束
  finally: source.stop()
```

- 交互式来源（`unlimited=true`，如控制台）空轮询视为继续等待
- 运行状态/进度通过 StateFlow 暴露给 UI；全部活动进 `AgentBus.logs`（环形 500 条）

## 3. 模块 B：命令生成器

### B1 ScriptSource（手动脚本，纯本地）
- 数据模型：`Script{id, name, steps[], rounds}`；`ScriptStep{x, y, action, postDelayMs, repeat}`
- rounds=0 表示无限循环；悬浮球/App 内启动，经 TaskRunner 执行
- 持久化：SharedPreferences + JSON（`script/Script.kt` ScriptStore），支持多脚本
- 选点模式：服务全屏 TYPE_ACCESSIBILITY_OVERLAY 覆盖层点哪记哪，坐标追加进编辑器

### B2 ConsoleSource（指令控制台）
- 控制台页输入一行命令 → `ConsoleParser.parse` → 队列 → TaskRunner 执行
- 语法：`click [03]`、`click 540 1200`、`inputText [02] 你好`、`swipe x1 y1 x2 y2`、
  `scroll up`、`back/home/recents`、`wait 1000`、`getElements`、`done/fail`、原始 JSON
- 用于调试协议与人工单步操作

### B3 LlmSource（内置 LLM）✅ 支持三种接口范式
- 协议抽象 `ai/LlmProvider`，设置页下拉选择（用户确认的三种，不含 Gemini）：
  1. **OpenAI Chat Completions**（默认）：`POST {base_url}/chat/completions`，Bearer 认证 —— 事实标准，DeepSeek/Qwen/Moonshot/qiniu 等绝大多数服务
  2. **OpenAI Responses**：`POST {base_url}/responses`，system 放 `instructions` 字段，输入是 `input` 数组，文本从 `output[]` 的 message/output_text 提取，须带 `max_output_tokens`
  3. **Anthropic Messages**：`POST {base}/v1/messages`，`x-api-key` + `anthropic-version` 头，system 独立于 messages，必须带 `max_tokens`
- Anthropic 的 Base URL 留空用官方端点；OpenAI 系必须填（拼 /chat/completions 或 /responses）
- system prompt：命令协议说明书 + 规则（元素编号易变、失败自纠、禁操作支付页等）
- user 消息：当前快照 + 最近 6 步执行结果（命令→成功/失败）
- 回复解析 `ai/ReplyParser`：容忍 ```json 围栏 / 裸数组 / 夹带文本（括号配对扫描）；
  含"异形命令归一化"容错（action→type、tap→click、id→elementId）
- 防失控：步数上限 60；模型输出解析失败则任务终止并记录日志

### B4 PiSource（pi agent 对接）✅ 已实现
- sshj 连服务器（host/port/user/password 可配置），exec：
  `export PATH=<pi目录>:$PATH; "<pi路径>" -p --no-session --no-tools`
- 载荷（stdin）：协议说明 + 快照 + 近期结果 → stdout 回复 → ReplyParser 解析
- `--no-tools` 确保只输出命令不做操作；模型/Provider 复用服务器 pi 已有的模型配置
- 已在 ddeb 实测：完整协议提示词下 pi 严格输出 `[{"type":"click","elementId":3}]` 格式；
  ReplyParser 另含"异形命令归一化"容错（action→type、tap→click、id→elementId）
- 注意：pi 是脚本且依赖 node，服务器 PATH 只在 .zshrc，命令里必须先 export PATH
- 每步一次 SSH exec，实现简单、无长连接协议负担（v1 权衡；后续可改 `pi --mode rpc` 长会话）
- UI：设置页 pi 服务器配置卡；主页"大脑"下拉切换 内置 LLM / pi agent

### B5 RemoteClient（远程人工指挥）
- 手机端 OkHttp WebSocket **主动外连** ddeb 中转（无 NAT 问题），2s→30s 指数退避重连
- 推送：hello（设备名）、每次快照更新（snapshot）
- 接收：command（交给引擎执行并回传 result）、screenshotRequest（模块 D 抓单帧回传）
- 操作端：浏览器打开中转服务的控制页（见部署文档）

## 4. 模块 C：UI（Compose）

| 页面 | 内容 |
|---|---|
| 主页 | 无障碍状态卡（跳设置）、脚本选择+启动/停止、AI 任务卡（自然语言+大脑切换）、实时日志 |
| 脚本 | 新建/保存/删除、名称与轮次、步骤列表（上移/下移/删除）、选点添加、手动添加 |
| 控制台 | 命令输入+发送+停止、语法提示、实时日志 |
| 设置 | LLM API、pi 服务器、远程中转、截图授权、无障碍开启引导（含 Android 13+ 受限设置）、电池白名单 |

**悬浮球**（`FloatingBallManager`）：TYPE_ACCESSIBILITY_OVERLAY（无障碍专属窗口，
免悬浮窗权限），可拖动；点按展开「启动脚本/停止/选点/收起」。

## 5. 模块 D：按需单帧截图

- `ProjectionService`（前台服务，foregroundServiceType=mediaProjection）持有 MediaProjection
- `ScreenCapture.capture()`：**每次请求**临时创建 VirtualDisplay+ImageReader，抓一帧
  → JPEG(55) → base64，用完立即释放；不做持续推流（省电、无性能影响）
- 授权：设置页「授权录屏」→ MediaProjection 系统弹窗 → 启动前台服务；重启后需重新授权

## 6. 关键技术决策

| 决策 | 理由 |
|---|---|
| 节点树而非截图做感知 | 零额外权限、省 token、对 LLM 更可读；Canvas/游戏场景不可用（已知取舍） |
| dispatchGesture 而非 ACTION_CLICK 为主 | 部分应用只响应真实触摸；可点元素先 ACTION_CLICK 再回退手势 |
| minSdk 24 | dispatchGesture 的最低要求 |
| 统一 TaskRunner 循环 | 人工/AI/脚本共用同一协议与日志，行为一致、便于调试 |
| 密码框不输出文本 | 快照会进入日志/LLM/远程通道，必须防泄露 |
| 中转服务自建 | 手机主动外连无 NAT 问题；数据不经第三方 |

## 7. 权限清单

| 权限 | 方式 | 用途 |
|---|---|---|
| 无障碍服务 | 手动在系统设置开启 | 感知+执行（唯一必须项） |
| INTERNET | 自动授予 | LLM/pi/远程中转 |
| MediaProjection | 使用截图功能时弹窗授权 | 按需单帧截图 |
| 前台服务+通知 | 自动授予 | 维持录屏授权 |

悬浮球用无障碍专属窗口类型，**不需要**悬浮窗权限；无 root。

**系统级限制（无法绕过）**：银行/支付密码框与 FLAG_SECURE 界面读不到；
部分银行 App 检测到无障碍会拒绝运行；指纹/人脸必须本人。

## 8. 代码结构

```
app/src/main/java/com/dilfish/autoagent/
├── accessibility/   ClickAccessibilityService（引擎）、FloatingBallManager
├── ai/              LlmClient、LlmSource、ReplyParser        (B3)
├── console/         ConsoleParser、ConsoleSource、ConsoleSession (B2)
├── engine/          Protocol、CommandSource、TaskRunner、AgentBus、NodeTree
├── pi/              PiSource                                  (B4)
├── remote/          RemoteClient、RemoteManager               (B5)
├── script/          Script/ScriptStore、ScriptSource          (B1)
├── settings/        AppSettings
├── shot/            ProjectionService、ScreenCapture          (D)
└── ui/              MainActivity（四个页面）、EditorState
server/              relay.js + public/index.html（ddeb 中转 + Web 控制页）
```
