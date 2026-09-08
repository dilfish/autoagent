package com.dilfish.autoagent.remote

import com.dilfish.autoagent.accessibility.ClickAccessibilityService
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandResult
import com.dilfish.autoagent.log.AppLog
import com.dilfish.autoagent.shot.ScreenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * B5 远程指挥（手机端）：主动外连 ddeb 中转服务。
 * 推送节点树快照，接收命令执行并回传结果，响应截图请求。
 */
class RemoteClient(private val wsUrl: String, private val token: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    @Volatile
    private var enabled = false

    @Volatile
    private var socket: WebSocket? = null

    val state = MutableStateFlow("未启动")
    private var snapshotJob: Job? = null

    fun start() {
        if (enabled) return
        enabled = true
        AppLog.i("remote", "start ws=$wsUrl")
        scope.launch {
            var backoff = 2_000L
            while (enabled) {
                state.value = "连接中…"
                AppLog.d("remote", "connecting… backoffWas=${backoff}ms")
                val opened = connectOnce()
                if (opened) {
                    backoff = 2_000L
                    AppLog.i("remote", "connected")
                    // 挂起直到连接断开
                    while (enabled && socket != null) delay(500)
                    AppLog.w("remote", "socket lost, will reconnect")
                }
                if (!enabled) break
                state.value = "断开，${backoff / 1000}s 后重连"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            state.value = "已停止"
            AppLog.i("remote", "stopped")
        }
    }

    private suspend fun connectOnce(): Boolean {
        val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val request = Request.Builder()
            .url("$wsUrl?token=$token")
            .build()
        var snapshotStarted = false
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state.value = "已连接"
                latch.complete(true)
                send(
                    buildJsonObject {
                        put("type", "hello")
                        put("device", android.os.Build.MODEL ?: "android")
                    },
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                state.value = "连接失败: ${t.message?.take(80)}"
                AppLog.e("remote", "onFailure http=${response?.code}", t)
                socket = null
                if (!latch.isCompleted) latch.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.d("remote", "onClosed code=$code reason=$reason")
                socket = null
                if (!latch.isCompleted) latch.complete(false)
            }
        })

        // 订阅快照推送
        val ok = latch.await()
        if (ok && !snapshotStarted) {
            snapshotStarted = true
            snapshotJob = scope.launch {
                AgentBus.snapshot.collect { snap ->
                    if (snap != null && socket != null) {
                        send(buildJsonObject { put("type", "snapshot"); put("text", snap.text.take(20_000)) })
                    }
                }
            }
        }
        return ok
    }

    private fun handleMessage(text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return
        }
        when (obj["type"]?.jsonPrimitive?.content) {
            "command" -> {
                val cmdElement = obj["cmd"] ?: return
                val cmd = try {
                    json.decodeFromJsonElement(Command.serializer(), cmdElement)
                } catch (e: Exception) {
                    AgentBus.log("远程命令解析失败: ${e.message}")
                    return
                }
                AgentBus.log("远程命令: $cmd")
                AppLog.d("remote", "cmd in: $cmd")
                scope.launch {
                    val svc = ClickAccessibilityService.instance
                    val res = svc?.execute(cmd)
                        ?: CommandResult(cmd.cmdId, false, "无障碍服务未开启")
                    AppLog.d("remote", "cmd out #${res.cmdId} ok=${res.ok} err=${res.error}")
                    send(
                        buildJsonObject {
                            put("type", "result")
                            put("cmdId", res.cmdId)
                            put("ok", res.ok)
                            res.error?.let { put("error", it) }
                        },
                    )
                }
            }
            "screenshotRequest" -> {
                AppLog.d("remote", "screenshotRequest")
                scope.launch {
                    val b64 = ScreenCapture.capture()
                    if (b64 != null) {
                        AppLog.d("remote", "screenshot ok bytes≈${b64.length * 3 / 4}")
                        send(buildJsonObject { put("type", "screenshot"); put("data", b64) })
                    } else {
                        AgentBus.log("截图失败（未授权录屏或超时）")
                        AppLog.w("remote", "screenshot failed")
                    }
                }
            }
        }
    }

    private fun send(obj: JsonObject) {
        try {
            socket?.send(obj.toString())
        } catch (_: Exception) {
        }
    }

    fun stop() {
        enabled = false
        snapshotJob?.cancel()
        try {
            socket?.close(1000, "bye")
        } catch (_: Exception) {
        }
        socket = null
    }
}

/** 远程连接全局入口 */
object RemoteManager {
    private var client: RemoteClient? = null
    private var collector: kotlinx.coroutines.Job? = null
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow("未启动")
    val state: MutableStateFlow<String> = _state

    fun start(wsUrl: String, token: String) {
        stop()
        val c = RemoteClient(wsUrl, token)
        client = c
        collector = syncScope.launch { c.state.collect { _state.value = it } }
        c.start()
    }

    fun stop() {
        collector?.cancel()
        collector = null
        client?.stop()
        client = null
        _state.value = "未启动"
    }
}
