package com.bakamusic.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(0, 0), navigationBarStyle = SystemBarStyle.auto(0, 0))
        setContent { BakaTheme { SettingsScreen() } }
    }
}

@Composable private fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { PluginManager(context.applicationContext) }
    val repository = remember { AppRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf(manager.allPlugins()) }
    var subscriptions by remember { mutableStateOf(manager.subscriptions()) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var subscriptionName by rememberSaveable { mutableStateOf("") }
    var subscriptionUrl by rememberSaveable { mutableStateOf("") }
    var networkUrl by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    val playQuality by repository.playQuality.collectAsStateWithLifecycle(initialValue = "320k")
    val downloadQuality by repository.downloadQuality.collectAsStateWithLifecycle(initialValue = "flac")
    val refresh = { plugins = manager.allPlugins(); subscriptions = manager.subscriptions() }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                // 优先取系统返回的显示文件名，保留 .js/.json 后缀
                var name = "plugin.js"
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && idx >= 0) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                        }
                    }
                }
                if (!name.endsWith(".js", true) && !name.endsWith(".json", true)) {
                    // 无后缀时按 .js 处理
                    name = "$name.js"
                }
                name = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "plugin.js" }
                // 直接使用原始文件名，保证回退名与去重 id 稳定
                val target = File(context.cacheDir, name)
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取插件文件" }
                input.use { source -> target.outputStream().use { source.copyTo(it) } }
                try {
                    manager.installLocal(target).getOrThrow()
                } finally {
                    runCatching { target.delete() }
                }
            }
            if (result.isSuccess) {
                // 本地安装后立即重载运行时
                runCatching { MusicSourceService.refreshAll() }
            }
            notice = result.fold({ "已安装插件：${it.name} ${it.version}" }, { "安装失败：${it.message}" }); refresh()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)) {
        item { Text("设置", Modifier.padding(horizontal = 22.dp, vertical = 18.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item {
            SettingGroup("播放") {
                SettingRow("默认播放音质", MusicQuality.labelOf(playQuality), Icons.Default.HighQuality) { dialog = "playQuality" }
                SettingRow("默认下载音质", MusicQuality.labelOf(downloadQuality), Icons.Default.Download) { dialog = "downloadQuality" }
            }
        }
        item { SettingGroup("下载与文件") { SettingRow("下载目录", "Music/BakaMusic", Icons.Default.Folder) {}; SettingRow("下载后转码", "关闭", Icons.Default.Transform) {} } }
        item { PluginManagementGroup(plugins, subscriptions, loading, onInstallLocal = { localPicker.launch(arrayOf("*/*")) }, onInstallNetwork = { dialog = "network" }, onAddSubscription = { dialog = "subscription" }, onUpdate = { item -> scope.launch { loading = true; val result = manager.updateSubscription(item); if (result.isSuccess) { runCatching { MusicSourceService.refreshAll() } }; loading = false; notice = result.fold({ "${item.name} 更新完成，导入 $it 个插件" }, { "更新失败：${it.message ?: "未知错误"}" }); refresh() } }, onDeleteSubscription = { item -> manager.removeSubscription(item.id); refresh(); notice = "已删除订阅：${item.name}" }, onToggle = { item, enabled -> manager.setEnabled(item.id, enabled); refresh(); scope.launch { runCatching { MusicSourceService.refreshAll() } } }, onRemove = { item -> manager.remove(item.id); refresh(); notice = "已卸载：${item.name}"; scope.launch { runCatching { MusicSourceService.refreshAll() } } }, onMove = { item, delta -> manager.move(item.id, delta); refresh(); scope.launch { runCatching { MusicSourceService.refreshAll() } } }) }
        item { SettingGroup("界面") { SettingRow("主题颜色", "跟随系统动态取色", Icons.Default.Palette) {}; SettingRow("关于 Baka Music", "v1.0.0", Icons.Default.Info) {} } }
    }
    when (dialog) {
        "playQuality" -> QualitySelectDialog("默认播放音质", playQuality, { scope.launch { repository.setPlayQuality(it) }; dialog = null }, { dialog = null })
        "downloadQuality" -> QualitySelectDialog("默认下载音质", downloadQuality, { scope.launch { repository.setDownloadQuality(it) }; dialog = null }, { dialog = null })
        "subscription" -> TextInputDialog("添加插件订阅", "订阅名称", subscriptionName, { subscriptionName = it }, "订阅地址（JSON 或 JS）", subscriptionUrl, { subscriptionUrl = it }, "保存", {
            scope.launch { val result = manager.addSubscription(subscriptionName, subscriptionUrl); notice = result.fold({ "订阅已保存：${it.name}" }, { "保存失败：${it.message}" }); if (result.isSuccess) { subscriptionName = ""; subscriptionUrl = ""; dialog = null; refresh() } }
        }, { dialog = null })
        "network" -> TextInputDialog("网络安装插件", "插件地址（JSON 或 JS）", networkUrl, { networkUrl = it }, null, "", { _: String -> }, "安装", {
            scope.launch { loading = true; val result = manager.installNetwork(networkUrl); if (result.isSuccess) { runCatching { MusicSourceService.refreshAll() } }; loading = false; notice = result.fold({ "安装完成，导入 $it 个插件" }, { "安装失败：${it.message ?: "未知错误"}" }); if (result.isSuccess) { networkUrl = ""; dialog = null; refresh() } }
        }, { dialog = null })
    }
    notice?.let { text -> AlertDialog(onDismissRequest = { notice = null }, title = { Text("插件管理") }, text = { Text(text) }, confirmButton = { TextButton({ notice = null }) { Text("确定") } }) }
}

@Composable private fun PluginManagementGroup(plugins: List<InstalledPlugin>, subscriptions: List<PluginSubscription>, loading: Boolean, onInstallLocal: () -> Unit, onInstallNetwork: () -> Unit, onAddSubscription: () -> Unit, onUpdate: (PluginSubscription) -> Unit, onDeleteSubscription: (PluginSubscription) -> Unit, onToggle: (InstalledPlugin, Boolean) -> Unit, onRemove: (InstalledPlugin) -> Unit, onMove: (InstalledPlugin, Int) -> Unit) {
    Text("插件管理", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Card(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onInstallLocal, enabled = !loading, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Text("本地安装") }; Button(onClick = onInstallNetwork, enabled = !loading, modifier = Modifier.weight(1f)) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Language, null); Spacer(Modifier.width(6.dp)); Text(if (loading) "处理中" else "网络安装") } }
            var priorityExpanded by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { priorityExpanded = !priorityExpanded }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("音源优先级", Modifier.weight(1f), fontWeight = FontWeight.Bold); Icon(if (priorityExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开") }
            val activePlugins = plugins.filter { it.enabled }
            if (priorityExpanded) { if (activePlugins.isEmpty()) Text("暂无启用的音源插件", Modifier.padding(16.dp)); activePlugins.forEachIndexed { index, plugin -> PriorityRow(plugin, index, activePlugins.lastIndex, onMove) } }
            HorizontalDivider()
            var installedExpanded by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { installedExpanded = !installedExpanded }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("已安装插件", Modifier.weight(1f), fontWeight = FontWeight.Bold); Icon(if (installedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开") }
            if (installedExpanded) { if (plugins.isEmpty()) Text("暂无已安装插件", Modifier.padding(16.dp)); plugins.forEach { plugin -> ListItem(headlineContent = { Text(plugin.name) }, supportingContent = { Text("${plugin.version} · ${if (plugin.enabled) "已启用" else "已禁用"}") }, leadingContent = { Icon(Icons.Default.Extension, null) }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = plugin.enabled, onCheckedChange = { onToggle(plugin, it) }); IconButton(onClick = { onRemove(plugin) }) { Icon(Icons.Default.Delete, "卸载") } } }) } }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("订阅源", Modifier.weight(1f), fontWeight = FontWeight.Bold); TextButton(onClick = onAddSubscription) { Icon(Icons.Default.Add, null); Text("添加订阅") } }
            if (subscriptions.isEmpty()) Text("暂无订阅，点击添加订阅", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            subscriptions.forEach { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.url) }, leadingContent = { Icon(Icons.Default.RssFeed, null) }, trailingContent = { Row { IconButton(onClick = { onUpdate(item) }, enabled = !loading) { Icon(Icons.Default.Refresh, "更新订阅") }; IconButton(onClick = { onDeleteSubscription(item) }, enabled = !loading) { Icon(Icons.Default.Delete, "删除订阅") } } }) }
        }
    }
}

