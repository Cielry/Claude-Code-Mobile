package com.lyuan.cc.env

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.envDataStore by preferencesDataStore(name = "env")

data class InstalledPackage(
    val id: String,
    val version: String,
    val commands: List<String>,
    val status: String,
    val output: String,
    val installedAt: Long,
    val updatedAt: Long,
    /** 作为其他包的依赖自动安装（界面上归入「依赖包」分组）。 */
    val automatic: Boolean = false,
)

/** 已安装工具记录。安装包本体在文件系统，这里只存元数据。 */
class EnvRepository(private val context: Context) {

    private object Keys {
        val installedJson = stringPreferencesKey("installed_json")
    }

    val installed: Flow<List<InstalledPackage>> =
        context.envDataStore.data.map { parse(it[Keys.installedJson].orEmpty()) }

    suspend fun find(id: String): InstalledPackage? =
        installed.first().firstOrNull { it.id == id }

    suspend fun upsert(pkg: InstalledPackage) {
        context.envDataStore.edit { prefs ->
            val list = parse(prefs[Keys.installedJson].orEmpty())
                .filterNot { it.id == pkg.id }
                .toMutableList()
            list.add(pkg)
            prefs[Keys.installedJson] = serialize(list)
        }
    }

    suspend fun remove(id: String) {
        context.envDataStore.edit { prefs ->
            val list = parse(prefs[Keys.installedJson].orEmpty()).filterNot { it.id == id }
            prefs[Keys.installedJson] = serialize(list)
        }
    }

    private fun parse(json: String): List<InstalledPackage> = runCatching {
        if (json.isBlank()) return@runCatching emptyList()
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (id.isBlank()) return@mapNotNull null
            InstalledPackage(
                id = id,
                version = obj.optString("version"),
                commands = obj.optJSONArray("commands")?.let { commands ->
                    (0 until commands.length()).map { commands.optString(it) }
                }.orEmpty(),
                status = obj.optString("status", "unknown"),
                output = obj.optString("output"),
                installedAt = obj.optLong("installedAt"),
                updatedAt = obj.optLong("updatedAt"),
                automatic = obj.optBoolean("automatic", false),
            )
        }
    }.getOrDefault(emptyList())

    private fun serialize(list: List<InstalledPackage>): String {
        val array = JSONArray()
        list.forEach { pkg ->
            array.put(
                JSONObject()
                    .put("id", pkg.id)
                    .put("version", pkg.version)
                    .put("commands", JSONArray(pkg.commands))
                    .put("status", pkg.status)
                    .put("output", pkg.output)
                    .put("installedAt", pkg.installedAt)
                    .put("updatedAt", pkg.updatedAt)
                    .put("automatic", pkg.automatic)
            )
        }
        return array.toString()
    }
}