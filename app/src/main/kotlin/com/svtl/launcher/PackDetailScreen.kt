package com.svtl.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PackDetailScreen(
    pack: PackInfo,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var verified by remember { mutableStateOf<Boolean?>(null) }
    var entries by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    LaunchedEffect(pack.file.absolutePath) {
        withContext(Dispatchers.IO) {
            entries = PackageRepository.listEntries(pack)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(pack.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        Text("版本 ${pack.version} · 游戏 ${pack.gameVersion}", style = MaterialTheme.typography.bodySmall)
        Text(
            "${pack.entryCount} 个文件 · ${pack.size / 1024 / 1024} MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "校验码 ${pack.checksum.take(16)}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (verified) {
            true -> Text("校验通过", color = MaterialTheme.colorScheme.primary)
            false -> Text("校验失败", color = MaterialTheme.colorScheme.error)
            null -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    scope.launch {
                        verified = withContext(Dispatchers.IO) { PackageRepository.verify(pack) }
                        LogStore.add("校验 ${pack.name}：$verified")
                    }
                }
            ) { Text("校验") }

            Button(onClick = onInstall, enabled = !busy) { Text("安装并启动") }

            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }

        Text("包内文件", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries.take(200)) { (name, size) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (size >= 1024 * 1024) "${size / 1024 / 1024} MB" else "${size / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
