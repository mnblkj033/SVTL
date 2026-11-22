package com.svtl.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val STORAGE_ROOT = "/storage/emulated/0"

/** 只读浏览 /storage/emulated/0 下的文件夹和文件。 */
@Composable
internal fun FileBrowserScreen(onBack: () -> Unit) {
    val root = remember { File(STORAGE_ROOT) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = root.listFiles()
            if (list == null) {
                error = "读不了 $STORAGE_ROOT，检查权限"
                LogStore.add("文件浏览失败：无权限")
            } else {
                entries = list.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
                )
                LogStore.add("文件浏览：${entries.size} 项")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(STORAGE_ROOT, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("${entries.size} 项", style = MaterialTheme.typography.bodySmall)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (f.isDirectory) "📁" else "📄")
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(f.name)
                        if (!f.isDirectory) {
                            Text(
                                "${f.length() / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
