package com.bakamusic.android.ui

import android.net.Uri
import com.bakamusic.android.data.*
import com.bakamusic.android.service.*
import com.bakamusic.android.plugin.*
import com.bakamusic.android.util.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File

class SettingsActivity : ComponentActivity() {
    companion object {
        /** 首页无插件引导跳转时携带 true，设置页自动定位到“插件管理” */
        const val EXTRA_SCROLL_TO_PLUGIN = "scroll_to_plugin"
    }
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
    val scrollToPlugin = (context as? android.app.Activity)?.intent
        ?.getBooleanExtra(SettingsActivity.EXTRA_SCROLL_TO_PLUGIN, false) == true
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToPlugin) {
        if (scrollToPlugin) runCatching { listState.scrollToItem(PLUGIN_GROUP_INDEX) }
    }
    var plugins by remember { mutableStateOf(manager.allPlugins()) }
    var subscriptions by remember { mutableStateOf(manager.subscriptions()) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var subscriptionName by rememberSaveable { mutableStateOf("") }
    var subscriptionUrl by rememberSaveable { mutableStateOf("") }
    var networkUrl by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(false) }
    val playQuality by repository.playQuality.collectAsStateWithLifecycle(initialValue = "flac")
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
                    // 无后缀时默认添加 .js 后缀
                    name = "$name.js"
                }
                name = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "plugin.js" }
                // 使用原始文件名暂存，以保证类型识别与去重稳定
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
    Scaffold { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 18.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            )
        ) {
            item {
                Text(
                    "设置",
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item {
                SettingGroup("播放") {
                    SettingRow(
                        "默认播放音质",
                        MusicQuality.labelOf(playQuality),
                        Icons.Default.HighQuality
                    ) { dialog = "playQuality" }
                    SettingRow(
                        "默认下载音质",
                        MusicQuality.labelOf(downloadQuality),
                        Icons.Default.Download
                    ) { dialog = "downloadQuality" }
                }
            }
            item {
                SettingGroup("下载与文件") {
                    SettingRow("下载目录", "Music/BakaMusic", Icons.Default.Folder) {}; SettingRow(
                    "下载后转码",
                    "关闭",
                    Icons.Default.Transform
                ) {}
                }
            }
            item {
                PluginManagementGroup(
                    plugins,
                    subscriptions,
                    loading,
                    onInstallLocal = { localPicker.launch(arrayOf("*/*")) },
                    onInstallNetwork = { dialog = "network" },
                    onAddSubscription = { dialog = "subscription" },
                    onUpdate = { item ->
                        scope.launch {
                            loading = true; val result = manager.updateSubscription(item); if (result.isSuccess) {
                            runCatching { MusicSourceService.refreshAll() }
                        }; loading = false; notice =
                            result.fold({ "${item.name} 更新完成，导入 ${it.installed} 个插件${it.skippedNotice()}" }, { "更新失败：${it.message ?: "未知错误"}" }); refresh()
                        }
                    },
                    onDeleteSubscription = { item ->
                        manager.removeSubscription(item.id); refresh(); notice = "已删除订阅：${item.name}"
                    },
                    onToggle = { item, enabled ->
                        manager.setEnabled(item.id, enabled); refresh(); scope.launch {
                        runCatching { MusicSourceService.refreshAll() }
                    }
                    },
                    onRemove = { item ->
                        manager.remove(item.id); refresh(); notice = "已卸载：${item.name}"; scope.launch {
                        runCatching { MusicSourceService.refreshAll() }
                    }
                    },
                    onMove = { item, delta ->
                        manager.move(item.id, delta); refresh(); scope.launch {
                        runCatching { MusicSourceService.refreshAll() }
                    }
                    })
            }
            item {
                SettingGroup("界面") {
                    SettingRow("主题颜色", "跟随系统动态取色", Icons.Default.Palette) {}; SettingRow(
                    "关于 Baka Music",
                    "v1.0.0",
                    Icons.Default.Info
                ) {}
                }
            }
        }
    }
    when (dialog) {
        "playQuality" -> QualitySelectDialog("默认播放音质", playQuality, { scope.launch { repository.setPlayQuality(it) }; dialog = null }, { dialog = null })
        "downloadQuality" -> QualitySelectDialog("默认下载音质", downloadQuality, { scope.launch { repository.setDownloadQuality(it) }; dialog = null }, { dialog = null })
        "subscription" -> TextInputDialog("添加插件订阅", "订阅名称", subscriptionName, { subscriptionName = it }, "订阅地址（JSON 或 JS）", subscriptionUrl, { subscriptionUrl = it }, "保存", {
            scope.launch { val result = manager.addSubscription(subscriptionName, subscriptionUrl); notice = result.fold({ "订阅已保存：${it.name}" }, { "保存失败：${it.message}" }); if (result.isSuccess) { subscriptionName = ""; subscriptionUrl = ""; dialog = null; refresh() } }
        }, { dialog = null })
        "network" -> TextInputDialog("网络安装插件", "插件地址（JSON 或 JS）", networkUrl, { networkUrl = it }, null, "", { _: String -> }, "安装", {
            // 点击安装后立即隐藏弹窗，后台继续安装
            val url = networkUrl
            networkUrl = ""
            dialog = null
            scope.launch { loading = true; val result = manager.installNetwork(url); if (result.isSuccess) { runCatching { MusicSourceService.refreshAll() } }; loading = false; notice = result.fold({ "安装完成，导入 ${it.installed} 个插件${it.skippedNotice()}" }, { "安装失败：${it.message ?: "未知错误"}" }); if (result.isSuccess) { refresh() } }
        }, { dialog = null })
    }
    notice?.let { text -> AlertDialog(onDismissRequest = { notice = null }, title = { Text("插件管理") }, text = { Text(text) }, confirmButton = { TextButton({ notice = null }) { Text("确定") } }) }
}

