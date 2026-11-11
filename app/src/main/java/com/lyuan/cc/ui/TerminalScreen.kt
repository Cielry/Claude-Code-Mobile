package com.lyuan.cc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.system.Os
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lyuan.cc.CcmOutput
import com.lyuan.cc.data.WorkspaceUtils
import com.lyuan.cc.runtime.RuntimeManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File

data class SessionSpec(
    val conversationId: String,
    val workspace: String,
    val baseUrl: String,
    val token: String,
    val modelId: String,
    val smallModelId: String,
    val effort: String?,
)

private class TerminalClient(
    private val appContext: Context,
    private val handle: TerminalSessions.Handle,
) : TerminalSessionClient, TerminalViewClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        handle.view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        handle.onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        if (text.isNotEmpty() && session != null) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }

    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) { handle.onTap() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = handle.ctrlDown()
    override fun readAltKey(): Boolean = handle.altDown()
    override fun readShiftKey(): Boolean = handle.shiftDown()
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
}

@Composable
fun TerminalScreen(
    runtime: RuntimeManager,
    spec: SessionSpec?,
    onOpenDrawer: () -> Unit,
    onSessionClosed: () -> Unit,
) {
    val context = LocalContext.current

    if (spec == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("还没有打开的对话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点下方 ≡ 打开对话列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenDrawer) { Text("打开对话列表") }
            }
        }
        return
    }

    val view = remember {
        TerminalView(context, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(android.graphics.Color.BLACK)
            setTextSize((12 * context.resources.displayMetrics.density).toInt())
        }
    }
    var shiftDown by remember { mutableStateOf(false) }
    var ctrlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<TerminalSessions.Handle?>(null) }
    var statusText by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }

    // 进程已结束（Termux 显示 "[Process completed - press Enter]"）时，
    // 按 Enter 关闭这次对话，与 Termux 行为一致；对话记录仍保留在列表里。
    LaunchedEffect(finished, spec.conversationId) {
        view.setOnKeyListener { _, keyCode, event ->
            if (finished && event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                TerminalSessions.close(spec.conversationId)
                onSessionClosed()
                true
            } else {
                false
            }
        }
    }

    DisposableEffect(view) {
        onDispose {
            hideKeyboard(view, context)
            TerminalSessions.detachView(view)
        }
    }

    LaunchedEffect(spec.conversationId, TerminalSessions.specKey(spec)) {
        TerminalSessions.detachView(view)
        finished = false
        statusText = ""
        val key = TerminalSessions.specKey(spec)
        val existing = TerminalSessions.get(spec.conversationId)
        if (existing != null && existing.specKey != key) {
            // 工作区 / 服务商 / 模型变了：这个对话需要重新启动。
            TerminalSessions.close(spec.conversationId)
        }
        var target = TerminalSessions.get(spec.conversationId)
        if (target == null) {
            val state = runtime.quickStatus()
            if (!state.ready) {
                statusText = "正在准备运行环境…"
                runtime.ensureInitialized { message -> statusText = message }
                if (!runtime.quickStatus().ready) {
                    statusText = "运行环境尚未就绪，请稍后重试或重启应用。"
                    return@LaunchedEffect
                }
            }
            val defaultDir = WorkspaceUtils.defaultDir(context)
            val workDir = spec.workspace.takeIf { WorkspaceUtils.isUsable(it) }
                ?: defaultDir.absolutePath
            if (workDir != spec.workspace) {
                Toast.makeText(context, "工作区不可访问，已改用默认工作区", Toast.LENGTH_LONG).show()
            }
            File(workDir).mkdirs()
            // 新工作区默认信任，跳过 Claude 的首次确认提示。
            runtime.ensureWorkspaceTrusted(workDir)
            ensurePrivateDir(runtime.tmpDir)
            // 保证新会话能读到随包的 settings.json / CLAUDE.md（配置版本变化时才重写）。
            runtime.ensureClaudeConfig()
            statusText = "正在启动 Claude Code…"
            val created = TerminalSessions.Handle(spec.conversationId, key)
            val client = TerminalClient(context, created)
            created.viewClient = client
            // TerminalSession 构造时会创建 Handler，必须在主线程（有 Looper）执行。
            val session = TerminalSession(
                runtime.loader.absolutePath,
                workDir,
                arrayOf(
                    runtime.loader.absolutePath,
                    "--library-path", runtime.libDir.absolutePath,
                    "--argv0", "claude",
                    runtime.binary.absolutePath,
                    "--permission-mode", "manual",
                ),
                buildEnv(runtime, spec, File(context.filesDir, "env"), CcmOutput.outputDir(context)),
                2000,
                client,
            )
            if (!currentCoroutineContext().isActive) {
                session.finishIfRunning()
                return@LaunchedEffect
            }
            created.session = session
            TerminalSessions.put(created)
            target = created
        }

        val active = target
        handle = active
        val session = active.session ?: return@LaunchedEffect
        active.view = view
        active.shiftDown = { shiftDown }
        active.ctrlDown = { ctrlDown }
        active.altDown = { altDown }
        active.onTap = {
            view.requestFocus()
            showKeyboard(view, context)
        }
        active.onFinished = {
            // 进程结束后由 Claude 显示“[Process completed - press Enter]”，
            // 按回车即可退出；不再额外显示「会话已结束」提示。
            view.post { finished = true }
        }
        if (!session.isRunning()) finished = true
        statusText = ""
        active.viewClient?.let { view.setTerminalViewClient(it) }
        view.attachSession(session)
        view.requestFocus()
        showKeyboard(view, context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                )
            }
        }
        KeyBar(
            shiftDown = shiftDown,
            ctrlDown = ctrlDown,
            altDown = altDown,
            onShiftToggle = { shiftDown = !shiftDown },
            onCtrlToggle = { ctrlDown = !ctrlDown },
            onAltToggle = { altDown = !altDown },
            onSend = { bytes ->
                if (finished && bytes.size == 1 && bytes[0] == 0x0a.toByte()) {
                    TerminalSessions.close(spec.conversationId)
                    onSessionClosed()
                } else {
                    handle?.session?.write(bytes, 0, bytes.size)
                }
            },
            onConversations = onOpenDrawer,
        )
    }
}

