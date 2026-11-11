package com.lyuan.cc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.lyuan.cc.data.AppDatabase
import com.lyuan.cc.data.ConversationEntity
import com.lyuan.cc.data.ProviderEntity
import com.lyuan.cc.data.ProviderRepository
import com.lyuan.cc.data.SettingsRepository
import com.lyuan.cc.data.Workspace
import com.lyuan.cc.data.WorkspaceUtils
import com.lyuan.cc.env.EnvManager
import com.lyuan.cc.runtime.RuntimeManager
import com.lyuan.cc.ui.ConfigScreen
import com.lyuan.cc.ui.EnvironmentScreen
import com.lyuan.cc.ui.ConversationDrawerContent
import com.lyuan.cc.ui.GlassNavBar
import com.lyuan.cc.ui.NewConversationDialog
import com.lyuan.cc.ui.OnboardingScreen
import com.lyuan.cc.ui.PermissionGate
import com.lyuan.cc.ui.SessionSpec
import com.lyuan.cc.ui.SettingsScreen
import com.lyuan.cc.ui.TerminalScreen
import com.lyuan.cc.ui.TerminalSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        val settings = SettingsRepository(applicationContext)
        val runtime = RuntimeManager(applicationContext)
        val providerRepo = ProviderRepository(applicationContext)
        val db = AppDatabase.get(applicationContext)
        val envManager = EnvManager(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { providerRepo.importLegacyIfNeeded(settings) }
            runCatching { settings.migrateLegacyWorkspace() }
            runCatching { envManager.initialize() }
            // 首次启动自动准备运行时（导入 claude-native），无需进入设置页手动初始化。
            runCatching { runtime.ensureInitialized() }
            // 运行时就绪后重新探测，环境页不必手动点「环境检测」。
            runCatching { envManager.runProbe() }
            // 启动时同步随包 Claude 配置（含 settings.json / CLAUDE.md / 脚本）。
            runCatching { runtime.ensureClaudeConfig() }
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF1A1A1A),
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val completed by produceState<Boolean?>(initialValue = null) {
                        settings.onboardingCompleted.collect { value = it }
                    }
                    val scope = rememberCoroutineScope()
                    when (completed) {
                        null -> Box(modifier = Modifier.fillMaxSize())
                        false -> OnboardingScreen(
                            onStart = {
                                scope.launch { settings.setOnboardingCompleted(true) }
                            },
                        )
                        true -> PermissionGate {
                            MainScreen(settings, runtime, providerRepo, db, envManager)
                        }
                    }
                }
            }
        }
    }
}

private data class Tab(val labelRes: Int, val title: String)

private val tabs = listOf(
    Tab(R.string.tab_terminal, "工作"),
    Tab(R.string.tab_environment, "环境"),
    Tab(R.string.tab_models, "配置"),
    Tab(R.string.tab_settings, "设置"),
)

