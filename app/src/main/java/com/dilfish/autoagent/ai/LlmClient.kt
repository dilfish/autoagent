package com.dilfish.autoagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

/**
 * B3 内置 LLM：OpenAI 兼容 chat/completions 客户端（纯文本，无图像）。
 * baseUrl 形如 https://api.example.com/v1
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** 返回模型回复文本；失败抛异常 */
    suspend fun chat(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val bodyObj = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JsonArray(messages.map {
                JsonObject(mapOf("role" to kotlinx.serialization.json.JsonPrimitive(it.role), "content" to kotlinx.serialization.json.JsonPrimitive(it.content)))
            }))
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(bodyObj.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        resp.use {
            val text = it.body?.string() ?: ""
            if (!it.isSuccessful) throw RuntimeException("LLM HTTP ${it.code}: ${text.take(300)}")
            val obj = json.parseToJsonElement(text).jsonObject
            obj["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw RuntimeException("LLM 响应缺少 choices[0].message.content")
        }
    }
}
