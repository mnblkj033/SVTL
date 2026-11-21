package com.svtl.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 一个插件包（zip）：manifest.json + CHECKSUM + lib/ + plugins/ + 资源 */
data class PackInfo(
    val file: File,
    val name: String,
    val version: String,
    val gameVersion: String,
    val checksum: String,
    val entryCount: Int,
) {
    val size: Long get() = file.length()
}

object PackageRepository {

    /** 扫描顺序：应用私有目录优先，其次手机存储。 */
    fun searchDirs(context: Context): List<File> = listOf(
        File(context.filesDir, "packages"),
        File("/sdcard/SVTL"),
        File("/sdcard/Download/SVTL"),
    )

    fun scan(context: Context): List<PackInfo> {
        val out = mutableListOf<PackInfo>()
        for (dir in searchDirs(context)) {
            val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            zips?.forEach { z -> runCatching { readInfo(z) }.getOrNull()?.let(out::add) }
        }
        return out.distinctBy { it.file.absolutePath }
    }

    private fun readInfo(zip: File): PackInfo = ZipFile(zip).use { zf ->
        val entry = zf.getEntry("manifest.json") ?: error("缺少 manifest.json")
        val text = zf.getInputStream(entry).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        PackInfo(
            file = zip,
            name = json.optString("name", zip.nameWithoutExtension),
            version = json.optString("version", "?"),
            gameVersion = json.optString("gameVersion", "*"),
            checksum = json.optString("checksum", ""),
            entryCount = zf.size(),
        )
    }

    /**
     * 校验码算法与 cpp/src/main.cpp 一致：
     * 按名字排序，跳过 manifest.json / CHECKSUM，逐个 update(名字)、0x00、内容。
     */
    fun verify(pack: PackInfo): Boolean {
        if (pack.checksum.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(pack.file).use { zf ->
            val names = zf.entries().toList()
                .filter { !it.isDirectory && it.name != "manifest.json" && it.name != "CHECKSUM" }
                .map { it.name }
                .sorted()
            for (name in names) {
                val entry = zf.getEntry(name) ?: return false
                digest.update(name.toByteArray())
                digest.update(0)
                val buf = ByteArray(8192)
                zf.getInputStream(entry).use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == pack.checksum
    }

    /** 解压到 dest，返回解出的文件数。 */
    fun install(pack: PackInfo, dest: File): Int {
        dest.deleteRecursively()
        dest.mkdirs()
        var count = 0
        ZipFile(pack.file).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val out = File(dest, entry.name)
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                count++
            }
        }
        return count
    }

    fun cacheDir(context: Context, pack: PackInfo): File =
        File(context.cacheDir, "packs/${pack.name}-${pack.version}")
}
