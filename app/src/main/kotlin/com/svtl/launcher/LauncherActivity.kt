package com.svtl.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val STORAGE_ROOT = "/storage/emulated/0"
private val DARK_YELLOW = Color(0xFFB8860B)

/**
 * 竖屏启动器。布局参考 ZalithLauncher2：
 * 顶部栏（标题 + 图标按钮）→ 卡片网格 → 底部停靠的操作卡片。
 * https://github.com/ZalithLauncher/ZalithLauncher2
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

    /** 申请「所有文件访问权限」（Android 11+ 跳系统页）。 */
    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
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
    var status by remember { mutableStateOf("还没有导入游戏") }
    var busy by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            packs = withContext(Dispatchers.IO) { PackageRepository.scan(context) }
            status = if (packs.isEmpty()) "还没有导入游戏" else "共 ${packs.size} 个包"
        }
    }

    fun install(pack: PackInfo) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { installAndPrepare(context, pack) }
            status = result
            busy = false
            if (result.startsWith("已启动")) {
                (context as? LauncherActivity)?.launchGame()
            }
        }
    }

    fun openImporter() {
        if (hasAllFiles(context)) {
            showBrowser = true
        } else {
            status = "需要「所有文件访问权限」，授权后再点一次"
            (context as? LauncherActivity)?.requestAllFiles()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showBrowser) {
        BackHandler { showBrowser = false; refresh() }
        FileBrowserScreen(onBack = { showBrowser = false; refresh() })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            status = status,
            onOpenFiles = { openImporter() },
            onSettings = { status = "设置还没做" },
            onAbout = { status = "SVTL 启动器 0.1.3" },
        )

        // 内容：包卡片网格
        if (packs.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "点下方「导入游戏」选择游戏包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(packs, key = { it.file.absolutePath }) { pack ->
                    PackCard(pack = pack, busy = busy, onPlay = { install(pack) })
                }
            }
        }

        // 底部停靠面板：导入 + 启动
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openImporter() }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "导入游戏",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PanelCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = packs.firstOrNull()?.name ?: "未导入",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = packs.firstOrNull()?.let { "${it.version} · ${it.size / 1024 / 1024} MB" }
                                ?: "先导入游戏包",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { (context as? LauncherActivity)?.launchGame() },
                        enabled = !busy,
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DARK_YELLOW,
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("启动游戏", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    status: String,
    onOpenFiles: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text("SVTL", style = MaterialTheme.typography.titleLarge)
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Filled.Folder, contentDescription = "文件")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
            IconButton(onClick = onAbout) {
                Icon(Icons.Filled.Info, contentDescription = "关于")
            }
        }
    }
}

@Composable
private fun PackCard(pack: PackInfo, busy: Boolean, onPlay: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${pack.version} · ${pack.size / 1024 / 1024} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pack.entryCount} 个文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = onPlay,
                    enabled = !busy,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "安装并启动")
                }
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
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
