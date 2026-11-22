package com.svtl.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("svtl_overlay", "SVTL 状态条", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, "svtl_overlay")
            .setContentTitle("SVTL")
            .setContentText("游戏内状态条运行中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(2001, n)
    }

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