@Composable private fun PriorityRow(plugin: InstalledPlugin, index: Int, lastIndex: Int, onMove: (InstalledPlugin, Int) -> Unit) { var distance = 0f; ListItem(headlineContent = { Text("${index + 1}. ${plugin.name}") }, supportingContent = { Text("长按拖动或使用箭头调整优先级") }, leadingContent = { Icon(Icons.Default.DragHandle, null, modifier = Modifier.pointerInput(plugin.id) { detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { _, amount -> distance += amount.y; if (distance > 56f && index < lastIndex) { onMove(plugin, 1); distance = 0f }; if (distance < -56f && index > 0) { onMove(plugin, -1); distance = 0f } }) }) }, trailingContent = { Row { IconButton(onClick = { onMove(plugin, -1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "上移") }; IconButton(onClick = { onMove(plugin, 1) }, enabled = index < lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "下移") } } }) }

@Composable private fun QualitySelectDialog(title: String, current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                QUALITY_KEYS_ORDERED.reversed().forEach { key ->
                    TextButton(onClick = { onSelect(key) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(MusicQuality.labelOf(key), Modifier.weight(1f))
                            if (key == current) Icon(Icons.Default.Check, "当前")
                        }
                    }
                }
            }
        },
        confirmButton = {})
}

@Composable private fun TextInputDialog(title: String, labelOne: String, valueOne: String, onValueOne: (String) -> Unit, labelTwo: String?, valueTwo: String, onValueTwo: (String) -> Unit, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(valueOne, onValueOne, label = { Text(labelOne) }, singleLine = true); if (labelTwo != null) OutlinedTextField(valueTwo, onValueTwo, label = { Text(labelTwo) }, singleLine = true) } }, confirmButton = { TextButton(onClick = onConfirm, enabled = valueOne.isNotBlank()) { Text(confirm) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Text(title, Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Card(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) { Column(content = content) } }
@Composable private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onClick)) }
