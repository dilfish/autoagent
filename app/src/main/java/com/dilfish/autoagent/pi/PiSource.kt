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
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * B4 pi agent 对接：SSH 到服务器，调用 `pi -p --no-session --no-tools`，
 * 把任务+节点树快照+历史通过 stdin 发给 pi，从回复解析 JSON 命令块。
 * 模型/Provider 复用服务器上 pi 的配置。每步一次 SSH exec（v1 权衡，见 docs/DESIGN.md）。
 */
class PiSource(
    private val appContext: Context,
    private val historyLimit: Int = 6,
) : CommandSource {

    override val id = "pi"
    override val label = "pi"

    private var ssh: SSHClient? = null

    private fun binPath() = AppSettings.piBinPath(appContext)

    private fun cmdPrefix(): String {
        val bin = binPath()
        val dir = bin.substringBeforeLast('/')
        // pi 是脚本，依赖 node；服务器 PATH 只在 .zshrc，非交互 SSH 必须手动补
        return "export PATH=\"$dir:\$PATH\"; \"$bin\""
    }

    override suspend fun start(task: TaskContext) {
        withContext(Dispatchers.IO) {
            try {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            } catch (_: Exception) {
            }
            val host = AppSettings.piHost(appContext)
            if (host.isEmpty()) throw RuntimeException("请先在设置里配置 pi 服务器")
            val client = SSHClient(DefaultConfig())
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect(host, AppSettings.piPort(appContext))
            client.authPassword(
                AppSettings.piUser(appContext),
                AppSettings.piPassword(appContext),
            )
            ssh = client
            AgentBus.log("已连接 pi 服务器 $host")
        }
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
        val reply = withContext(Dispatchers.IO) { execPi(payload) }
        AgentBus.log("pi: ${reply.take(200)}")
        return ReplyParser.parse(reply)
            ?: throw RuntimeException("无法从 pi 回复解析命令")
    }

    private fun execPi(payload: String): String {
        val client = ssh ?: throw RuntimeException("SSH 未连接")
        val session = client.startSession()
        try {
            val cmd = session.exec("${cmdPrefix()} -p --no-session --no-tools")
            cmd.outputStream.write(payload.toByteArray())
            cmd.outputStream.flush()
            cmd.outputStream.close()
            val out = IOUtils.readFully(cmd.inputStream).toString()
            val err = try {
                IOUtils.readFully(cmd.errorStream).toString()
            } catch (_: Exception) {
                ""
            }
            cmd.join(120, TimeUnit.SECONDS)
            if (out.isBlank() && err.isNotBlank()) throw RuntimeException("pi 执行失败: ${err.take(300)}")
            return out
        } finally {
            session.close()
        }
    }

    override fun stop() {
        try {
            ssh?.disconnect()
        } catch (_: Exception) {
        }
        ssh = null
    }

    private companion object {
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
