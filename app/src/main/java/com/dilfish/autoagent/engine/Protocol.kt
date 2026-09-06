package com.dilfish.autoagent.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A 与 B 之间的命令协议。
 * 所有命令串行执行，每条返回一个 CommandResult。
 */
@Serializable
sealed class Command {
    abstract val cmdId: Int

    @Serializable
    @SerialName("click")
    data class Click(
        override val cmdId: Int,
        val elementId: Int? = null,
        val x: Float? = null,
        val y: Float? = null,
    ) : Command()

    @Serializable
    @SerialName("longClick")
    data class LongClick(
        override val cmdId: Int,
        val elementId: Int? = null,
        val x: Float? = null,
        val y: Float? = null,
    ) : Command()

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        override val cmdId: Int,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long = 300,
    ) : Command()

    @Serializable
    @SerialName("inputText")
    data class InputText(
        override val cmdId: Int,
        val elementId: Int,
        val text: String,
    ) : Command()

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        override val cmdId: Int,
        val direction: String, // "up" | "down"
        val elementId: Int? = null,
    ) : Command()

    @Serializable
    @SerialName("globalAction")
    data class GlobalAction(
        override val cmdId: Int,
        val action: String, // "back" | "home" | "recents"
    ) : Command()

    @Serializable
    @SerialName("getElements")
    data class GetElements(override val cmdId: Int) : Command()

    @Serializable
    @SerialName("wait")
    data class Wait(override val cmdId: Int, val ms: Long) : Command()

    @Serializable
    @SerialName("done")
    data class Done(override val cmdId: Int, val summary: String = "") : Command()

    @Serializable
    @SerialName("fail")
    data class Fail(override val cmdId: Int, val reason: String = "") : Command()

    companion object {
        private val seq = java.util.concurrent.atomic.AtomicInteger(1)
        fun nextId(): Int = seq.getAndIncrement()
    }
}

@Serializable
data class CommandResult(
    val cmdId: Int,
    val ok: Boolean,
    val error: String? = null,
    val elements: String? = null,
)
