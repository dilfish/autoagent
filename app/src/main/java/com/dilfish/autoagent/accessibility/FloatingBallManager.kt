package com.dilfish.autoagent.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dilfish.autoagent.engine.AgentBus
import com.dilfish.autoagent.engine.TaskContext
import com.dilfish.autoagent.engine.TaskRunner
import com.dilfish.autoagent.script.ScriptSource
import com.dilfish.autoagent.script.ScriptStore
import com.dilfish.autoagent.ui.EditorState
import kotlin.math.abs

/**
 * 悬浮球：TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限。
 * 收起态是可拖动小球，点按展开控制面板（启动脚本/停止/选点/收起）。
 */
class FloatingBallManager(private val service: ClickAccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val touchSlop = 12 * density

    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        val size = (52 * density).toInt()
        val ball = TextView(service).apply {
            text = "启"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC4F7CFF.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20 * density.toInt()
            y = 200 * density.toInt()
        }

        ball.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > touchSlop || abs(e.rawY - downY) > touchSlop) {
                        dragging = true
                        params.x += (e.rawX - downX).toInt()
                        params.y += (e.rawY - downY).toInt()
                        downX = e.rawX
                        downY = e.rawY
                        try {
                            wm.updateViewLayout(ball, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePanel()
                    true
                }
                else -> false
            }
        }

        wm.addView(ball, params)
        ballView = ball
        ballParams = params
    }

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val bp = ballParams ?: return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bp.x
            y = bp.y + 60 * density.toInt()
        }
        val pad = (10 * density).toInt()

        fun button(label: String, onClick: () -> Unit) = TextView(service).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            gravity = Gravity.CENTER
            setPadding(pad * 2, pad, pad * 2, pad)
            setOnClickListener { onClick() }
        }

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2FFFFFF.toInt())
                cornerRadius = 14 * density
            }
            addView(button("▶ 启动脚本") { startLastScript() })
            addView(button("■ 停止") {
                TaskRunner.stop()
                AgentBus.log("已请求停止任务")
            })
            addView(button("＋ 选点") {
                service.startPickPoints { x, y -> EditorState.addPoint(x, y) }
                removePanel()
            })
            addView(button("✕ 收起") { removePanel() })
        }

        wm.addView(container, p)
        panel = container
        panelParams = p
    }

    private fun startLastScript() {
        val ctx = service
        val lastId = ScriptStore.lastSelected(ctx)
        val script = ScriptStore.loadAll(ctx).firstOrNull { it.id == lastId }
            ?: ScriptStore.loadAll(ctx).firstOrNull()
        if (script == null) {
            AgentBus.log("没有可用脚本，请先在 App 里创建")
        } else {
            TaskRunner.start(ScriptSource(script), TaskContext(script.name))
        }
        removePanel()
    }

    private fun removePanel() {
        panel?.let { p ->
            try {
                wm.removeView(p)
            } catch (_: Exception) {
            }
        }
        panel = null
        panelParams = null
    }

    fun detach() {
        removePanel()
        ballView?.let { b ->
            try {
                wm.removeView(b)
            } catch (_: Exception) {
            }
        }
        ballView = null
        ballParams = null
    }
}
