package com.svtl.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 启动游戏时的过渡页：中间一块磨砂感的圆角卡片 + 旋转条。
 * 游戏画面出来（SvtlApp 收到 onActivityResumed）后自动关闭。
 */
class LoadingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#66000000")))

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(32), dp(40), dp(32))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6FFFFFF"))
                cornerRadius = dp(28).toFloat()
            }
            addView(ProgressBar(this@LoadingActivity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B8860B"))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(this@LoadingActivity).apply {
                text = "正在启动星露谷…"
                textSize = 15f
                setTextColor(Color.parseColor("#1B1A17"))
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }

        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(card)
        }
        setContentView(root)
        current = this
        // 兜底：15 秒还没等到游戏就自己关掉，别卡住用户
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, 15_000)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var current: LoadingActivity? = null
            private set

        /** 游戏画面出来后调用。 */
        fun dismiss() {
            current?.let { a -> a.runOnUiThread { a.finish() } }
            current = null
        }
    }
}