@Composable
private fun MainScreen(
    settings: SettingsRepository,
    runtime: RuntimeManager,
    providerRepo: ProviderRepository,
    db: AppDatabase,
    envManager: EnvManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 键盘弹出时，底部快捷键条贴住键盘顶边（Termux 同款），并隐藏玻璃导航栏。
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var navBarHeightPx by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // 删除「项目」栏后编号前移；兼容旧存档恢复出的越界值。
    val tabIndex = selected.coerceAtMost(tabs.lastIndex)
    val labels = tabs.map { stringResource(it.labelRes) }
    val installedEnv by envManager.installed.collectAsState(initial = emptyList())
    val tabStateHolder = rememberSaveableStateHolder()

    var conversations by remember { mutableStateOf<List<ConversationEntity>>(emptyList()) }
    var providers by remember { mutableStateOf<List<ProviderEntity>>(emptyList()) }
    var currentId by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf<SessionSpec?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var defaultProviderId by remember { mutableStateOf("") }
    var defaultModelId by remember { mutableStateOf("") }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf("") }
    val startTipShown by produceState<Boolean?>(initialValue = null) {
        settings.startTipShown.collect { value = it }
    }

    LaunchedEffect(Unit) {
        db.conversationDao().observeActive().collect { conversations = it }
    }
    LaunchedEffect(Unit) {
        providerRepo.observeProviders().collect { providers = it }
    }
    LaunchedEffect(Unit) {
        settings.currentConversationId.collect { currentId = it }
    }
    LaunchedEffect(Unit) {
        settings.defaultProviderId.collect { defaultProviderId = it.orEmpty() }
    }
    LaunchedEffect(Unit) {
        settings.defaultModelId.collect { defaultModelId = it.orEmpty() }
    }
    LaunchedEffect(Unit) {
        settings.workspaces.collect { workspaces = it }
    }
    LaunchedEffect(Unit) {
        settings.selectedWorkspaceId.collect { selectedWorkspaceId = it }
    }
    LaunchedEffect(
        currentId, conversations, providers,
        defaultProviderId, defaultModelId, workspaces, selectedWorkspaceId,
    ) {
        val conversation = conversations.firstOrNull { it.id == currentId }
        // 对话未指定服务商时回退到默认服务商，避免旧对话打不开。
        val provider = conversation?.providerId?.let { pid ->
            providers.firstOrNull { it.id == pid }
        } ?: providers.firstOrNull { it.id == defaultProviderId }
            ?: providers.firstOrNull()
        spec = if (conversation != null && provider != null) {
            val defaultWorkspace = WorkspaceUtils.defaultDir(context).absolutePath
            val fallbackWorkspace = workspaces
                .firstOrNull { it.id == selectedWorkspaceId }
                ?.path
                ?.takeIf { it.isNotBlank() }
                ?: defaultWorkspace
            val models = providerRepo.modelsOf(provider)
            val modelId = conversation.modelId
                ?.takeIf { it in models }
                ?: defaultModelId.takeIf { it in models }
                ?: models.firstOrNull().orEmpty()
            SessionSpec(
                conversationId = conversation.id,
                workspace = conversation.workspacePath ?: fallbackWorkspace,
                baseUrl = provider.baseUrl,
                token = providerRepo.tokenOf(provider),
                modelId = modelId,
                smallModelId = provider.smallModelId.orEmpty(),
                effort = conversation.effort,
            )
        } else {
            null
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(Unit) {
        if (settings.currentConversationId.first().isBlank()) drawerState.open()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ConversationDrawerContent(
                conversations = conversations,
                currentId = currentId,
                onSelect = { conversation ->
                    scope.launch {
                        db.conversationDao().upsert(
                            conversation.copy(updatedAt = System.currentTimeMillis())
                        )
                        settings.setCurrentConversation(conversation.id)
                        drawerState.close()
                        selected = 0
                    }
                },
                onDelete = { deleteTarget = it },
                onNew = { showNewDialog = true },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 键盘弹出时 safeDrawing 已包含 IME 高度，不再额外留白，快捷键条正好贴住键盘。
            val bottomReserve = when {
                imeVisible -> 0.dp
                navBarHeightPx > 0 -> with(density) { navBarHeightPx.toDp() } + 4.dp
                else -> 72.dp
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = bottomReserve)
            ) {
                tabStateHolder.SaveableStateProvider(tabIndex) {
                    when (tabIndex) {
                        0 -> TerminalScreen(
                            runtime = runtime,
                            spec = spec,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onSessionClosed = {
                                scope.launch {
                                    // 只关闭终端会话，对话记录保留在列表里，可再次打开。
                                    settings.setCurrentConversation("")
                                    drawerState.open()
                                }
                            },
                        )
                        1 -> EnvironmentScreen(
                            manager = envManager,
                            installed = installedEnv,
                            customWorkspace = workspaces
                                .firstOrNull { it.id == selectedWorkspaceId }
                                ?.path,
                            onOpenConfig = { selected = 2 },
                        )
                        2 -> ConfigScreen(
                            settings = settings,
                            providerRepo = providerRepo,
                            runtime = runtime,
                            providers = providers,
                            workspaces = workspaces,
                            selectedWorkspaceId = selectedWorkspaceId,
                            defaultProviderId = defaultProviderId,
                            defaultModelId = defaultModelId,
                        )
                        3 -> SettingsScreen(runtime)
                    }
                }
            }

            if (!imeVisible) {
                GlassNavBar(
                    labels = labels,
                    selected = tabIndex,
                    onSelect = { selected = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .onSizeChanged { navBarHeightPx = it.height },
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = {
                Text("删除「${target.title.ifBlank { "未命名对话" }}」？终端会话会结束，工作区文件不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        TerminalSessions.close(target.id)
                        db.conversationDao().delete(target.id)
                        if (target.id == currentId) {
                            settings.setCurrentConversation("")
                            drawerState.open()
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (showNewDialog) {
        NewConversationDialog(
            providers = providers,
            modelsOf = { providerRepo.modelsOf(it) },
            defaultProviderId = defaultProviderId,
            defaultModelId = defaultModelId,
            defaultWorkspace = WorkspaceUtils.defaultDir(context).absolutePath,
            workspaces = workspaces,
            selectedWorkspaceId = selectedWorkspaceId,
            onDismiss = { showNewDialog = false },
            onCreate = { title, workspace, providerId, modelId, effort ->
                val now = System.currentTimeMillis()
                val conversation = ConversationEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    workspacePath = workspace,
                    providerId = providerId,
                    modelId = modelId,
                    effort = effort,
                    createdAt = now,
                    updatedAt = now,
                )
                scope.launch {
                    db.conversationDao().upsert(conversation)
                    settings.setCurrentConversation(conversation.id)
                    showNewDialog = false
                    drawerState.close()
                    selected = 0
                }
            },
        )
    }

    // 第一次进入主界面：提示先添加模型和工作区。
    if (startTipShown == false) {
        AlertDialog(
            onDismissRequest = { scope.launch { settings.setStartTipShown(true) } },
            title = { Text("开始使用前") },
            text = { Text("请先添加模型和工作区，再开始对话。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.setStartTipShown(true) }
                    selected = 2
                }) { Text("去配置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { settings.setStartTipShown(true) }
                }) { Text("知道了") }
            },
        )
    }
}