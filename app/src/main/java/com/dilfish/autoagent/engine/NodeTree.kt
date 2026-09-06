package com.dilfish.autoagent.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class NodeEntry(
    val id: Int,
    val depth: Int,
    val cls: String,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val isPassword: Boolean,
    val cx: Int,
    val cy: Int,
)

data class NodeTreeSnapshot(
    val packageName: String?,
    val time: Long,
    val entries: List<NodeEntry>,
    val text: String,
) {
    fun find(id: Int): NodeEntry? = entries.firstOrNull { it.id == id }
}

/**
 * 把无障碍节点树序列化成带编号、可读性好的纯文本快照。
 * 编号即 elementId，后续命令（click/inputText 等）按编号引用元素。
 * 密码框内容一律不输出。
 * 可传入 nodeCache，在遍历的同时记录 elementId -> AccessibilityNodeInfo，供命令执行定位。
 */
object NodeTreeSerializer {

    private const val MAX_NODES = 400
    private const val MAX_TEXT = 60

    fun serialize(
        root: AccessibilityNodeInfo?,
        nodeCache: MutableMap<Int, AccessibilityNodeInfo>? = null,
    ): NodeTreeSnapshot? {
        if (root == null) return null
        val entries = ArrayList<NodeEntry>()
        try {
            traverse(root, 0, entries, nodeCache)
        } catch (_: Exception) {
            // 树在遍历过程中可能失效，返回已收集的部分
        }
        val sb = StringBuilder()
        sb.appendLine("包名: ${root.packageName ?: "?"}  元素数: ${entries.size}")
        for (e in entries) sb.appendLine(format(e))
        return NodeTreeSnapshot(
            packageName = root.packageName?.toString(),
            time = System.currentTimeMillis(),
            entries = entries,
            text = sb.toString(),
        )
    }

    fun format(e: NodeEntry): String {
        val indent = "  ".repeat(e.depth.coerceAtMost(12))
        val body = StringBuilder()
        body.append("[${"%02d".format(e.id)}] ").append(e.cls.substringAfterLast('.'))
        e.text?.let { body.append(" \"${it.replace("\n", " ")}\"") }
        e.desc?.let { body.append(" 描述=\"${it.replace("\n", " ")}\"") }
        e.resId?.let { body.append(" id=$it") }
        val flags = buildList {
            if (e.isPassword) add("密码框(内容隐藏)")
            if (e.clickable) add("点")
            if (e.longClickable) add("长按")
            if (e.editable) add("输入")
            if (e.scrollable) add("滚动")
            if (e.checkable) add("勾选")
        }
        if (flags.isNotEmpty()) body.append(' ').append(flags.joinToString("+"))
        body.append(" (${e.cx},${e.cy})")
        return indent + body
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<NodeEntry>,
        cache: MutableMap<Int, AccessibilityNodeInfo>?,
    ) {
        if (out.size >= MAX_NODES) return
        val interactive = node.isClickable || node.isLongClickable ||
            node.isEditable || node.isScrollable || node.isCheckable
        val text = node.text?.toString()?.take(MAX_TEXT)
        val desc = node.contentDescription?.toString()?.take(MAX_TEXT)
        if (interactive || !text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val id = out.size + 1
            out.add(
                NodeEntry(
                    id = id,
                    depth = depth,
                    cls = node.className?.toString() ?: "View",
                    text = if (node.isPassword) null else text,
                    desc = if (node.isPassword) null else desc,
                    resId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    isPassword = node.isPassword,
                    cx = rect.centerX(),
                    cy = rect.centerY(),
                ),
            )
            cache?.put(id, node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, depth + 1, out, cache)
        }
    }
}
