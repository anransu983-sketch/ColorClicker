package com.example.colorclicker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TAGS = listOf("全部", "截图", "匹配", "点击", "规则", "服务", "启动", "崩溃", "记忆", "守卫", "性能", "复盘")

private fun levelColor(level: String) = when (level) {
    "ERROR" -> Color(0xFFD32F2F)
    "WARN" -> Color(0xFFEF6C00)
    "DEBUG" -> Color(0xFF757575)
    else -> Color.Unspecified
}

/** 应用内详细日志页：实时看、按分类/级别筛、清空、分享导出 */
@Composable
fun LogScreen(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tag by remember { mutableStateOf("全部") }
    var onlyErrors by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    var recentOnly by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // 近5分钟开着时每 15 秒重算一次，否则旧日志会一直赖在列表里
    LaunchedEffect(recentOnly) {
        if (recentOnly) {
            while (true) {
                delay(15_000)
                tick++
            }
        }
    }

    val shown = remember(AppLog.lines.size, tag, onlyErrors, recentOnly, tick) {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000
        AppLog.lines.filter {
            (tag == "全部" || it.tag == tag) && (!onlyErrors || it.level == "ERROR" || it.level == "WARN") &&
                (!recentOnly || it.time >= cutoff)
        }
    }

    LaunchedEffect(shown.size) {
        if (autoScroll && shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("详细日志", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text("关闭") }
                }
                Text("共 ${AppLog.lines.size} 条，当前显示 ${shown.size} 条（最多保留 ${AppLog.MAX_LINES} 条），日志保存在本机文件 logs/app.log，崩溃重启后仍可查看", style = MaterialTheme.typography.bodySmall)
                Text("带坐标的命中日志可点「模拟点击」，在该坐标模拟一次手动点击", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TAGS.forEach { t ->
                        FilterChip(tag == t, { tag = t }, { Text(t) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(onlyErrors, { onlyErrors = it })
                        Text("仅警告/错误", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(autoScroll, { autoScroll = it })
                        Text("自动滚到底", fontSize = 13.sp)
                    }
                    FilterChip(recentOnly, { recentOnly = !recentOnly }, { Text("近5分钟") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        AppLog.clear()
                        AppLog.i("服务", "日志已清空")
                    }) { Text("清空") }
                    // 0.25：以前是整段文字塞进 Intent，日志一大（能到 2MB）就超 Binder 上限、分享打不开。
                    // 改成把当前显示的内容写成 .txt 文件再分享，近5分钟和全部都能发。
                    OutlinedButton({
                        val text = shown.joinToString("\n") { it.format() }
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                        val dir = File(ctx.cacheDir, "log_exports").apply { if (!exists()) mkdirs() }
                        val f = File(dir, "colorclicker-log-$stamp.txt")
                        f.writeText(text)
                        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ColorClicker 日志")
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "导出日志"))
                        AppLog.i("服务", "日志已导出 ${shown.size} 条到文件分享")
                    }) { Text("分享导出") }
                    OutlinedButton({
                        val text = shown.joinToString("\n") { it.format() }
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ColorClicker 日志", text))
                            Toast.makeText(ctx, "已复制 ${shown.size} 条日志", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "复制失败：内容太长，先开「近5分钟」再复制", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("复制") }
                }
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (recentOnly) "近5分钟内暂无日志" else "暂无日志", color = Color.Gray)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(shown) { l ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(
                                    if (l.level == "ERROR") Color(0xFFFFEBEE) else Color.Transparent
                                ).padding(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    l.format(),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = levelColor(l.level),
                                    modifier = Modifier.weight(1f)
                                )
                                if (l.x != null && l.y != null) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                AppLog.i("点击", "手动点击 (${l.x},${l.y})（来自日志）")
                                                val ok = ClickService.tap(l.x, l.y)
                                                if (ok) AppLog.i("点击", "手动点击 (${l.x},${l.y}) 已发出")
                                                else AppLog.e("点击", "手动点击 (${l.x},${l.y}) 失败：无障碍服务未连接或手势被拒")
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) { Text("模拟点击", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
