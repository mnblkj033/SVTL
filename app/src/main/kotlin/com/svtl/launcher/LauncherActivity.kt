package com.svtl.launcher

import android.app.Activity
import com.svtl.manager.BuildConfig
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

private const val GAME_PACKAGE = "com.chucklefish.stardewvalley"
private const val GAME_ACTIVITY = "com.chucklefish.stardewvalley.MainActivity"
private const val REQ_PICK = 1001

/**
 * 横屏启动器：左边内容，右边操作面板（参考 ZalithLauncher2）。
 * 纯原生 View，不依赖 Compose / AndroidX，合并进游戏 APK 不会版本冲突。
 */
class LauncherActivity : Activity() {

    private lateinit var settings: SettingsStore
    private var embedded = false
    private var gameInstalled = false
    private var dark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsStore(this)
        dark = when (settings.themeMode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        embedded = runCatching { Class.forName(GAME_ACTIVITY) }.isSuccess
        gameInstalled = runCatching { packageManager.getPackageInfo(GAME_PACKAGE, 0) }.isSuccess

        Thread {
            val ok = NativeCore.load(null)
            if (ok) {
                NativeCore.init(filesDir.absolutePath)
                NativeCore.loadPlugins(File(filesDir, "plugins").absolutePath, "1.6.15.3")
            }
            LogStore.add(if (ok) "核心已加载" else "核心未加载")
            runOnUiThread { showHome() }
        }.start()

        showHome()
    }

    /* ---------------- 样式 ---------------- */

    private fun bgColor() = if (dark) 0xFF0F1418.toInt() else 0xFFF5F2EA.toInt()
    private fun cardColor() = if (dark) 0xFF1B2229.toInt() else 0xFFFFFFFF.toInt()
    private fun textColor() = if (dark) 0xFFE8EBEE.toInt() else 0xFF1B1A17.toInt()
    private fun subColor() = if (dark) 0xFF9AA4AD.toInt() else 0xFF6B665C.toInt()
    private fun accent() = 0xFFB8860B.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 22, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun title(text: String, size: Float = 20f, color: Int = textColor()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun body(text: String, size: Float = 13f, color: Int = subColor()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun pill(text: String, filled: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (filled) Color.WHITE else textColor())
            background = rounded(if (filled) accent() else cardColor())
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun card(content: View, padding: Int = 16): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor())
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            addView(content)
        }

