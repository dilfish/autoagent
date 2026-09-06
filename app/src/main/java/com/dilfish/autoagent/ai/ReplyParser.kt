package com.dilfish.autoagent.ai

import com.dilfish.autoagent.engine.Command
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/** 从模型回复文本中解析协议命令（容忍 markdown 代码块与前后缀文本） */
object ReplyParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<Command>? {
        // 1. ```json ... ``` / ``` ... ``` 代码块
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(text)
        for (m in fenced) {
            decode(m.groupValues[1].trim())?.let { return it }
        }
        // 2. 裸 JSON：首个 [ 到其配对 ]，或首个 { 到配对 }
        decode(text.trim())?.let { return it }
        val arr = extractBracketed(text, '[', ']')
        if (arr != null) decode(arr)?.let { return it }
        val obj = extractBracketed(text, '{', '}')
        if (obj != null) decode(obj)?.let { return it }
        return null
    }

    private fun decode(s: String): List<Command>? = try {
        val element = normalize(json.parseToJsonElement(s))
        when {
            element is kotlinx.serialization.json.JsonArray ->
                json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Command.serializer()), element.jsonArray)
            else -> listOf(json.decodeFromJsonElement(Command.serializer(), element))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 模型偶尔不按协议输出（如 {"action":"tap","x":540}），归一化成协议格式：
     * action→type、tap→click、id→elementId 等。
     */
    private fun normalize(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        return when (e) {
            is kotlinx.serialization.json.JsonArray ->
                kotlinx.serialization.json.JsonArray(e.map { normalize(it) })
            is kotlinx.serialization.json.JsonObject -> {
                if (e.containsKey("type")) {
                    e
                } else {
                    val action = (e["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase()
                    if (action == null) {
                        e
                    } else {
                        val type = when (action) {
                            "tap", "click", "press" -> "click"
                            "longclick", "long_press", "longpress" -> "longClick"
                            "input", "type", "type_text", "inputtext", "settext" -> "inputText"
                            "scrollup", "scrolldown", "scroll" -> "scroll"
                            "swipe" -> "swipe"
                            "back", "home", "recents" -> "globalAction"
                            "wait", "sleep" -> "wait"
                            "done", "finish", "complete" -> "done"
                            "fail", "abort" -> "fail"
                            "getelements", "get_elements", "refresh", "snapshot" -> "getElements"
                            else -> null
                        }
                        if (type == null) {
                            e
                        } else {
                            val map = e.toMutableMap()
                            map.remove("action")
                            map["type"] = kotlinx.serialization.json.JsonPrimitive(type)
                            (e["id"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                                map.remove("id")
                                map.putIfAbsent("elementId", it)
                            }
                            kotlinx.serialization.json.JsonObject(map)
                        }
                    }
                }
            }
            else -> e
        }
    }

    private fun extractBracketed(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (esc) { esc = false; continue }
            if (inStr) {
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
