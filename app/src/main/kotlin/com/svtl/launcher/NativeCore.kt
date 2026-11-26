package com.svtl.launcher

/**
 * 核心库从解压出来的包目录里加载（System.load 绝对路径），
 * 不在 APK 里自带，所以启动器本身不含核心，靠包提供。
 * JNI 函数在 Rust 核心 lib.rs 里导出：Java_com_svtl_launcher_NativeCore_*
 */
object NativeCore {

    @Volatile
    private var loaded = false

    /** 优先从 APK 自带库加载，失败再试压缩包里的绝对路径。 */
    /** 只从 APK 自带的 jniLibs 加载，避免加载到压缩包里的旧版本。 */
    fun load(absolutePath: String?): Boolean {
        if (loaded) return true
        loaded = runCatching { System.loadLibrary("svtl") }.isSuccess
        android.util.Log.i("SVTL", "core loaded = $loaded")
        return loaded
    }

    fun isLoaded(): Boolean = loaded

    external fun init(dataRoot: String): Int
    /** 在启动器自己的进程里初始化 Mono 并加载游戏程序集。 */
    external fun monoBoot(packDir: String): Int
    external fun installHooks(): Int
    external fun loadPlugins(dir: String, gameVersion: String): Int
    external fun tick(dt: Double)
    external fun stateJson(): String
    external fun drawJson(): String
    external fun drawClear()
    external fun logsJson(): String
    external fun hookCount(): Int
    external fun uiTap(x: Float, y: Float): Int
    external fun touchEnabled(): Int
    external fun reloadPlugins(dir: String, gameVersion: String): Int
    external fun probeStart(): Int
}