    private fun gap(v: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(v))
    }

    /* ---------------- 首页 ---------------- */

    private fun showHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧：标题 + 插件
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(title("SVTL", 26f))
        left.addView(body(if (embedded) "游戏已内嵌 · 1.6.15.3" else if (gameInstalled) "已检测到已安装的星露谷" else "未检测到游戏"))
        left.addView(gap(14))

        val plugins = PluginStore.scan(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(body("插件 ${plugins.size} 个", 13f, textColor()))
        list.addView(gap(8))
        if (plugins.isEmpty()) {
            list.addView(card(body("还没有插件，点右边「插件」安装")))
        } else {
            plugins.take(4).forEach { p ->
                list.addView(card(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(body(p.name, 15f, textColor()))
                        addView(body("${p.id} · v${p.version}", 12f))
                    },
                    padding = 12,
                ))
                list.addView(gap(8))
            }
        }
        left.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(18), 1) })

        // 右侧：操作面板
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        val canLaunch = embedded || gameInstalled

        right.addView(card(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title(if (embedded) "星露谷物语" else "未检测到游戏", 17f))
                addView(gap(4))
                addView(body(if (canLaunch) "点下面开始" else "先安装游戏或使用合并包", 12f))
                addView(gap(12))
                val b = pill(if (canLaunch) "启动游戏" else "无法启动", filled = canLaunch) {
                    if (canLaunch) launchGame()
                }
                b.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(b)
            },
        ), LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))
        right.addView(gap(12))

        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(pill("插件") { showPlugins() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ops.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        ops.addView(pill("日志") { showLogs() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ops.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        ops.addView(pill("设置") { showSettings() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        right.addView(ops, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(right, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun launchGame() {
        // 轮询游戏状态（不挂钩子）
        runCatching { if (NativeCore.isLoaded()) NativeCore.probeStart() }
        val targets = mutableListOf<Intent>()
        if (embedded) targets += Intent().setClassName(packageName, GAME_ACTIVITY)
        if (gameInstalled) targets += Intent().setClassName(GAME_PACKAGE, GAME_ACTIVITY)
        packageManager.getLaunchIntentForPackage(GAME_PACKAGE)?.let { targets += it }

        for (intent in targets) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { startActivity(intent) }.isSuccess
            LogStore.add("启动 ${intent.component?.flattenToShortString()}：${if (ok) "成功" else "失败"}")
            if (ok) return
        }
        LogStore.add("没找到可启动的游戏")
    }

    /* ---------------- 插件 ---------------- */

    private fun showPlugins() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill("返回") { showHome() })
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        top.addView(title("插件", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("安装插件", filled = true) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_PICK,
            )
        })
        root.addView(top)
        root.addView(gap(12))

        val plugins = PluginStore.scan(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (plugins.isEmpty()) {
            list.addView(card(body("还没有插件。点右上角「安装插件」，选 zip 或 .so")))
        } else {
            plugins.forEach { p ->
                val item = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(body(p.name, 15f, textColor()))
                info.addView(body("${p.id} · v${p.version} · ${p.size / 1024} KB", 12f))
                item.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                item.addView(Switch(this).apply {
                    isChecked = p.enabled
                    setOnCheckedChangeListener { _, on ->
                        PluginStore.setEnabled(p, on)
                        if (NativeCore.isLoaded()) {
                            NativeCore.loadPlugins(File(filesDir, "plugins").absolutePath, "1.6.15.3")
                        }
                    }
                })
                item.addView(pill("删除") { PluginStore.delete(p); showPlugins() })
                list.addView(card(item, padding = 12))
                list.addView(gap(8))
            }
        }
        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来时确保核心还在（进程没被杀的话）
        if (!NativeCore.isLoaded()) {
            Thread {
                if (NativeCore.load(null)) {
                    NativeCore.init(filesDir.absolutePath)
                    NativeCore.loadPlugins(File(filesDir, "plugins").absolutePath, "1.6.15.3")
                    LogStore.add("核心已重新加载")
                }
            }.start()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val name = uri.lastPathSegment ?: ""
            Thread {
                val msg = if (name.endsWith(".zip", true)) PluginStore.installZip(this, uri)
                else PluginStore.installSo(this, uri)
                LogStore.add(msg)
                runOnUiThread { showPlugins() }
            }.start()
        }
    }

    /* ---------------- 设置 ---------------- */

    private fun showSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill("返回") { showHome() })
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        top.addView(title("设置", 20f))
        root.addView(top)
        root.addView(gap(12))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body("外观", 14f, textColor()))
            addView(gap(8))
            val row = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, name) ->
                row.addView(pill(if (settings.themeMode == v) "● $name" else name) {
                    settings.themeMode = v
                    recreate()
                })
                row.addView(View(this@LauncherActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 1)
                })
            }
            addView(row)
        }))
        list.addView(gap(10))

        list.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body("启动", 14f, textColor()))
            addView(switchRow("启动前校验", settings.verifyBeforeLaunch) { settings.verifyBeforeLaunch = it })
            addView(switchRow("启动后清理缓存", settings.cleanCacheAfterLaunch) { settings.cleanCacheAfterLaunch = it })
        }))
        list.addView(gap(10))

        list.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(body("维护", 14f, textColor()))
            addView(gap(8))
            addView(pill("清理解压缓存") {
                File(cacheDir, "packs").deleteRecursively()
                LogStore.add("缓存已清理")
            })
            addView(gap(8))
            addView(pill("清空日志") { LogStore.clear() })
            addView(gap(10))
            addView(body("SVTL 启动器 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", 12f))
        }))

        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(body(title, 14f, textColor()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        return r
    }

    /* ---------------- 日志 ---------------- */

    private fun showLogs() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill("返回") { showHome() })
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })
        top.addView(title("日志", 20f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("清空") { LogStore.clear(); showLogs() })
        root.addView(top)
        root.addView(gap(10))

        val text = if (LogStore.lines.isEmpty()) "还没有日志" else LogStore.lines.joinToString("\n")
        root.addView(ScrollView(this).apply {
            addView(body(text, 12f))
        })
        setContentView(root)
    }
}
