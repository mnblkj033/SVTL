package com.svtl.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val STORAGE_ROOT = "/storage/emulated/0"
private val DARK_YELLOW = Color(0xFFB8860B)

/**
 * 启动器：包列表 → 校验 → 解压到缓存 → 加载核心 → 起游戏 → 清缓存。
 * 右侧卡片放「导入游戏」和「启动游戏」。
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { LauncherRoot() }
        }
    }

    fun launchGame() {
        val intent = Intent()
            .setClassName(packageName, "com.chucklefish.stardewvalley.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** 申请访问所有文件（Android 11+ 走「所有文件访问权限」页）。 */
    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
        }
    }
}

private fun hasAllFiles(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun LauncherRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<PackInfo>>(emptyList()) }
    var status by remember { mutableStateOf("准备就绪") }
    var busy by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            packs = withContext(Dispatchers.IO) { PackageRepository.scan(context) }
            status = if (packs.isEmpty()) "还没有导入游戏，点右侧「导入游戏」" else "共 ${packs.size} 个包"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showBrowser) {
        BackHandler { showBrowser = false; refresh() }
        FileBrowserScreen(onBack = { showBrowser = false; refresh() })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).padding(end = 132.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SVTL 启动器", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)

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

        // 右侧卡片：导入游戏 + 启动游戏
        Card(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(116.dp),
        ) {
            Column(
                modifier = Modifier.height(220.dp).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "导入游戏",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (hasAllFiles(context)) {
                                showBrowser = true
                            } else {
                                status = "需要「所有文件访问权限」，授权后再点一次"
                                (context as? LauncherActivity)?.requestAllFiles()
                            }
                        }
                        .padding(vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { (context as? LauncherActivity)?.launchGame() },
                    enabled = !busy,
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DARK_YELLOW,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("启动游戏", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 只读浏览 /storage/emulated/0 下的文件夹和文件。 */
@Composable
private fun FileBrowserScreen(onBack: () -> Unit) {
    val root = remember { File(STORAGE_ROOT) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = root.listFiles()
            if (list == null) {
                error = "读不了 $STORAGE_ROOT，检查权限"
            } else {
                entries = list.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(STORAGE_ROOT, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("${entries.size} 项", style = MaterialTheme.typography.bodySmall)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (f.isDirectory) "📁" else "📄")
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(f.name)
                        if (!f.isDirectory) {
                            Text(
                                "${f.length() / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
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
