package com.dilfish.autoagent.console

import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.NodeTreeSnapshot

/**
 * B2 指令控制台：把用户输入的一行命令解析成协议命令。
 * 支持：click/longClick [编号] 或 x y、inputText [编号] 文本、swipe x1 y1 x2 y2、
 * scroll up/down [编号]、back/home/recents、wait 毫秒、getElements、done/fail、原始 JSON。
 */
object ConsoleParser {

    fun parse(input: String, snapshot: NodeTreeSnapshot?): Command? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            return try {
                rawJson(t)
            } catch (e: Exception) {
                AgentBus.log("JSON 解析失败: ${e.message}")
                null
            }
        }
        val id = ref(t, snapshot)
        val parts = t.split(Regex("\\s+"))
        val head = parts.firstOrNull()?.lowercase() ?: return null
        val cmdId = Command.nextId()

        return when {
            head == "click" || head == "longclick" -> {
                val long = head == "longclick"
                val nums = t.removePrefix(parts[0]).trim().split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                when {
                    id != null ->
                        if (long) Command.LongClick(cmdId, elementId = id.second)
                        else Command.Click(cmdId, elementId = id.second)
                    nums.size >= 2 -> {
                        val x = nums[0].toFloatOrNull()
                        val y = nums[1].toFloatOrNull()
                        if (x == null || y == null) null
                        else if (long) Command.LongClick(cmdId, x = x, y = y)
                        else Command.Click(cmdId, x = x, y = y)
                    }
                    else -> null
                }
            }
            head == "inputtext" || head == "input" -> {
                val m = Regex("^\\S+\\s*\\[(\\d+)]\\s*(.*)$", RegexOption.IGNORE_CASE).find(t) ?: return null
                Command.InputText(cmdId, elementId = m.groupValues[1].toInt(), text = m.groupValues[2])
            }
            head == "swipe" && parts.size >= 5 -> {
                val xs = parts.drop(1).mapNotNull { it.toFloatOrNull() }
                if (xs.size < 4) null
                else Command.Swipe(cmdId, xs[0], xs[1], xs[2], xs[3], parts.getOrNull(5)?.toLongOrNull() ?: 300)
            }
            head == "scroll" && parts.size >= 2 ->
                Command.Scroll(cmdId, direction = parts[1].lowercase(), elementId = parts.getOrNull(2)?.removeSurrounding("[", "]")?.toIntOrNull())
            head == "back" -> Command.GlobalAction(cmdId, "back")
            head == "home" -> Command.GlobalAction(cmdId, "home")
            head == "recents" -> Command.GlobalAction(cmdId, "recents")
            head == "wait" && parts.size >= 2 -> Command.Wait(cmdId, parts[1].toLongOrNull() ?: 500)
            head == "getelements" || head == "get_elements" || head == "refresh" -> Command.GetElements(cmdId)
            head == "done" -> Command.Done(cmdId, t.removePrefix(parts[0]).trim())
            head == "fail" -> Command.Fail(cmdId, t.removePrefix(parts[0]).trim())
            else -> null
        } ?: run {
            AgentBus.log("无法解析命令: $t")
            null
        }
    }

    /** 返回 (引用在原文中的位置, elementId) */
    private fun ref(t: String, snapshot: NodeTreeSnapshot?): Pair<Int, Int>? {
        val m = Regex("\\[(\\d+)]").find(t) ?: return null
        return m.range.first to m.groupValues[1].toInt()
    }

    private fun rawJson(t: String): Command {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString(Command.serializer(), t)
    }
}
