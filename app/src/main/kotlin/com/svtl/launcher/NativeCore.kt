package com.svtl.launcher

/**
 * 核心库从解压出来的包目录里加载（System.load 绝对路径），
 * 不在 APK 里自带，所以启动器本身不含核心，靠包提供。
 * JNI 函数在 Rust 核心 lib.rs 里导出：Java_com_svtl_launcher_NativeCore_*
 */
object NativeCore {

    @Volatile
    private var loaded = false

    /** 加载包里的 lib/arm64-v8a/libsvtl.so，成功返回 true。 */
    fun load(absolutePath: String): Boolean {
        if (loaded) return true
        loaded = runCatching { System.load(absolutePath) }.isSuccess
        return loaded
    }

    fun isLoaded(): Boolean = loaded

    external fun init(dataRoot: String): Int
    external fun installHooks(): Int
    external fun loadPlugins(dir: String, gameVersion: String): Int
    external fun tick(dt: Double)
    external fun stateJson(): String
    external fun drawJson(): String
    external fun logsJson(): String
    external fun hookCount(): Int
}
