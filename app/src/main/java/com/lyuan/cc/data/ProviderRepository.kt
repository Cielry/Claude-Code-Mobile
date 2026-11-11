package com.lyuan.cc.data

import android.content.Context
import com.lyuan.cc.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 服务商与模型管理。每家服务商可配置多个模型；API Key 用 Keystore 加密后存 Room。
 */
class ProviderRepository(context: Context) {

    private val db = AppDatabase.get(context)

    fun observeProviders(): Flow<List<ProviderEntity>> =
        db.providerDao().observeAll().map { list ->
            list.map { it.copy(baseUrl = normalizeBaseUrl(it.baseUrl)) }
        }

    suspend fun addProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        firstModelId: String,
        smallModelId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val models = if (firstModelId.isBlank()) emptyList() else listOf(firstModelId)
        val now = System.currentTimeMillis()
        val entity = ProviderEntity(
            id = id,
            name = name,
            baseUrl = normalizeBaseUrl(baseUrl),
            authType = "api_key",
            credentialAlias = id,
            credentialCipher = if (apiKey.isBlank()) null else CryptoManager(id).encrypt(apiKey),
            modelsJson = serializeModels(models),
            smallModelId = smallModelId?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        db.providerDao().upsert(entity)
        return id
    }

    /**
     * v13 及更早版本把服务商配置存在 DataStore 中。首次启动 v14 时导入为 Room 记录，
     * 沿用旧的 Keystore 别名，避免用户重新填写 Key。导入后清空旧字段，只执行一次。
     */
    suspend fun importLegacyIfNeeded(settings: SettingsRepository) {
        if (db.providerDao().observeAll().first().isNotEmpty()) return
        val baseUrl = settings.providerBaseUrl.first()
        val modelId = settings.providerModelId.first()
        if (baseUrl.isBlank() || modelId.isBlank()) return
        val smallModelId = settings.providerSmallModelId.first()
        val cipher = settings.providerTokenCipher.first()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        db.providerDao().upsert(
            ProviderEntity(
                id = id,
                name = "默认服务商",
                baseUrl = normalizeBaseUrl(baseUrl),
                authType = "api_key",
                credentialAlias = LEGACY_ALIAS,
                credentialCipher = cipher.takeIf { it.isNotBlank() },
                modelsJson = serializeModels(listOf(modelId)),
                smallModelId = smallModelId.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
            )
        )
        settings.setDefaultProvider(id)
        settings.setDefaultModel(modelId)
        settings.clearLegacyProviderConfig()
    }

    /** 拉取已保存服务商的模型列表。 */
    suspend fun fetchModels(providerId: String): Result<List<String>> {
        val provider = db.providerDao().find(providerId)
            ?: return Result.failure(IllegalStateException("服务商不存在"))
        return fetchModelsFor(provider.baseUrl, tokenOf(provider))
    }