@Composable private fun PluginManagementGroup(plugins: List<InstalledPlugin>, subscriptions: List<PluginSubscription>, loading: Boolean, onInstallLocal: () -> Unit, onInstallNetwork: () -> Unit, onAddSubscription: () -> Unit, onUpdate: (PluginSubscription) -> Unit, onDeleteSubscription: (PluginSubscription) -> Unit, onToggle: (InstalledPlugin, Boolean) -> Unit, onRemove: (InstalledPlugin) -> Unit, onMove: (InstalledPlugin, Int) -> Unit) {
    Text("插件管理", Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    SettingsGroupCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onInstallLocal, enabled = !loading, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Text("本地安装") }; Button(onClick = onInstallNetwork, enabled = !loading, modifier = Modifier.weight(1f)) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Language, null); Spacer(Modifier.width(6.dp)); Text(if (loading) "处理中" else "网络安装") } }
            var installedExpanded by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { installedExpanded = !installedExpanded }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("已安装插件", fontWeight = FontWeight.Bold); Text("禁用或删除插件，长按拖动可调整优先级", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (installedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开") }
            if (installedExpanded) { if (plugins.isEmpty()) Text("暂无已安装插件", Modifier.padding(16.dp)) else { plugins.forEachIndexed { index, plugin -> key(plugin.id) { InstalledPluginRow(plugin, index, plugins.lastIndex, onToggle, onRemove, onMove) } } } }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("订阅源", Modifier.weight(1f), fontWeight = FontWeight.Bold); TextButton(onClick = onAddSubscription) { Icon(Icons.Default.Add, null); Text("添加订阅") } }
            if (subscriptions.isEmpty()) Text("暂无订阅，点击添加订阅", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp))
            subscriptions.forEach { item -> ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.url) }, leadingContent = { Icon(Icons.Default.RssFeed, null) }, trailingContent = { Row { IconButton(onClick = { onUpdate(item) }, enabled = !loading) { Icon(Icons.Default.Refresh, "更新订阅") }; IconButton(onClick = { onDeleteSubscription(item) }, enabled = !loading) { Icon(Icons.Default.Delete, "删除订阅") } } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
    }
}

