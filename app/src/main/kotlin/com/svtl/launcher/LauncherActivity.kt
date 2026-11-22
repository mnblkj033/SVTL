package com.svtl.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.svtl.manager.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val STORAGE_ROOT = "/storage/emulated/0"

/**
 * 竖屏启动器。用原生 View 写，不依赖 Compose ——
 * 合并进游戏 APK 时不会和里面的 Compose/AndroidX 版本打架。
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private var embedded = false
    private var dark = false

    private val pickPlugin = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: ""
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    if (name.endsWith(".zip", true)) PluginStore.installZip(this@LauncherActivity, uri)
                    else PluginStore.installSo(this@LauncherActivity, uri)
                }
                LogStore.add(msg)
                showPlugins()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsStore(this)
        dark = when (settings.themeMode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        embedded = runCatching {
            Class.forName("com.chucklefish.stardewvalley.MainActivity")
        }.isSuccess

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                NativeCore.load(null).also {
                    if (it) {
                        NativeCore.init(filesDir.absolutePath)
                        NativeCore.loadPlugins(
                            File(filesDir, "plugins").absolutePath, "1.6.15.3"
                        )
                    }
                }
            }
            LogStore.add(if (ok) "核心已加载" else "核心未加载（APK 里没有 libsvtl.so）")
            showHome()
        }
        showHome()
    }

    /* ---------------- 通用样式 ---------------- */

    private fun bg(): Int = if (dark) Color.parseColor("#101418") else Color.parseColor("#F6F4EE")
    private fun cardBg(): Int = if (dark) Color.parseColor("#1E252C") else Color.parseColor("#FFFFFF")
    private fun fg(): Int = if (dark) Color.parseColor("#E7EAEC") else Color.parseColor("#1C1B17")
    private fun sub(): Int = if (dark) Color.parseColor("#A7B0B7") else Color.parseColor("#4A4740")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, color: Int = fg()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun action(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 14f
            setOnClickListener { onClick() }
        }

    private fun card(content: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardBg())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(content)
    }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg())
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun space(v: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(v))
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        addView(content)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
    }

    private fun setBody(view: View) = setContentView(view)

    /* ---------------- 首页 ---------------- */

    private fun showHome() {
        val root = root()

        val top = row()
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label("SVTL", 22f))
        titleBox.addView(
            label(
                if (embedded) "游戏已内嵌" else "独立启动器（没有游戏本体）",
                12f, sub()
            )
        )
        top.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(action("插件") { showPlugins() })
        top.addView(action("日志") { showLogs() })
        top.addView(action("设置") { showSettings() })
        root.addView(top)
        root.addView(space(10))

        val plugins = PluginStore.scan(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label("已安装插件：${plugins.size}", 14f, sub()))
        body.addView(space(8))
        if (plugins.isEmpty()) {
            body.addView(label("还没有插件，点上方「插件」安装", 14f, sub()))
        } else {
            plugins.take(6).forEach { p ->
                body.addView(
                    card(
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(p.name, 16f))
                            addView(label("${p.id} · v${p.version}", 12f, sub()))
                        }
                    )
                )
                body.addView(space(8))
            }
        }
        root.addView(scroll(body))
        root.addView(space(10))

        val launch = action(if (embedded) "启动游戏" else "需要合并包才有游戏") {
            if (embedded) launchGame()
        }
        launch.isEnabled = embedded
        launch.textSize = 16f
        root.addView(launch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        setBody(root)
    }

    private fun launchGame() {
        val intent = Intent()
            .setClassName(packageName, "com.chucklefish.stardewvalley.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LogStore.add("启动游戏")
        runCatching { startActivity(intent) }
            .onFailure { LogStore.add("启动失败：${it.message}") }
    }

    /* ---------------- 插件 ---------------- */

    private fun showPlugins() {
        val root = root()
        val top = row()
        top.addView(action("返回") { showHome() })
        top.addView(label("  插件", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(action("安装插件") { pickPlugin.launch(arrayOf("*/*")) })
        root.addView(top)
        root.addView(space(10))

        val plugins = PluginStore.scan(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (plugins.isEmpty()) {
            body.addView(label("还没有插件。点右上角「安装插件」，选 zip 或 .so", 14f, sub()))
        } else {
            plugins.forEach { p ->
                val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                item.addView(label(p.name, 16f))
                item.addView(label("${p.id} · v${p.version} · ${p.size / 1024} KB", 12f, sub()))

                val ops = row()
                val sw = Switch(this).apply {
                    text = "启用"
                    textSize = 13f
                    isChecked = p.enabled
                    setOnCheckedChangeListener { _, on ->
                        PluginStore.setEnabled(p, on)
                        if (NativeCore.isLoaded()) {
                            NativeCore.loadPlugins(
                                File(filesDir, "plugins").absolutePath, "1.6.15.3"
                            )
                        }
                    }
                }
                ops.addView(sw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                ops.addView(action("删除") {
                    PluginStore.delete(p)
                    showPlugins()
                })
                item.addView(ops)
                body.addView(card(item))
                body.addView(space(8))
            }
        }
        root.addView(scroll(body))
        setBody(root)
    }

    /* ---------------- 设置 ---------------- */

    private fun showSettings() {
        val root = root()
        val top = row()
        top.addView(action("返回") { showHome() })
        top.addView(label("  设置", 20f))
        root.addView(top)
        root.addView(space(10))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        body.addView(label("外观", 15f))
        listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, name) ->
            body.addView(action(
                if (settings.themeMode == v) "● $name" else "○ $name"
            ) {
                settings.themeMode = v
                recreate()
            })
        }
        body.addView(space(12))

        body.addView(label("启动", 15f))
        body.addView(switchRow("启动前校验", settings.verifyBeforeLaunch) { settings.verifyBeforeLaunch = it })
        body.addView(switchRow("启动后清理缓存", settings.cleanCacheAfterLaunch) { settings.cleanCacheAfterLaunch = it })
        body.addView(space(12))

        body.addView(label("维护", 15f))
        body.addView(action("清理解压缓存") {
            File(cacheDir, "packs").deleteRecursively()
            LogStore.add("缓存已清理")
        })
        body.addView(action("清空日志") {
            LogStore.clear()
            LogStore.add("日志已清空")
        })
        body.addView(space(12))
        body.addView(label("SVTL 启动器 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", 13f, sub()))
        body.addView(label("布局参考 ZalithLauncher2", 12f, sub()))

        root.addView(scroll(body))
        setBody(root)
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val r = row()
        r.addView(label(title, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        return r
    }

    /* ---------------- 日志 ---------------- */

    private fun showLogs() {
        val root = root()
        val top = row()
        top.addView(action("返回") { showHome() })
        top.addView(label("  日志", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(action("清空") {
            LogStore.clear()
            showLogs()
        })
        root.addView(top)
        root.addView(space(8))
        val tv = label(LogStore.lines.joinToString("\n").ifBlank { "还没有日志" }, 12f, sub())
        root.addView(scroll(tv))
        setBody(root)
    }
}
