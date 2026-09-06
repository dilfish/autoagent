package com.dilfish.autoagent.pi

import android.content.Context
import com.dilfish.autoagent.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 调试后端：把 payload POST 给桌面 pi-stub，不碰 SSH/真 pi。
 * 超时与 SshCliBackend 对齐（120s），失败抛异常由 TaskRunner 接住。
 */
class HttpStubBackend(
    appContext: Context,
    stubUrl: String = AppSettings.piStubUrl(appContext),
) : PiBackend {

    private val url: String = stubUrl.trim().removeSuffix("/")

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StubRequest(val payload: String)

    override suspend fun start() {
        if (url.isEmpty()) throw RuntimeException("请先在设置里填写 pi-stub 地址")
    }

    override suspend fun exec(payload: String): String =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                StubRequest.serializer(),
                StubRequest(payload),
            ).toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$url/v1/next").post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw RuntimeException("pi-stub HTTP ${resp.code}: ${text.take(300)}")
                if (text.isBlank()) throw RuntimeException("pi-stub 返回空响应")
                val obj = json.parseToJsonElement(text).jsonObject
                obj["reply"]?.jsonPrimitive?.content
                    ?: throw RuntimeException("pi-stub 响应缺少 reply 字段")
            }
        }

    override fun stop() {
    }
}