@Composable private fun InstalledPluginRow(plugin: InstalledPlugin, position: Int, lastIndex: Int, onToggle: (InstalledPlugin, Boolean) -> Unit, onRemove: (InstalledPlugin) -> Unit, onMove: (InstalledPlugin, Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    // 自拖拽起点累计的位移，手势回调中仅更新该值，不进行换位判断，避免引用过期状态
    var totalDrag by remember { mutableStateOf(0f) }
    var startSlot by remember { mutableStateOf(0) }
    var itemHeightPx by remember { mutableStateOf(0) }
    val h = if (itemHeightPx > 0) itemHeightPx.toFloat() else with(LocalDensity.current) { 72.dp.toPx() }
    val latestPosition = rememberUpdatedState(position)
    // 将拖拽位移钳制在列表范围内，避免列表项被拖出边界
    val boundedDrag = totalDrag.coerceIn((0 - startSlot) * h, (lastIndex - startSlot) * h)
    // 根据当前位置计算目标槽位（超过半格则换位），依据最新位置逐步调整
    val targetSlot = (startSlot + boundedDrag / h).roundToInt().coerceIn(0, lastIndex)
    LaunchedEffect(totalDrag, position, dragging) {
        if (!dragging) return@LaunchedEffect
        if (targetSlot > position) onMove(plugin, 1)
        else if (targetSlot < position) onMove(plugin, -1)
    }
    // 列表项位移等于累计位移减去已换位补偿，始终与触摸位置一致
    val currentTranslation = if (dragging) boundedDrag - (position - startSlot) * h else 0f
    // 拖拽中直接取值显示（与布局同帧提交，避免换位抖动）；松手后回位
    val settleAnim by animateFloatAsState(targetValue = currentTranslation, animationSpec = if (dragging) snap() else spring(), label = "dragSettle")
    val dragScale by animateFloatAsState(targetValue = if (dragging) 1.03f else 1f, animationSpec = spring(), label = "dragScale")
    ListItem(
        headlineContent = { Text("${position + 1}. ${plugin.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${plugin.version} · ${if (plugin.enabled) "已启用" else "已禁用"}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(Icons.Default.DragHandle, null)
        },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = plugin.enabled, onCheckedChange = { onToggle(plugin, it) }); IconButton(onClick = { onRemove(plugin) }) { Icon(Icons.Default.Delete, "卸载") } } },
        modifier = Modifier.onSizeChanged { itemHeightPx = it.height }.zIndex(if (dragging) 1f else 0f).graphicsLayer {
            translationY = if (dragging) currentTranslation else settleAnim
            scaleX = dragScale
            scaleY = dragScale
            shadowElevation = if (dragging) 16.dp.toPx() else 0f
            clip = false
        }.pointerInput(plugin.id) {
            // 列表项支持长按拖拽：短按点击仍传递给开关和删除按钮
            detectDragGesturesAfterLongPress(
                onDragStart = { startSlot = latestPosition.value; totalDrag = 0f; dragging = true },
                onDragCancel = { dragging = false; totalDrag = 0f },
                onDragEnd = { dragging = false; totalDrag = 0f },
                onDrag = { _, amount -> totalDrag = (totalDrag + amount.y).coerceIn(-10000f, 10000f) }
            )
        },
        colors = ListItemDefaults.colors(containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
    )
}

@Composable private fun QualitySelectDialog(title: String, current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                QUALITY_KEYS_ORDERED.reversed().forEach { key ->
                    TextButton(onClick = { onSelect(key) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val selected = key == current
                            Text(
                                MusicQuality.labelOf(key),
                                Modifier.weight(1f),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (selected) Icon(Icons.Default.Check, "当前", tint = MaterialTheme.colorScheme.primary)
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

/** 设置页 LazyColumn 中“插件管理”分组的下标：标题0/播放1/下载2/插件管理3 */
private const val PLUGIN_GROUP_INDEX = 3

/** 设置页分组卡片容器，样式跟随主题 */
@Composable private fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) { Column(content = content) }
}

@Composable private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Text(title, Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); SettingsGroupCard(content = content) }
@Composable private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onClick), colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
