package com.svtl.launcher

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志：校验、解压、加载核心都会往里写。 */
object LogStore {

    val lines = mutableStateListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(text: String) {
        lines.add("${fmt.format(Date())}  $text")
        if (lines.size > 500) lines.removeRange(0, lines.size - 500)
    }

    fun clear() = lines.clear()
}
