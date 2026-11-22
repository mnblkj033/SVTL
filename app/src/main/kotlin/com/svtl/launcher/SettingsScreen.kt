package com.svtl.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.svtl.manager.BuildConfig

@Composable
internal fun SettingsScreen(
    settings: SettingsStore,
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onClearLogs: () -> Unit,
) {
    var verify by remember { mutableStateOf(settings.verifyBeforeLaunch) }
    var clean by remember { mutableStateOf(settings.cleanCacheAfterLaunch) }
    var logs by remember { mutableStateOf(settings.showLogs) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Text("外观", style = MaterialTheme.typography.titleSmall)
        listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeChange(value) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = themeMode == value, onClick = { onThemeChange(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("启动", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        SettingSwitch(
            title = "启动前校验",
            subtitle = "对比包内校验码，防止包损坏",
            checked = verify,
            onChange = {
                verify = it
                settings.verifyBeforeLaunch = it
            },
        )
        SettingSwitch(
            title = "启动后清理缓存",
            subtitle = "解压出来的文件用完即删",
            checked = clean,
            onChange = {
                clean = it
                settings.cleanCacheAfterLaunch = it
            },
        )
        SettingSwitch(
            title = "记录详细日志",
            subtitle = "在日志页保留每一步操作",
            checked = logs,
            onChange = {
                logs = it
                settings.showLogs = it
            },
        )

        Text("维护", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = onClearCache) { Text("清理解压缓存") }
        TextButton(onClick = onClearLogs) { Text("清空日志") }

        Text("关于", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            "SVTL 启动器 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "布局参考 ZalithLauncher2",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