    /**
     * 按 Base URL + Key 拉取模型列表，兼容 OpenAI 的 `/v1/models`、`/models`，
     * 以及带 `/anthropic` 后缀的 base URL。
     */
    suspend fun fetchModelsFor(
        baseUrl: String,
        apiKey: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normalizeBaseUrl(baseUrl).trimEnd('/')
            if (base.isBlank()) error("Base URL 为空")
            val candidates = buildList {
                add("$base/v1/models")
                add("$base/models")
                if (base.contains("/anthropic")) {
                    add(base.substringBefore("/anthropic").trimEnd('/') + "/v1/models")
                }
            }.distinct()

            var lastError = "无可用响应"
            for (url in candidates) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("anthropic-version", "2023-06-01")
                        if (apiKey.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer $apiKey")
                            setRequestProperty("x-api-key", apiKey)
                        }
                    }
                    val code = conn.responseCode
                    val body = try {
                        (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    } finally {
                        conn.disconnect()
                    }
                    if (code !in 200..299) {
                        lastError = "HTTP $code"
                        continue
                    }
                    val json = JSONObject(body)
                    val array = json.optJSONArray("data")
                        ?: json.optJSONArray("models")
                        ?: error("响应中没有模型列表")
                    val ids = buildList {
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i)
                            val id = obj?.optString("id").orEmpty()
                                .ifBlank { obj?.optString("name").orEmpty() }
                                .ifBlank { array.optString(i) }
                            if (id.isNotBlank() && id != "null") add(id)
                        }
                    }.distinct()
                    if (ids.isEmpty()) error("响应中没有模型")
                    return@runCatching ids
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
            }
            error("拉取失败：$lastError")
        }
    }

    suspend fun addModel(providerId: String, modelId: String) {
        val provider = db.providerDao().find(providerId) ?: return
        if (modelId.isBlank()) return
        val models = parseModels(provider.modelsJson).toMutableList()
        if (!models.contains(modelId)) models.add(modelId)
        db.providerDao().upsert(
            provider.copy(
                modelsJson = serializeModels(models),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeModel(providerId: String, modelId: String) {
        val provider = db.providerDao().find(providerId) ?: return
        val models = parseModels(provider.modelsJson).filterNot { it == modelId }
        db.providerDao().upsert(
            provider.copy(
                modelsJson = serializeModels(models),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setSmallModel(providerId: String, modelId: String?) {
        val provider = db.providerDao().find(providerId) ?: return
        db.providerDao().upsert(
            provider.copy(
                smallModelId = modelId?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 编辑服务商。apiKey 为 null 或空白时沿用已保存的密文，不触碰 Keystore。
     * 返回 false 表示服务商已被删除。
     */
    suspend fun updateProvider(
        id: String,
        name: String,
        baseUrl: String,
        apiKey: String?,
        smallModelId: String?,
        models: List<String>,
    ): Boolean {
        val provider = db.providerDao().find(id) ?: return false
        val alias = provider.credentialAlias ?: provider.id
        val cipher = if (apiKey != null && apiKey.isNotBlank()) {
            CryptoManager(alias).encrypt(apiKey.trim())
        } else {
            provider.credentialCipher
        }
        val cleaned = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        db.providerDao().upsert(
            provider.copy(
                name = name.trim(),
                baseUrl = normalizeBaseUrl(baseUrl),
                credentialAlias = alias,
                credentialCipher = cipher,
                modelsJson = serializeModels(cleaned),
                smallModelId = smallModelId?.trim()?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun deleteProvider(id: String) {
        val alias = db.providerDao().find(id)?.credentialAlias ?: id
        db.providerDao().delete(id)
        runCatching { CryptoManager(alias).deleteKey() }
    }

    suspend fun find(id: String): ProviderEntity? = db.providerDao().find(id)

    suspend fun tokenOf(provider: ProviderEntity): String {
        val cipher = provider.credentialCipher ?: return ""
        val alias = provider.credentialAlias ?: provider.id
        return runCatching { CryptoManager(alias).decrypt(cipher) }.getOrDefault("")
    }

    fun modelsOf(provider: ProviderEntity): List<String> =
        parseModels(provider.modelsJson)

    /** 兼容旧格式：纯字符串数组，以及曾用过的 {id, effort} 对象数组。 */
    private fun parseModels(json: String): List<String> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index)
            if (obj != null) {
                obj.optString("id").takeIf { it.isNotBlank() }
            } else {
                array.optString(index).takeIf { it.isNotBlank() }
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun serializeModels(models: List<String>): String {
        val array = JSONArray()
        models.forEach { array.put(it) }
        return array.toString()
    }

    /** 兼容曾被 JSON 转义成 `https:\/\/` 的 Base URL。 */
    private fun normalizeBaseUrl(url: String): String = url.trim().replace("\\/", "/")

    private companion object {
        /** v13 及更早版本 CryptoManager 的默认 Keystore 别名。 */
        const val LEGACY_ALIAS = "ccm_credentials"
    }
}