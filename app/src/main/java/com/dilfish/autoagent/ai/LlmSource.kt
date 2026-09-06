package com.dilfish.autoagent.ai

import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandSource
import com.dilfish.autoagent.engine.NodeTreeSnapshot
import com.dilfish.autoagent.engine.StepResult
import com.dilfish.autoagent.engine.TaskContext

/**
 * B3 内置 LLM 命令生成器：支持三种主流协议（OpenAI 兼容 / Anthropic / Gemini），纯文本交互。
 */
class LlmSource(
    private val provider: LlmProvider,
    private val historyLimit: Int = 6,
) : CommandSource {

    override val id = "llm"
    override val label = "LLM"

    private val messages = ArrayList<ChatMessage>()

    override suspend fun start(task: TaskContext) {
        messages.clear()
        messages.add(ChatMessage("system", SYSTEM_PROMPT))
        messages.add(ChatMessage("user", "## 任务\n${task.description}\n\n请基于后续消息中的屏幕元素开始执行。"))
    }

    override suspend fun nextStep(
        screen: NodeTreeSnapshot?,
        history: List<StepResult>,
    ): List<Command> {
        val user = buildString {
            appendLine("## 当前屏幕元素")
            appendLine(screen?.text?.take(12_000) ?: "（空）")
            if (history.isNotEmpty()) {
                appendLine("## 最近执行结果")
                for (h in history.takeLast(historyLimit)) {
                    for ((cmd, res) in h.commands.zip(h.results)) {
                        if (cmd is Command.Done || cmd is Command.Fail) continue
                        appendLine("#${res.cmdId} ${brief(cmd)} → ${if (res.ok) "成功" else "失败: ${res.error}"}")
                    }
                }
            }
            appendLine("## 请输出下一步命令（只输出一个 JSON 数组，不要输出其他内容）")
        }
        messages.add(ChatMessage("user", user))

        val reply = try {
            provider.chat(messages)
        } catch (e: Exception) {
            AgentBus.log("LLM 请求失败: ${e.message}")
            throw e
        }
        messages.add(ChatMessage("assistant", reply))
        AgentBus.log("LLM: ${reply.take(200)}")

        val cmds = ReplyParser.parse(reply)
            ?: throw RuntimeException("无法从模型回复中解析命令，请检查模型输出格式")
        return cmds
    }

    override fun stop() {
        messages.clear()
    }

    private fun brief(cmd: Command): String = when (cmd) {
        is Command.Click -> "click(${cmd.elementId?.let { "[$it]" } ?: "${cmd.x},${cmd.y}"})"
        is Command.LongClick -> "longClick(${cmd.elementId?.let { "[$it]" } ?: "${cmd.x},${cmd.y}"})"
        is Command.Swipe -> "swipe(${cmd.x1},${cmd.y1}→${cmd.x2},${cmd.y2})"
        is Command.InputText -> "inputText([${cmd.elementId}], \"${cmd.text.take(30)}\")"
        is Command.Scroll -> "scroll(${cmd.direction})"
        is Command.GlobalAction -> cmd.action
        is Command.GetElements -> "getElements"
        is Command.Wait -> "wait(${cmd.ms})"
        is Command.Done -> "done"
        is Command.Fail -> "fail"
    }

    private companion object {
        val SYSTEM_PROMPT = """
你是安卓手机自动化助手。你通过"屏幕元素快照"感知屏幕——这是无障碍节点树的纯文本表示，每行一个元素：

[03] Button "下一步" id=com.app:id/next 点 (540,1100)

- [03] 是元素编号，引用它来执行命令
- 引号内是元素文本/描述，id= 是资源ID，末尾括号是元素中心坐标，"点/长按/输入/滚动/勾选"标记可交互性
- 密码框内容不会显示

你可以输出以下命令（JSON 格式）：
- {"type":"click","elementId":3}          按编号点击
- {"type":"click","x":540,"y":1100}       按坐标点击
- {"type":"longClick","elementId":3}      长按
- {"type":"inputText","elementId":2,"text":"你好"}  向输入框写文本
- {"type":"scroll","direction":"up"}      向上滚动（"down" 向下）
- {"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":800,"durationMs":300}  滑动
- {"type":"globalAction","action":"back"} 返回键（"home" 主屏、"recents" 最近任务）
- {"type":"wait","ms":1000}               等待
- {"type":"getElements"}                  重新获取屏幕元素
- {"type":"done","summary":"已完成"}      任务成功结束
- {"type":"fail","reason":"原因"}         任务无法完成

规则：
1. 每次回复只输出一个 JSON 数组，例如：[{"type":"click","elementId":3}]
2. 一次可以输出多条命令，它们会按顺序执行；页面会变化时优先单条执行并在下一步观察新快照
3. 点击后页面若需要刷新感知，可输出 [{"type":"wait","ms":800},{"type":"getElements"}]
4. 之前的执行结果会在"最近执行结果"里给出，失败时请调整策略（比如改用坐标点击）
5. 元素编号在每次快照后都会变化，必须使用最新快照里的编号
6. 任务完成输出 done，确定无法完成输出 fail；不要凭空编造屏幕上不存在的元素
7. 绝不操作银行/支付类页面；遇到验证码、支付密码等必须输出 fail 并说明

示例：
用户消息给出快照后，你回复：
[{"type":"click","elementId":5},{"type":"wait","ms":800},{"type":"getElements"}]
        """.trimIndent()
    }
}
