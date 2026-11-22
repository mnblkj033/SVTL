package com.svtl.launcher

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val entry: String,
    val dir: File,
) {
    val size: Long
        get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** 插件安装/扫描/启停/删除，目录：filesDir/plugins/<id>/ */
object PluginStore {

    fun root(context: Context): File = File(context.filesDir, "plugins")

    fun scan(context: Context): List<PluginInfo> {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val mf = File(dir, "manifest.json")
            if (!mf.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(mf.readText())
                PluginInfo(
                    id = json.optString("id", dir.name),
                    name = json.optString("name", dir.name),
                    version = json.optString("version", "?"),
                    enabled = json.optBoolean("enabled", true),
                    entry = json.optString("entry", ""),
                    dir = dir,
                )
            }.getOrNull()
        }.sortedBy { it.name }
    }

    /** 从 zip 安装：必须有 manifest.json。 */
    fun installZip(context: Context, uri: Uri): String {
        val tmp = File(context.cacheDir, "plugin-import.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return "读不到文件"

        return runCatching {
            ZipFile(tmp).use { zf ->
                val mf = zf.getEntry("manifest.json") ?: error("压缩包里没有 manifest.json")
                val json = JSONObject(zf.getInputStream(mf).bufferedReader().use { it.readText() })
                val id = json.optString("id")
                if (id.isBlank()) error("manifest.json 缺少 id")
                val dest = File(root(context), id)
                dest.deleteRecursively()
                dest.mkdirs()
                for (e in zf.entries()) {
                    if (e.isDirectory) continue
                    val out = File(dest, e.name)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                }
                LogStore.add("安装插件 $id")
                "已安装 $id"
            }
        }.getOrElse { "安装失败：${it.message}" }
            .also { tmp.delete() }
    }

    /** 直接装单个 .so，自动生成 manifest。 */
    fun installSo(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.so"
        val id = "local." + name.removeSuffix(".so").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dest = File(root(context), id)
        dest.deleteRecursively()
        dest.mkdirs()
        val so = File(dest, if (name.endsWith(".so")) name else "libplugin.so")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                so.outputStream().use { input.copyTo(it) }
            } ?: error("读不到文件")
            File(dest, "manifest.json").writeText(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("version", "0.1.0")
                    .put("abi", 1)
                    .put("entry", so.name)
                    .put("gameVersion", "1.6.*")
                    .put("enabled", true)
                    .toString(2)
            )
            LogStore.add("安装插件 $id")
            "已安装 $id"
        }.getOrElse { "安装失败：${it.message}" }
    }

    fun setEnabled(plugin: PluginInfo, enabled: Boolean): Boolean = runCatching {
        val mf = File(plugin.dir, "manifest.json")
        val json = JSONObject(mf.readText()).put("enabled", enabled)
        mf.writeText(json.toString(2))
        LogStore.add("${if (enabled) "启用" else "禁用"}插件 ${plugin.id}")
        true
    }.getOrDefault(false)

    fun delete(plugin: PluginInfo): Boolean {
        val ok = plugin.dir.deleteRecursively()
        LogStore.add("删除插件 ${plugin.id}：$ok")
        return ok
    }
}
