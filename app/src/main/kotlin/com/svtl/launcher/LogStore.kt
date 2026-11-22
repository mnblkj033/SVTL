package com.svtl.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志：校验、解压、加载核心都会往里写。 */
object LogStore {

    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val lines: List<String>
        get() = buffer.toList()

    fun add(text: String) {
        buffer.addLast("${fmt.format(Date())}  $text")
        while (buffer.size > 500) buffer.removeFirst()
    }

    fun clear() = buffer.clear()
}
