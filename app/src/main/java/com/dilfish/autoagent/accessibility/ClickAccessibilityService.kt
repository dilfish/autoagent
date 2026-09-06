package com.dilfish.autoagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.Command
import com.dilfish.autoagent.engine.CommandResult
import com.dilfish.autoagent.engine.NodeTreeSerializer
import com.dilfish.autoagent.engine.NodeTreeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 模块 A：感知/执行引擎。
 * 感知 = 节点树快照；执行 = 手势注入与节点操作。
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private val _serviceRunning = MutableStateFlow(false)
        val serviceRunning: MutableStateFlow<Boolean> = _serviceRunning

        var instance: ClickAccessibilityService? = null
            private set

        fun refreshNow() {
            instance?.refreshSnapshot()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var ballManager: FloatingBallManager? = null

    // 最近一次快照对应的节点引用，elementId -> node，供命令执行时定位
    private val nodeCache = HashMap<Int, AccessibilityNodeInfo>()

    @Volatile
    private var lastSnapshot: NodeTreeSnapshot? = null

    private val refreshRunnable = Runnable { refreshSnapshot() }

    // 选点模式
    private var pickCallback: ((Float, Float) -> Unit)? = null
    private val pickViews = ArrayList<View>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceRunning.value = true
        ballManager = FloatingBallManager(this).also { it.attach() }
        AgentBus.log("无障碍服务已连接")
        refreshSnapshot()
    }

    override fun onDestroy() {
        instance = null
        _serviceRunning.value = false
        AgentBus.log("无障碍服务已断开")
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _serviceRunning.value = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (pickCallback != null) return
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 350)
    }

    /** 重新抓取节点树快照并发布到 AgentBus */
    fun refreshSnapshot() {
        scope.launch {
            val root = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            nodeCache.clear()
            val snap = try {
                NodeTreeSerializer.serialize(root, nodeCache)
            } catch (_: Exception) {
                null
            }
            lastSnapshot = snap
            AgentBus.publishSnapshot(snap)
        }
    }

    fun currentSnapshotText(): String =
        lastSnapshot?.text ?: "（暂无快照，请切换窗口后重试）"

    // ---------- 命令执行 ----------

    suspend fun execute(cmd: Command): CommandResult = try {
        when (cmd) {
            is Command.Click -> doClick(cmd.cmdId, cmd.elementId, cmd.x, cmd.y, longPress = false)
            is Command.LongClick -> doClick(cmd.cmdId, cmd.elementId, cmd.x, cmd.y, longPress = true)
            is Command.InputText -> doInputText(cmd.cmdId, cmd.elementId, cmd.text)
            is Command.Swipe -> {
                val ok = gestureSwipe(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.durationMs)
                result(cmd.cmdId, ok, "手势注入失败")
            }
            is Command.Scroll -> doScroll(cmd)
            is Command.GlobalAction -> {
                val ga = when (cmd.action) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    else -> -1
                }
                if (ga < 0) CommandResult(cmd.cmdId, false, "未知全局动作: ${cmd.action}")
                else result(cmd.cmdId, performGlobalAction(ga), "全局动作失败")
            }
            is Command.GetElements -> {
                refreshSnapshot()
                delay(600)
                CommandResult(cmd.cmdId, true, elements = currentSnapshotText())
            }
            is Command.Wait -> {
                delay(cmd.ms.coerceIn(0, 60_000))
                CommandResult(cmd.cmdId, true)
            }
            is Command.Done, is Command.Fail -> CommandResult(cmd.cmdId, true)
        }
    } catch (t: Throwable) {
        CommandResult(cmd.cmdId, false, t.message ?: t.javaClass.simpleName)
    }

    private fun result(cmdId: Int, ok: Boolean, error: String? = null) =
        if (ok) CommandResult(cmdId, true) else CommandResult(cmdId, false, error ?: "操作失败")

    private suspend fun doClick(
        cmdId: Int,
        elementId: Int?,
        x: Float?,
        y: Float?,
        longPress: Boolean,
    ): CommandResult {
        val target = resolveTarget(elementId, x, y)
            ?: return CommandResult(cmdId, false, "找不到目标元素（元素已失效？请先 getElements）")
        val (cx, cy, node) = target
        if (!longPress && node != null && node.isClickable) {
            val ok = try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: IllegalStateException) {
                false
            }
            if (ok) return CommandResult(cmdId, true)
        }
        val duration = if (longPress) 600L else 50L
        val ok = gestureTap(cx, cy, duration)
        return result(cmdId, ok, "手势注入失败")
    }

    private suspend fun doInputText(cmdId: Int, elementId: Int, text: String): CommandResult {
        val target = resolveTarget(elementId, null, null)
            ?: return CommandResult(cmdId, false, "找不到元素 $elementId（元素已失效？请先 getElements）")
        val (cx, cy, node) = target
        if (node == null) return CommandResult(cmdId, false, "元素 $elementId 不可操作")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: IllegalStateException) {
            false
        }
        if (ok) return CommandResult(cmdId, true)
        return pasteFallback(cmdId, cx, cy, text)
    }

    private suspend fun pasteFallback(cmdId: Int, x: Float, y: Float, text: String): CommandResult {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            handler.post { cm.setPrimaryClip(ClipData.newPlainText("autoagent", text)) }
            delay(150)
            nodeAt(x, y)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(100)
            if (!gestureTap(x, y, 600L)) return CommandResult(cmdId, false, "长按失败")
            delay(800)
            val pasteNode = findPasteMenuNode()
                ?: return CommandResult(cmdId, false, "未找到粘贴菜单")
            pasteNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return CommandResult(cmdId, true)
        } catch (t: Throwable) {
            return CommandResult(cmdId, false, "粘贴回退失败: ${t.message}")
        }
    }

    private fun findPasteMenuNode(): AccessibilityNodeInfo? {
        val keywords = listOf("粘贴", "Paste")
        val windows = try {
            this.windows
        } catch (_: Exception) {
            null
        }
        val roots = (windows?.mapNotNull { it.root } ?: emptyList()) + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            for (kw in keywords) {
                for (n in root.findAccessibilityNodeInfosByText(kw)) {
                    if (n.isClickable) return n
                }
            }
        }
        return null
    }

    private suspend fun doScroll(cmd: Command.Scroll): CommandResult {
        val rect = Rect()
        if (cmd.elementId != null) {
            val node = nodeCache[cmd.elementId]
            if (node != null) {
                try {
                    node.getBoundsInScreen(rect)
                } catch (_: IllegalStateException) {
                    return CommandResult(cmd.cmdId, false, "元素 ${cmd.elementId} 已失效")
                }
            } else return CommandResult(cmd.cmdId, false, "元素 ${cmd.elementId} 已失效")
        } else {
            val dm = resources.displayMetrics
            rect.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val span = rect.height() * 0.35f
        val ok = if (cmd.direction == "up") {
            gestureSwipe(cx, cy - span / 2, cx, cy + span / 2, 300)
        } else {
            gestureSwipe(cx, cy + span / 2, cx, cy - span / 2, 300)
        }
        return result(cmd.cmdId, ok, "滑动失败")
    }

    private fun resolveTarget(
        elementId: Int?,
        x: Float?,
        y: Float?,
    ): Triple<Float, Float, AccessibilityNodeInfo?>? {
        if (elementId != null) {
            val node = nodeCache[elementId] ?: return null
            return try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                Triple(rect.exactCenterX(), rect.exactCenterY(), node)
            } catch (_: IllegalStateException) {
                null
            }
        }
        if (x != null && y != null) return Triple(x, y, nodeAt(x, y))
        return null
    }

    private fun nodeAt(x: Float, y: Float): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Exception) {
        null
    }

    // ---------- 手势 ----------

    private suspend fun gestureTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    private suspend fun gestureSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 10_000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = try {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(false)
                false
            }
            if (!ok && cont.isActive) cont.resume(false)
        }

    // ---------- 选点模式 ----------

    fun startPickPoints(onPick: (Float, Float) -> Unit) {
        if (pickCallback != null) return
        pickCallback = onPick
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        val overlay = View(this).apply {
            setBackgroundColor(0x26000000)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    pickCallback?.invoke(e.rawX, e.rawY)
                    addMarker(wm, e.rawX, e.rawY, density)
                }
                true
            }
        }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(overlay, overlayParams)
        pickViews.add(overlay)

        val done = TextView(this).apply {
            text = "完成选点"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xE04F7CFF.toInt())
                cornerRadius = 12 * density
            }
            setOnClickListener { stopPickPoints() }
        }
        val doneParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            (48 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (80 * density).toInt()
        }
        wm.addView(done, doneParams)
        pickViews.add(done)
        AgentBus.log("选点模式已开启：点击屏幕任意位置记录点击点")
    }

    private fun addMarker(wm: WindowManager, x: Float, y: Float, density: Float) {
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF4444.toInt())
            }
        }
        val size = (20 * density).toInt()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).toInt()
            this.y = (y - size / 2).toInt()
        }
        wm.addView(dot, p)
        pickViews.add(dot)
    }

    fun stopPickPoints() {
        pickCallback = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        for (v in pickViews) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        pickViews.clear()
        AgentBus.log("选点模式已结束")
    }

    fun isPicking(): Boolean = pickCallback != null
}
