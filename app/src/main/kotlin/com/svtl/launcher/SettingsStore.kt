package com.svtl.launcher

import android.content.Context

/** 简单偏好存储：主题、启动前校验、启动后清缓存、日志开关。 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("svtl", Context.MODE_PRIVATE)

    /** 0 = 跟随系统，1 = 浅色，2 = 深色 */
    var themeMode: Int
        get() = prefs.getInt("theme", 0)
        set(value) = prefs.edit().putInt("theme", value).apply()

    var verifyBeforeLaunch: Boolean
        get() = prefs.getBoolean("verify", true)
        set(value) = prefs.edit().putBoolean("verify", value).apply()

    var cleanCacheAfterLaunch: Boolean
        get() = prefs.getBoolean("clean", true)
        set(value) = prefs.edit().putBoolean("clean", value).apply()

    var showLogs: Boolean
        get() = prefs.getBoolean("logs", false)
        set(value) = prefs.edit().putBoolean("logs", value).apply()
}
