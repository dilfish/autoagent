package com.dilfish.autoagent.pi

import android.content.Context
import com.dilfish.autoagent.ai.ReplyParser
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandSource
import com.dilfish.autoagent.engine.NodeTreeSnapshot
import com.dilfish.autoagent.engine.StepResult
import com.dilfish.autoagent.engine.TaskContext
import com.dilfish.autoagent.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B4 pi agent 对接：prompt 组装 + reply 解析走这里，
 * 具体执行交给 PiBackend（真 pi 经 SSH / 调试走桌面 stub）。
 * 每步 prompt/reply 落盘到 PiTrace，坏回复可事后回放复现。
 */
class PiSource(
    private val appContext: Context,
    private val historyLimit: Int = 6,
    private val backend: PiBackend = defaultBackend(appContext),
) : CommandSource {

    override val id = "pi"
    override val label = "pi"

    private var traceDir: File? = null
    private var stepNo = 0

    /** 兼容老调用：MainActivity 原 `PiSource(ctx)` 不用改也能编译。 */
    constructor(appContext: Context) : this(appContext, 6, defaultBackend(appContext))

    override suspend fun start(task: TaskContext) {
        traceDir = PiTrace.newSessionDir(appContext.filesDir)
        stepNo = 0
        backend.start()
    }

    override suspend fun nextStep(
        screen: NodeTreeSnapshot?,
        history: List<StepResult>,
    ): List<Command> {
        val payload = buildString {
            appendLine(PROTOCOL_HINT)
            appendLine("## 当前屏幕元素")
            appendLine(screen?.text?.take(12_000) ?: "（空）")
            if (history.isNotEmpty()) {
                appendLine("## 最近执行结果")
                for (h in history.takeLast(historyLimit)) {
                    for ((cmd, res) in h.commands.zip(h.results)) {
                        if (cmd is Command.Done || cmd is Command.Fail) continue
                        appendLine("#${res.cmdId} → ${if (res.ok) "成功" else "失败: ${res.error}"}")
                    }
                }
            }
            appendLine("请只输出一个 JSON 命令数组。")
        }
        stepNo++
        val dir = traceDir ?: PiTrace.newSessionDir(appContext.filesDir).also { traceDir = it }
        val backendId = backend.javaClass.simpleName
        val reply = try {
            withContext(Dispatchers.IO) { backend.exec(payload) }
        } catch (t: Throwable) {
            PiTrace.record(dir, stepNo, payload, reply = null, error = (t.message ?: t.toString()), backendId)
            AgentBus.log("pi-trace: ${dir.name}/step%02d（失败）".format(stepNo))
            throw t
        }
        PiTrace.record(dir, stepNo, payload, reply = reply, error = null, backendId)
        AgentBus.log("pi: ${reply.take(200)}")
        AgentBus.log("pi-trace: ${dir.name}/step%02d".format(stepNo))
        return ReplyParser.parse(reply)
            ?: throw RuntimeException("无法从 pi 回复解析命令")
    }

    override fun stop() {
        backend.stop()
    }

    private companion object {
        fun defaultBackend(ctx: Context): PiBackend =
            if (AppSettings.piMode(ctx) == AppSettings.PI_MODE_STUB) {
                HttpStubBackend(ctx)
            } else {
                SshCliBackend(ctx)
            }

        val PROTOCOL_HINT = """
你是安卓手机自动化助手，通过无障碍节点树快照感知屏幕，每行一个元素：
[03] Button "下一步" id=com.app:id/next 点 (540,1100)
[编号] 引用元素；末尾括号是中心坐标；"点/长按/输入/滚动/勾选"是可交互标记；密码框不显示内容。

可用命令（JSON）：
{"type":"click","elementId":3} / {"type":"click","x":540,"y":1100}
{"type":"longClick","elementId":3}
{"type":"inputText","elementId":2,"text":"你好"}
{"type":"scroll","direction":"up"|"down"}
{"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":800,"durationMs":300}
{"type":"globalAction","action":"back"|"home"|"recents"}
{"type":"wait","ms":1000} / {"type":"getElements"}
{"type":"done","summary":"…"} / {"type":"fail","reason":"…"}

规则：只输出一个 JSON 数组；页面变化后用最新快照的编号；绝不操作银行/支付页面；无法完成输出 fail。
        """.trimIndent()
    }
}
