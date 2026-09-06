package com.dilfish.autoagent.pi

import kotlinx.serialization.Serializable

/** pi-stub 剧本：纯 Kotlin 数据模型，App 与 JVM 单测共用，stub 的 JS 端只读同一 JSON。 */
@Serializable
data class PiScriptStep(
    /** 本步 stub 返回的 reply 文本（期望是 JSON 命令数组；坏用例可故意写残缺文本） */
    val reply: String,
    /** 标记为坏用例时，单测期望 ReplyParser.parse(reply) 返回 null，而非抛错 */
    val expectParseFail: Boolean = false,
)

@Serializable
data class PiScript(
    val name: String = "",
    val steps: List<PiScriptStep> = emptyList(),
) {
    /** 超步后重复最后一步，保证 stub 永不返回空。 */
    fun replyFor(step: Int): PiScriptStep {
        require(steps.isNotEmpty()) { "剧本 steps 为空" }
        return steps[minOf(step, steps.size - 1)]
    }
}