private fun showKeyboard(view: View, context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

private fun hideKeyboard(view: View, context: Context) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

/** CLI 的消息 socket 需要私有目录；/data 不可用，改用应用自己的 tmp。 */
private fun ensurePrivateDir(dir: File) {
    dir.mkdirs()
    runCatching { Os.chmod(dir.absolutePath, 0b111_000_000) } // 0700
}

private fun buildEnv(
    runtime: RuntimeManager,
    spec: SessionSpec,
    envDir: File,
    outputDir: File,
): Array<String> {
    val nativeDir = runtime.loader.parentFile?.absolutePath ?: ""
    val env = mutableListOf(
        "HOME=${runtime.homeDir.absolutePath}",
        "TMPDIR=${runtime.tmpDir.absolutePath}",
        // Claude Code 的跨会话消息 socket 需要私有目录，但 Android 的 /data 不属于应用
        // （属主 system:1000），路径校验必然失败；该功能在手机上用不到，直接关闭。
        "CLAUDE_CODE_HARBOR_KITE=0",
        // 关闭自动更新检查与提示；开发版固定使用已锁定的 Claude Code 版本。
        "DISABLE_AUTOUPDATER=1",
        "XDG_RUNTIME_DIR=${runtime.tmpDir.absolutePath}",
        "CLAUDE_CODE_TMPDIR=${runtime.tmpDir.absolutePath}",
        "PATH=${envDir.absolutePath}/bin:/system/bin",
        "CCM_ENV_DIR=${envDir.absolutePath}",
        "CCM_OUTPUT_DIR=${outputDir.absolutePath}",
        // 装了内置 Bash 包就用它；未安装时回落系统 sh
        "SHELL=" + (File(envDir, "bin/bash").takeIf { it.isFile }?.absolutePath
            ?: "/system/bin/sh"),
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TZ=Asia/Shanghai",
        "LD_LIBRARY_PATH=${runtime.libDir.absolutePath}",
        "CLAUDE_CODE_EXECPATH=$nativeDir/libclaude_exec.so",
        "CCM_LD=${runtime.loader.absolutePath}",
        "CCM_LIBDIR=${runtime.libDir.absolutePath}",
        "CCM_NATIVE=${runtime.binary.absolutePath}",
        "CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1",
    )
    if (spec.baseUrl.isNotBlank()) env += "ANTHROPIC_BASE_URL=${spec.baseUrl}"
    if (spec.token.isNotBlank()) {
        // 只用 Anthropic 兼容网关通用的 Bearer 鉴权。不设置 ANTHROPIC_API_KEY，
        // 避免 Claude Code 弹出「是否使用此 API Key」确认和双鉴权警告。
        env += "ANTHROPIC_AUTH_TOKEN=${spec.token}"
    }
    spec.effort?.takeIf { it.isNotBlank() }?.let { env += "CLAUDE_CODE_EFFORT_LEVEL=$it" }
    if (spec.modelId.isNotBlank()) {
        env += "ANTHROPIC_MODEL=${spec.modelId}"
        env += "ANTHROPIC_DEFAULT_OPUS_MODEL=${spec.modelId}"
        env += "ANTHROPIC_DEFAULT_SONNET_MODEL=${spec.modelId}"
        env += "ANTHROPIC_DEFAULT_HAIKU_MODEL=${spec.smallModelId.ifBlank { spec.modelId }}"
        env += "CLAUDE_CODE_SUBAGENT_MODEL=${spec.modelId}"
    }
    return env.toTypedArray()
}

@Composable
private fun KeyBar(
    shiftDown: Boolean,
    ctrlDown: Boolean,
    altDown: Boolean,
    onShiftToggle: () -> Unit,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onConversations: () -> Unit,
) {
    fun arrow(code: Char) {
        // xterm 修饰码：Shift=1、Alt=2、Ctrl=4，参数为 1 + 修饰位之和。
        val mod = 1 + (if (shiftDown) 1 else 0) + (if (altDown) 2 else 0) +
            (if (ctrlDown) 4 else 0)
        val seq = if (mod > 1) "\u001b[1;${mod}$code" else "\u001b[$code"
        onSend(seq.toByteArray())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton("esc", Modifier.weight(1f)) { onSend(byteArrayOf(0x1b)) }
            KeyButton("≡", Modifier.weight(1f), onClick = onConversations)
            KeyButton(
                if (altDown) "alt*" else "alt",
                Modifier.weight(1f),
                active = altDown,
                onClick = onAltToggle,
            )
            KeyButton("↑", Modifier.weight(1f)) { arrow('A') }
            KeyButton("end", Modifier.weight(1f)) { onSend("\u001b[F".toByteArray()) }
            KeyButton("换行", Modifier.weight(1f)) { onSend(byteArrayOf(0x0a)) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyButton(if (shiftDown) "shift*" else "shift", Modifier.weight(1f), active = shiftDown) {
                onShiftToggle()
            }
            KeyButton(if (ctrlDown) "ctrl*" else "ctrl", Modifier.weight(1f), active = ctrlDown) {
                onCtrlToggle()
            }
            KeyButton("←", Modifier.weight(1f)) { arrow('D') }
            KeyButton("↓", Modifier.weight(1f)) { arrow('B') }
            KeyButton("→", Modifier.weight(1f)) { arrow('C') }
            KeyButton("tab", Modifier.weight(1f)) {
                if (shiftDown) onSend("\u001b[Z".toByteArray()) else onSend(byteArrayOf(0x09))
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFFD97757) else Color.White,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}