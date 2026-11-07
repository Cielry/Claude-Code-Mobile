package com.lyuan.cc.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val defaultProviderId = stringPreferencesKey("default_provider_id")
        val defaultModelId = stringPreferencesKey("default_model_id")
        val providerBaseUrl = stringPreferencesKey("provider_base_url")
        val providerModelId = stringPreferencesKey("provider_model_id")
        val providerSmallModelId = stringPreferencesKey("provider_small_model_id")
        val providerTokenCipher = stringPreferencesKey("provider_token_cipher")
        val workspaceMode = stringPreferencesKey("workspace_mode")
        val workspaceUri = stringPreferencesKey("workspace_uri")
        val workspacePath = stringPreferencesKey("workspace_path")
        val workspacesJson = stringPreferencesKey("workspaces_json")
        val selectedWorkspaceId = stringPreferencesKey("selected_workspace_id")
        val currentConversationId = stringPreferencesKey("current_conversation_id")
    }

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.onboardingCompleted] ?: false }

    val providerBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.providerBaseUrl] ?: "" }

    val providerModelId: Flow<String> =
        context.dataStore.data.map { it[Keys.providerModelId] ?: "" }

    val providerSmallModelId: Flow<String> =
        context.dataStore.data.map { it[Keys.providerSmallModelId] ?: "" }

    /** Keystore 加密后的凭据（Base64 "iv:cipher"）。 */
    val providerTokenCipher: Flow<String> =
        context.dataStore.data.map { it[Keys.providerTokenCipher] ?: "" }

    val workspaceMode: Flow<String> =
        context.dataStore.data.map { it[Keys.workspaceMode] ?: "default" }

    val workspaceUri: Flow<String> =
        context.dataStore.data.map { it[Keys.workspaceUri] ?: "" }

    val workspacePath: Flow<String> =
        context.dataStore.data.map { it[Keys.workspacePath] ?: "" }

    val currentConversationId: Flow<String> =
        context.dataStore.data.map { it[Keys.currentConversationId] ?: "" }

    val workspaces: Flow<List<Workspace>> =
        context.dataStore.data.map { parseWorkspaces(it[Keys.workspacesJson].orEmpty()) }

    /** 选中的工作区 id；空字符串表示「默认工作区」。 */
    val selectedWorkspaceId: Flow<String> =
        context.dataStore.data.map { it[Keys.selectedWorkspaceId] ?: "" }

    suspend fun addWorkspace(name: String, uri: String, path: String): String {
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            val list = parseWorkspaces(prefs[Keys.workspacesJson].orEmpty()).toMutableList()
            list.add(
                Workspace(
                    id = id,
                    name = name.ifBlank {
                        File(path).name.ifBlank { "工作区" }
                    },
                    uri = uri,
                    path = path,
                )
            )
            prefs[Keys.workspacesJson] = workspacesToJson(list)
            prefs[Keys.selectedWorkspaceId] = id
        }
        return id
    }

    suspend fun removeWorkspace(id: String) {
        context.dataStore.edit { prefs ->
            val list = parseWorkspaces(prefs[Keys.workspacesJson].orEmpty())
                .filterNot { it.id == id }
            prefs[Keys.workspacesJson] = workspacesToJson(list)
            if (prefs[Keys.selectedWorkspaceId] == id) {
                prefs[Keys.selectedWorkspaceId] = ""
            }
        }
    }

    suspend fun selectWorkspace(id: String) {
        context.dataStore.edit { prefs -> prefs[Keys.selectedWorkspaceId] = id }
    }

    /** v17 及更早版本只保存了一个自定义工作区，首次启动时并入列表。 */
    suspend fun migrateLegacyWorkspace() {
        context.dataStore.edit { prefs ->
            if (parseWorkspaces(prefs[Keys.workspacesJson].orEmpty()).isNotEmpty()) {
                return@edit
            }
            val path = prefs[Keys.workspacePath].orEmpty()
            if (path.isBlank()) return@edit
            val id = "legacy-workspace"
            val workspace = Workspace(
                id = id,
                name = File(path).name.ifBlank { "自定义工作区" },
                uri = prefs[Keys.workspaceUri].orEmpty(),
                path = path,
            )
            prefs[Keys.workspacesJson] = workspacesToJson(listOf(workspace))
            prefs[Keys.selectedWorkspaceId] = id
        }
    }

    private fun parseWorkspaces(json: String): List<Workspace> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val path = obj.optString("path")
            if (path.isBlank()) return@mapNotNull null
            Workspace(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name").ifBlank { File(path).name },
                uri = obj.optString("uri"),
                path = path,
            )
        }
    }.getOrDefault(emptyList())

    private fun workspacesToJson(list: List<Workspace>): String {
        val array = JSONArray()
        list.forEach { workspace ->
            array.put(
                JSONObject()
                    .put("id", workspace.id)
                    .put("name", workspace.name)
                    .put("uri", workspace.uri)
                    .put("path", workspace.path)
            )
        }
        return array.toString()
    }

    suspend fun setWorkspace(mode: String, uri: String, path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.workspaceMode] = mode
            prefs[Keys.workspaceUri] = uri
            prefs[Keys.workspacePath] = path
        }
    }

    suspend fun setCurrentConversation(id: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.currentConversationId] = id
        }
    }

    suspend fun setProviderConfig(baseUrl: String, modelId: String, smallModelId: String, tokenCipher: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.providerBaseUrl] = baseUrl
            prefs[Keys.providerModelId] = modelId
            prefs[Keys.providerSmallModelId] = smallModelId
            prefs[Keys.providerTokenCipher] = tokenCipher
        }
    }

    /** 旧版服务商配置已迁移到 Room 后调用，避免重复导入。 */
    suspend fun clearLegacyProviderConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.providerBaseUrl)
            prefs.remove(Keys.providerModelId)
            prefs.remove(Keys.providerSmallModelId)
            prefs.remove(Keys.providerTokenCipher)
        }
    }

    val defaultProviderId: Flow<String?> =
        context.dataStore.data.map { it[Keys.defaultProviderId] }

    val defaultModelId: Flow<String?> =
        context.dataStore.data.map { it[Keys.defaultModelId] }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.onboardingCompleted] = completed }
    }

    suspend fun setDefaultProvider(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.defaultProviderId)
            else prefs[Keys.defaultProviderId] = id
        }
    }

    suspend fun setDefaultModel(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.defaultModelId)
            else prefs[Keys.defaultModelId] = id
        }
    }
}