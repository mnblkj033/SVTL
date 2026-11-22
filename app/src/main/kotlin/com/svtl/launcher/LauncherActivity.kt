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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 页面 */
private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Logs : Screen
    data object Browser : Screen
    data class Detail(val pack: PackInfo) : Screen
}

/**
 * 竖屏启动器。布局参考 ZalithLauncher2：
 * 顶部栏 → 内容区 → 底部操作卡片。
 * https://github.com/ZalithLauncher/ZalithLauncher2
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)
        setContent {
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            val dark = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LauncherNav(
                    settings = settings,
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        settings.themeMode = it
                    },
                )
            }
        }
    }

    fun launchGame() {
        val intent = Intent()
            .setClassName(packageName, "com.chucklefish.stardewvalley.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LogStore.add("启动游戏：${intent.component?.className}")
        runCatching { startActivity(intent) }
            .onFailure { LogStore.add("启动失败：${it.message}") }
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

internal fun hasAllFiles(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun LauncherNav(
    settings: SettingsStore,
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var packs by remember { mutableStateOf<List<PackInfo>>(emptyList()) }
    var status by remember { mutableStateOf("还没有导入游戏") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            packs = withContext(Dispatchers.IO) { PackageRepository.scan(context) }
            status = if (packs.isEmpty()) "还没有导入游戏" else "共 ${packs.size} 个包"
            LogStore.add("扫描完成：${packs.size} 个包")
        }
    }

    fun install(pack: PackInfo) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { installAndPrepare(context, pack, settings) }
            status = result
            LogStore.add(result)
            busy = false
            if (result.startsWith("已启动")) {
                (context as? LauncherActivity)?.launchGame()
            }
        }
    }

    fun openImporter() {
        if (hasAllFiles(context)) {
            screen = Screen.Browser
        } else {
            status = "需要「所有文件访问权限」，授权后再点一次"
            LogStore.add("缺少所有文件访问权限")
            (context as? LauncherActivity)?.requestAllFiles()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (screen !is Screen.Home) {
        BackHandler {
            screen = Screen.Home
            refresh()
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            packs = packs,
            status = status,
            busy = busy,
            onImport = { openImporter() },
            onLaunch = { (context as? LauncherActivity)?.launchGame() },
            onOpenFiles = { openImporter() },
            onOpenLogs = { screen = Screen.Logs },
            onOpenSettings = { screen = Screen.Settings },
            onInstall = { install(it) },
            onOpenDetail = { screen = Screen.Detail(it) },
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            onBack = { screen = Screen.Home },
            onClearLogs = { LogStore.clear() },
            onClearCache = {
                val dir = File(context.cacheDir, "packs")
                val ok = dir.deleteRecursively()
                LogStore.add("清理缓存：$ok")
                status = if (ok) "缓存已清理" else "没有缓存可清理"
            },
        )

        Screen.Logs -> LogsScreen(onBack = { screen = Screen.Home })

        Screen.Browser -> FileBrowserScreen(
            onBack = {
                screen = Screen.Home
                refresh()
            },
        )

        is Screen.Detail -> PackDetailScreen(
            pack = s.pack,
            busy = busy,
            onBack = { screen = Screen.Home },
            onInstall = { install(s.pack) },
            onDelete = {
                val ok = s.pack.file.delete()
                LogStore.add("删除 ${s.pack.name}：$ok")
                refresh()
                screen = Screen.Home
            },
        )
    }
}

/** 校验 → 解压 → 从包里加载核心/插件 → 删缓存 */
private fun installAndPrepare(context: Context, pack: PackInfo, settings: SettingsStore): String {
    if (settings.verifyBeforeLaunch && !PackageRepository.verify(pack)) {
        return "校验失败，包可能损坏"
    }
    val dest = PackageRepository.cacheDir(context, pack)
    val count = PackageRepository.install(pack, dest)

    val core = File(dest, "lib/arm64-v8a/libsvtl.so")
    if (!core.isFile) return "包里没有核心库"
    if (!NativeCore.load(core.absolutePath)) return "核心加载失败"

    NativeCore.init(dest.absolutePath)
    NativeCore.installHooks()
    NativeCore.loadPlugins(File(dest, "plugins").absolutePath, pack.gameVersion)

    if (settings.cleanCacheAfterLaunch) {
        dest.deleteRecursively()
        return "已启动 ${pack.name}，解出 $count 个文件，缓存已清理"
    }
    return "已启动 ${pack.name}，解出 $count 个文件（缓存保留）"
}
