package com.dilfish.autoagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/** 接入协议的工厂：Chat Completions / Responses / Messages（pi agent 由独立的 PiSource 承担） */
object LlmFactory {
    fun create(type: String, baseUrl: String, apiKey: String, model: String): LlmProvider = when (type) {
        "responses" -> OpenAiResponsesProvider(baseUrl, apiKey, model)
        "anthropic" -> AnthropicProvider(baseUrl, apiKey, model)
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
            com.dilfish.autoagent.log.AppLog.d("llm", "HTTP POST $url bodyChars=${body.length}")
            val builder = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
            for ((k, v) in headers) builder.header(k, v)
            val t0 = System.currentTimeMillis()
            val resp = http.newCall(builder.build()).execute()
            resp.use {
                val text = it.body?.string() ?: ""
                com.dilfish.autoagent.log.AppLog.d(
                    "llm",
                    "HTTP ${it.code} ${System.currentTimeMillis() - t0}ms respChars=${text.length}",
                )
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

/** 2. OpenAI Responses 协议（/v1/responses，新一代有状态接口） */
class OpenAiResponsesProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider, BaseHttpProvider() {

    override val label = "OpenAI Responses"

    override suspend fun chat(messages: List<ChatMessage>): String {
        val instructions = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val input = messages.filter { it.role != "system" }.map {
            JsonObject(mapOf("role" to JsonPrimitive(it.role), "content" to JsonPrimitive(it.content)))
        }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("max_output_tokens", 8192)
            if (instructions.isNotBlank()) put("instructions", instructions)
            put("input", JsonArray(input))
        }
        val resp = post("$baseUrl/responses", mapOf("Authorization" to "Bearer $apiKey"), body.toString())
        val output = resp["output"]?.jsonArray ?: throw RuntimeException("Responses 响应缺少 output")
        val text = output.asSequence()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { it.jsonObject["content"]?.jsonArray ?: emptyList() }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        if (text.isBlank()) throw RuntimeException("Responses 响应无文本内容")
        return text
    }
}

/** 3. Anthropic Messages 协议（Claude） */
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
