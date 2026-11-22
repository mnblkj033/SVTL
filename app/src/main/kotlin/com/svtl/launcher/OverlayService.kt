package com.svtl.launcher

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 游戏内左下角状态条：显示插件数量和"修改版本"标记。
 * 只在启动游戏时挂上，返回后由系统回收。
 */
class OverlayService : Service() {

    private var view: TextView? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val count = PluginStore.scan(this).count { it.enabled }
        val text = "插件 $count 个 · 修改版本"

        val manager = getSystemService(WindowManager::class.java)
        wm = manager
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#FFE9B8"))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1B1A16").toInt())
                cornerRadius = 24f
            }
            background = bg
            setPadding(28, 14, 28, 14)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 24
            y = 24
        }
        runCatching { manager.addView(tv, params) }
        view = tv
    }

    override fun onDestroy() {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        super.onDestroy()
    }
}
