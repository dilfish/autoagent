package com.dilfish.autoagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

/** 大模型对话接口抽象：一种协议一个实现 */
interface LlmProvider {
    val label: String

    /** 返回模型回复文本；失败抛异常。messages 含 system 角色，由实现自行处理。 */
    suspend fun chat(messages: List<ChatMessage>): String
}

/** 三种主流协议的工厂 */
object LlmFactory {
    fun create(type: String, baseUrl: String, apiKey: String, model: String): LlmProvider = when (type) {
        "anthropic" -> AnthropicProvider(baseUrl, apiKey, model)
        "gemini" -> GeminiProvider(baseUrl, apiKey, model)
        else -> OpenAiCompatProvider(baseUrl, apiKey, model)
    }
}

abstract class BaseHttpProvider {
    protected val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    protected val json = Json { ignoreUnknownKeys = true }

    protected suspend fun post(url: String, headers: Map<String, String>, body: String): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
            for ((k, v) in headers) builder.header(k, v)
            val resp = http.newCall(builder.build()).execute()
            resp.use {
                val text = it.body?.string() ?: ""
                if (!it.isSuccessful) throw RuntimeException("LLM HTTP ${it.code}: ${text.take(300)}")
                json.parseToJsonElement(text).jsonObject
            }
        }
}

/** 1. OpenAI 兼容协议（DeepSeek/Qwen/Moonshot/qiniu 等绝大多数服务） */
class OpenAiCompatProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider, BaseHttpProvider() {

    override val label = "OpenAI 兼容"

    override suspend fun chat(messages: List<ChatMessage>): String {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JsonArray(messages.map {
                JsonObject(mapOf("role" to JsonPrimitive(it.role), "content" to JsonPrimitive(it.content)))
            }))
        }
        val resp = post("$baseUrl/chat/completions", mapOf("Authorization" to "Bearer $apiKey"), body.toString())
        return resp["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")
            ?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw RuntimeException("LLM 响应缺少 choices[0].message.content")
    }
}

/** 2. Anthropic 协议（Claude） */
class AnthropicProvider(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider, BaseHttpProvider() {

    override val label = "Anthropic"
    private val base = baseUrl.ifBlank { "https://api.anthropic.com" }

    override suspend fun chat(messages: List<ChatMessage>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val dialog = messages.filter { it.role != "system" }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 8192)
            put("temperature", 0.2)
            if (system.isNotBlank()) put("system", system)
            put("messages", JsonArray(dialog.map {
                JsonObject(mapOf("role" to JsonPrimitive(it.role), "content" to JsonPrimitive(it.content)))
            }))
        }
        val resp = post(
            "$base/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
        val parts = resp["content"]?.jsonArray ?: throw RuntimeException("Anthropic 响应缺少 content")
        val text = parts.mapNotNull { p ->
            val o = p.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "text") o["text"]?.jsonPrimitive?.content else null
        }.joinToString("")
        if (text.isBlank()) throw RuntimeException("Anthropic 响应无文本内容")
        return text
    }
}

/** 3. Google Gemini 协议 */
class GeminiProvider(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider, BaseHttpProvider() {

    override val label = "Gemini"
    private val base = baseUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }

    override suspend fun chat(messages: List<ChatMessage>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val dialog = messages.filter { it.role != "system" }.map {
            // Gemini 的助手角色叫 "model"
            if (it.role == "assistant") "model" to it.content else it.role to it.content
        }
        val body = buildJsonObject {
            if (system.isNotBlank()) {
                put("system_instruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
                })
            }
            put("contents", JsonArray(dialog.map { (role, content) ->
                buildJsonObject {
                    put("role", role)
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", content) }) })
                }
            }))
            put("generationConfig", buildJsonObject { put("temperature", 0.2) })
        }
        val resp = post(
            "$base/models/$model:generateContent?key=$apiKey",
            emptyMap(),
            body.toString(),
        )
        return resp["candidates"]?.jsonArray?.get(0)?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { p -> p.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("Gemini 响应缺少文本")
    }
}
