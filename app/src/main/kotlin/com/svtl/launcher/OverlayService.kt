package com.svtl.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONArray

/**
 * 游戏内叠加层：把核心里的绘制指令画成 Android 画面，
 * 插件只要调 draw_text / draw_rect 就能在游戏上盖一层新界面。
 */
class OverlayService : Service() {

    private var view: OverlayView? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("svtl_overlay", "SVTL 叠加层", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, "svtl_overlay")
            .setContentTitle("SVTL")
            .setContentText("游戏内叠加层运行中")
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
        val manager = getSystemService(WindowManager::class.java)
        wm = manager
        val v = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { manager.addView(v, params) }
        view = v

        // 没有钩到游戏时，也定时驱动插件，让它们能持续往叠加层画东西
        Thread {
            while (true) {
                runCatching { if (NativeCore.isLoaded()) NativeCore.tick(0.1) }
                Thread.sleep(100)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        view?.stop()
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        super.onDestroy()
    }
}

/** 每 100ms 从核心拉一次绘制指令，画到 Canvas 上。 */
private class OverlayView(context: Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE9B8")
        textSize = 28f
    }
    private var running = true

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            handler.postDelayed(this, 100)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) 插件画的指令
        val json = runCatching { NativeCore.drawJson() }.getOrNull()
        if (!json.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = o.optInt("c", 0xFFFFFFFF.toInt())
                    if (o.optString("t") == "text") {
                        textPaint.color = c
                        textPaint.textSize = o.optDouble("size", 16.0).toFloat() *
                            context.resources.displayMetrics.density
                        canvas.drawText(
                            o.optString("s"), o.optDouble("x", 0.0).toFloat(),
                            o.optDouble("y", 0.0).toFloat(), textPaint
                        )
                    } else {
                        paint.color = c
                        paint.style = if (o.optInt("f", 1) == 1) Paint.Style.FILL else Paint.Style.STROKE
                        canvas.drawRect(
                            o.optDouble("x", 0.0).toFloat(),
                            o.optDouble("y", 0.0).toFloat(),
                            (o.optDouble("x", 0.0) + o.optDouble("w", 0.0)).toFloat(),
                            (o.optDouble("y", 0.0) + o.optDouble("h", 0.0)).toFloat(),
                            paint
                        )
                    }
                }
            }
        }

        // 2) 左下角状态条
        val count = runCatching { PluginStore.scan(context).count { it.enabled } }.getOrDefault(0)
        val label = "插件 $count 个 · 修改版本"
        textPaint.color = Color.parseColor("#FFE9B8")
        textPaint.textSize = 30f
        val pad = 24f
        val w = textPaint.measureText(label) + pad * 2
        val h = 52f
        val x = 24f
        val y = height - h - 24f
        paint.color = Color.parseColor("#CC1B1A16").toInt()
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(x, y, x + w, y + h, 26f, 26f, paint)
        canvas.drawText(label, x + pad, y + h - 18f, textPaint)
    }
}
