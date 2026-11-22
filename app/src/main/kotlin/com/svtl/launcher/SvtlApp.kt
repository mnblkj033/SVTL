package com.svtl.launcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

/**
 * 合并包里我们就是游戏本身，所以不需要悬浮窗权限：
 * 直接把叠加层 View 加进游戏 Activity 自己的窗口就行。
 */
class SvtlApp : Application() {

    private var overlay: View? = null
    private var host: Activity? = null
    private var ticker: Thread? = null

    override fun onCreate() {
        super.onCreate()

        // 驱动插件（没有钩子时，插件靠这个每 100ms 跑一次）
        ticker = Thread {
            while (true) {
                runCatching { if (NativeCore.isLoaded()) NativeCore.tick(0.1) }
                Thread.sleep(100)
            }
        }.apply { isDaemon = true; start() }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is LauncherActivity) {
                    detach()
                    return
                }
                // 游戏画面出来了，关掉启动过渡页
                if (activity !is LoadingActivity) LoadingActivity.dismiss()
                // 不管核心有没有加载都挂上：没加载时叠加层会直接显示原因
                attach(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity !is LauncherActivity) detach()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === host) detach()
            }
        })
    }

    private fun attach(activity: Activity) {
        if (overlay != null && host === activity) return
        detach()
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val v = OverlayView(activity)
        runCatching {
            decor.addView(
                v,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        overlay = v
        host = activity
        LogStore.add("叠加层已挂到 ${activity.javaClass.simpleName}")
    }

    private fun detach() {
        val v = overlay ?: return
        runCatching {
            (v.parent as? ViewGroup)?.removeView(v)
            (v as? OverlayView)?.stop()
        }
        overlay = null
        host = null
    }
}
