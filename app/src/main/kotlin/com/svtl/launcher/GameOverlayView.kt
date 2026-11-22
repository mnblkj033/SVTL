package com.svtl.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONArray

/** 每 100ms 从核心拉一次绘制指令，画到 Canvas 上。 */
internal class OverlayView(context: Context) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE9B8")
        textSize = 28f
    }
    private var running = true
    private val bitmaps = HashMap<String, android.graphics.Bitmap?>()
    private var lastFrame = System.currentTimeMillis()
    private var fps = 0f

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

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (runCatching { NativeCore.touchEnabled() }.getOrDefault(0) == 0) return false
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            runCatching { NativeCore.uiTap(event.x, event.y) }
            return true
        }
        return true
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
                    if (o.optString("t") == "img") {
                        val path = o.optString("p")
                        val bmp = bitmaps.getOrPut(path) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
                        }
                        if (bmp != null) {
                            canvas.drawBitmap(
                                bmp, null,
                                android.graphics.RectF(
                                    o.optDouble("x", 0.0).toFloat(),
                                    o.optDouble("y", 0.0).toFloat(),
                                    (o.optDouble("x", 0.0) + o.optDouble("w", 0.0)).toFloat(),
                                    (o.optDouble("y", 0.0) + o.optDouble("h", 0.0)).toFloat(),
                                ),
                                paint
                            )
                        }
                    } else if (o.optString("t") == "text") {
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

        // 1.5) 状态行：核心 / 插件 / 版本（不依赖核心也能看到）
        val coreOk = runCatching { NativeCore.isLoaded() }.getOrDefault(false)
        val pluginCount = runCatching { PluginStore.scan(context).count { it.enabled } }.getOrDefault(0)
        textPaint.color = if (coreOk) Color.parseColor("#FF8AB4F8").toInt()
        else Color.parseColor("#FFFF6B6B").toInt()
        textPaint.textSize = 24f
        canvas.drawText(
            "核心 ${if (coreOk) "已加载" else "未加载"} · 插件 $pluginCount 个 · 修改版本 1.6.15.3",
            24f, 44f, textPaint,
        )

        // 2) 左上角：核心日志最近几行
        val logs = runCatching { NativeCore.logsJson() }.getOrNull()
        if (!logs.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(logs)
                val start = (arr.length() - 8).coerceAtLeast(0)
                textPaint.color = Color.parseColor("#CCFFFFFF").toInt()
                textPaint.textSize = 22f
                var y = 80f
                for (i in start until arr.length()) {
                    val line = arr.getString(i)
                    canvas.drawText(line.take(64), 24f, y, textPaint)
                    y += 28f
                }
            }
        }

        // 3) 右上角调试信息
        val now = System.currentTimeMillis()
        val dt = (now - lastFrame).coerceAtLeast(1)
        lastFrame = now
        fps = if (fps == 0f) 1000f / dt else fps * 0.9f + (1000f / dt) * 0.1f
        textPaint.color = Color.parseColor("#88FFFFFF").toInt()
        textPaint.textSize = 24f
        canvas.drawText("%.0f fps".format(fps), width - 140f, 60f, textPaint)

        // 3) 左下角状态条
        val count = runCatching { PluginStore.scan(context).count { it.enabled } }.getOrDefault(0)
        val label = "插件 $count 个 · 修改版本 1.6.15.3"
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
