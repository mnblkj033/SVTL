package com.svtl.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 启动器：列表 → 校验 → 解压到缓存 → 加载核心 → 起游戏 → 清缓存。
 * 包只负责存数据（核心 .so、插件、资源），启动器只负责解压和启动。
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { LauncherScreen() }
        }
    }

    fun launchGame() {
        val intent = Intent()
            .setClassName(packageName, "com.chucklefish.stardewvalley.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}

@Composable
private fun LauncherScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<PackInfo>>(emptyList()) }
    var status by remember { mutableStateOf("准备就绪") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            packs = withContext(Dispatchers.IO) { PackageRepository.scan(context) }
            status = if (packs.isEmpty()) "没有找到包，放到 /sdcard/SVTL/ 下" else "共 ${packs.size} 个包"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SVTL 启动器", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("刷新列表") }
            Button(
                onClick = { (context as? LauncherActivity)?.launchGame() },
                enabled = !busy,
            ) { Text("直接启动游戏") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs, key = { it.file.absolutePath }) { pack ->
                PackRow(
                    pack = pack,
                    busy = busy,
                    onInstall = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { installAndPrepare(context, pack) }
                            status = result
                            busy = false
                            if (result.startsWith("已启动")) {
                                (context as? LauncherActivity)?.launchGame()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PackRow(pack: PackInfo, busy: Boolean, onInstall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${pack.name}  ${pack.version}", style = MaterialTheme.typography.titleMedium)
            Text(
                "游戏 ${pack.gameVersion} · ${pack.entryCount} 个文件 · ${pack.size / 1024 / 1024} MB",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(pack.file.absolutePath, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onInstall, enabled = !busy) { Text("安装并启动") }
        }
    }
}

/** 校验 → 解压 → 从包里加载核心/插件 → 删缓存 */
private fun installAndPrepare(context: Context, pack: PackInfo): String {
    if (!PackageRepository.verify(pack)) return "校验失败，包可能损坏"
    val dest = PackageRepository.cacheDir(context, pack)
    val count = PackageRepository.install(pack, dest)

    val core = File(dest, "lib/arm64-v8a/libsvtl.so")
    if (!core.isFile) return "包里没有核心库"
    if (!NativeCore.load(core.absolutePath)) return "核心加载失败"

    NativeCore.init(dest.absolutePath)
    NativeCore.installHooks()
    NativeCore.loadPlugins(File(dest, "plugins").absolutePath, pack.gameVersion)
    dest.deleteRecursively()
    return "已启动 ${pack.name}，解出 $count 个文件，缓存已清理"
}
