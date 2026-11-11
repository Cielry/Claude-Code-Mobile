package com.lyuan.cc.env

import android.content.Context
import org.json.JSONObject

/** data/termux-packages.txt 生成的包列表，安装时从软件源按需下载。 */
data class TermuxPackage(
    val name: String,
    val version: String,
    /** 基础依赖，安装其它包时自动带入。 */
    val automatic: Boolean,
    /** 建议优先安装的通用包。 */
    val recommended: Boolean,
    /** 推荐说明；非推荐包为空。 */
    val description: String,
)

/** 环境包目录中的一个可安装包。 */
data class CatalogPackage(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val version: String,
    val size: Long,
    val sha256: String,
    val urls: List<String>,
    val stripComponents: Int,
    val binDir: String,
    val commands: List<String>,
    val libs: List<String>,
    val env: Map<String, String>,
    val dependsOn: List<String>,
    val checkArgs: List<String>,
    /** 内置包在 APK assets 中的路径；非空表示无需联网下载。 */
    val bundled: String?,
)

object EnvCatalog {

    /** 从 APK assets 读取内置包目录。 */
    fun load(context: Context): List<CatalogPackage> {
        val text = context.assets.open("env/catalog.json")
            .bufferedReader()
            .use { it.readText() }
        val root = JSONObject(text)
        val array = root.optJSONArray("packages") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            parse(obj)
        }
    }

    /** 读取 Termux 设备包列表（已去掉 glibc 变体、仓库标记与内置目录已有的包）。 */
    fun loadTermuxPackages(context: Context): List<TermuxPackage> = runCatching {
        val text = context.assets.open("env/termux-packages.json")
            .bufferedReader()
            .use { it.readText() }
        val array = JSONObject(text).optJSONArray("packages")
            ?: return@runCatching emptyList()
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name")
            if (name.isBlank()) {
                null
            } else {
                TermuxPackage(
                    name = name,
                    version = obj.optString("version"),
                    automatic = obj.optBoolean("automatic", false),
                    recommended = obj.optBoolean("recommended", false),
                    description = obj.optString("description"),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parse(obj: JSONObject): CatalogPackage? {
        val id = obj.optString("id")
        val version = obj.optString("version")
        if (id.isBlank() || version.isBlank()) return null
        val urls = obj.optJSONArray("urls")
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
            .orEmpty()
        val bundled = obj.optString("bundled").takeIf { it.isNotBlank() }
        if (urls.isEmpty() && bundled == null) return null
        return CatalogPackage(
            id = id,
            name = obj.optString("name", id),
            category = obj.optString("category", "其他"),
            description = obj.optString("description"),
            version = version,
            size = obj.optLong("size"),
            sha256 = obj.optString("sha256").lowercase(),
            urls = urls,
            stripComponents = obj.optInt("stripComponents", 1),
            binDir = obj.optString("binDir", "bin"),
            commands = stringList(obj, "commands"),
            libs = stringList(obj, "libs"),
            env = objectMap(obj.optJSONObject("env")),
            dependsOn = stringList(obj, "dependsOn"),
            checkArgs = stringList(obj, "checkArgs").ifEmpty { listOf("--version") },
            bundled = bundled,
        )
    }

    private fun stringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
    }

    private fun objectMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().associateWith { obj.optString(it) }
    }
}