package com.lyuan.cc.ui

import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 按对话保存终端会话。切换对话时只把 TerminalView 重新绑定到对应会话，
 * 不结束进程，因此每个对话保有独立的 PTY、进程与屏幕内容。
 */
object TerminalSessions {

    class Handle(val conversationId: String, val specKey: String) {
        @Volatile var session: TerminalSession? = null
        @Volatile var viewClient: TerminalViewClient? = null
        @Volatile var view: TerminalView? = null
        @Volatile var onTap: () -> Unit = {}
        @Volatile var onFinished: () -> Unit = {}
        @Volatile var shiftDown: () -> Boolean = { false }
        @Volatile var ctrlDown: () -> Boolean = { false }
        @Volatile var altDown: () -> Boolean = { false }
    }

    private val handles = LinkedHashMap<String, Handle>()

    /** 工作区 / 服务商 / 模型任一变化都需要重建该对话的会话。 */
    fun specKey(spec: SessionSpec): String = buildString {
        append(spec.workspace).append('\u0000')
        append(spec.baseUrl).append('\u0000')
        append(spec.modelId).append('\u0000')
        append(spec.smallModelId).append('\u0000')
        append(spec.effort.orEmpty()).append('\u0000')
        append(spec.token.hashCode())
    }

    @Synchronized
    fun get(conversationId: String): Handle? = handles[conversationId]

    @Synchronized
    fun put(handle: Handle) {
        val old = handles.put(handle.conversationId, handle)
        if (old != null && old !== handle) finish(old)
    }

    @Synchronized
    fun close(conversationId: String) {
        handles.remove(conversationId)?.let(::finish)
    }

    /** TerminalView 被销毁或切换到别的对话时，解除引用，避免回调打到旧视图。 */
    @Synchronized
    fun detachView(view: TerminalView) {
        handles.values.forEach { if (it.view === view) it.view = null }
    }

    private fun finish(handle: Handle) {
        handle.view = null
        handle.session?.finishIfRunning()
        handle.session = null
    }
}