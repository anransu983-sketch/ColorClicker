package com.example.colorclicker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == RESULT_OK && d != null) {
                AppLog.i("截图", "已拿到截屏授权，启动截屏服务")
                ContextCompat.startForegroundService(
                    this, Intent(this, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", d)
                )
            } else {
                AppLog.w("截图", "截屏授权被拒绝或取消")
            }
        }

    fun startProjection() =
        projLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        AppLog.init(this)
        val ok = OpenCVLoader.initLocal()
        AppLog.i("启动", "OpenCV 初始化" + if (ok) "成功" else "失败")
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    fun Screen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val rules = remember { mutableStateListOf<Rule>().apply { addAll(RuleStore.load(ctx)) } }
        var showAdd by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var updateInfo by remember { mutableStateOf<Updater.UpdateInfo?>(null) }
        var updateProgress by remember { mutableStateOf(-1) }   // -1 未下载，0~100 下载中
        var updateError by remember { mutableStateOf("") }
        var updateChecked by remember { mutableStateOf(false) }
        var loopLimit by remember { mutableStateOf(RuleStore.getLoopLimit(ctx).toString()) }
        fun persist() = RuleStore.save(ctx, rules.toList())

        // 启动后静默检查一次更新（没网/没新版时什么都不弹）
        LaunchedEffect(Unit) {
            val info = withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.hasUpdate(ctx) }
            if (info != null) updateInfo = info
        }

        Scaffold(floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } }) { pad ->
            LazyColumn(
                Modifier.padding(pad).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text("图色点击器", style = MaterialTheme.typography.headlineSmall) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("按顺序完成 3 步，然后点悬浮窗 ▶ 开始：")
                        OutlinedButton({ startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("1. 开启无障碍服务") }
                        OutlinedButton({
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }) { Text("2. 授权悬浮窗") }
                        Button({ startProjection() }) { Text("3. 授权截屏并启动") }
                        OutlinedButton({ showLog = true }) { Text("查看详细日志") }
                        OutlinedButton({
                            updateChecked = true; updateError = ""
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val info = Updater.hasUpdate(ctx)
                                if (info != null) updateInfo = info
                                else updateError = "已是最新版"
                            }
                        }) { Text("检查更新") }
                        if (updateChecked && updateInfo == null && updateError.isNotEmpty()) {
                            Text(updateError, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("循环次数")
                            OutlinedTextField(
                                loopLimit,
                                { v ->
                                    loopLimit = v.filter { it.isDigit() }.take(4)
                                    RuleStore.setLoopLimit(ctx, loopLimit.toIntOrNull() ?: 0)
                                },
                                modifier = Modifier.width(80.dp), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text("次（0=无限）", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton({
                            val builtins = listOf(
                                Triple("builtin_potato", "守卫-土豆怪", "tpl_potato.png"),
                                Triple("builtin_slime", "守卫-史莱姆", "tpl_slime.png"),
                                Triple("builtin_scorpion", "守卫-蝎子", "tpl_scorpion.png"),
                                Triple("builtin_tree", "守卫-树怪", "tpl_tree.png"),
                                Triple("builtin_snake", "守卫-蛇", "tpl_snake.png"),
                                Triple("builtin_fairy", "守卫-花仙", "tpl_fairy.png"),
                            )
                            // 土豆 0.75：0.13/0.15 局里曾掉到 0.848，离 0.8 太近；树/蛇/花仙模板从
                            // 战斗结束暗屏裁出，颜色偏暗，阈值放宽到 0.7；史莱姆/蝎子一直 0.98+，保持 0.8
                            val thresholds = mapOf("builtin_potato" to 0.75, "builtin_tree" to 0.7, "builtin_snake" to 0.7, "builtin_fairy" to 0.7)
                            var added = 0
                            var updated = 0
                            for ((id, name, asset) in builtins) {
                                val f = File(ctx.filesDir, asset)
                                ctx.assets.open(asset).use { input ->
                                    f.outputStream().use { output -> input.copyTo(output) }
                                }
                                val idx = rules.indexOfFirst { it.id == id }
                                val enabled = if (idx >= 0) rules[idx].enabled else true
                                val fresh = Rule(id = id, name = name, type = "image", templatePath = f.absolutePath, threshold = thresholds[id] ?: 0.8, clusterFirst = true, delayMs = 600, enabled = enabled)
                                if (idx >= 0) { rules[idx] = fresh; updated++ } else { rules.add(fresh); added++ }
                            }
                            persist()
                            AppLog.i("规则", "守卫城邦内置规则：新增 $added 条，更新 $updated 条（点后延迟 600ms，按价值选目标）")
                            Toast.makeText(ctx, if (added + updated > 0) "内置规则已同步（新增 $added，更新 $updated）" else "内置规则已是最新", Toast.LENGTH_SHORT).show()
                        }) { Text("加载守卫城邦内置规则") }
                        Button({ GuardScript.toggle(ctx) }) { Text("一键守卫城邦") }
                        Text("在挑战页点它：自动进场→点颜色辅助→开跑一局（只点免费，不点钻石）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("规则（从上到下依次检测，命中即点击）", style = MaterialTheme.typography.titleSmall) }
                items(rules, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (if (r.type == "color") "找色 ${r.colorHex} ±${r.tolerance}"
                                    else "找图 相似度≥${r.threshold}") +
                                    (if (r.clusterFirst) "·扎堆优先" else "") +
                                    (if (r.inputText.isNotBlank()) " → 输入「${r.inputText}」" else ""),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(r.enabled, { on ->
                                val i = rules.indexOfFirst { it.id == r.id }
                                rules[i] = r.copy(enabled = on); persist()
                                AppLog.i("规则", "规则「${r.name}」已" + if (on) "启用" else "停用")
                            })
                            TextButton({
                                rules.remove(r); persist()
                                AppLog.i("规则", "规则「${r.name}」已删除")
                            }) { Text("删") }
                        }
                    }
                }
            }
        }
        if (showAdd) AddDialog({ showAdd = false }) {
            rules.add(it); persist()
            Pick.clear()   // 这次取色/框选用掉了，下次打开不再重复带入
            AppLog.i("规则", "新建规则「${it.name}」（${if (it.type == "color") "找色 ${it.colorHex} ±${it.tolerance}" else "找图 相似度≥${it.threshold}"}）")
            showAdd = false
        }
        if (showLog) LogScreen { showLog = false }
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { if (updateProgress < 0) updateInfo = null },
                title = { Text("发现新版本 ${info.versionName}") },
                text = {
                    Column {
                        val curName = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
                        Text("当前已安装 $curName，新版 ${info.versionName} 可直接覆盖安装（签名固定，规则不丢）。")
                        if (updateProgress >= 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("下载中 ${updateProgress}%", style = MaterialTheme.typography.bodySmall)
                        }
                        if (updateError.isNotEmpty()) Text(updateError, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (updateProgress >= 0) return@TextButton
                            updateError = ""; updateProgress = 0
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val err = Updater.downloadAndInstall(ctx, info) { p -> updateProgress = p }
                                if (err != null) { updateError = err; updateProgress = -1 }
                                else { updateProgress = -1; updateInfo = null }   // 安装器已调起，关掉对话框
                            }
                        },
                        enabled = updateProgress < 0
                    ) { Text(if (updateProgress >= 0) "下载中…" else "更新") }
                },
                dismissButton = {
                    TextButton(onClick = { if (updateProgress < 0) updateInfo = null }) { Text("稍后") }
                }
            )
        }
    }

    @Composable
    fun AddDialog(onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
        val ctx = LocalContext.current
        var name by remember { mutableStateOf("规则${System.currentTimeMillis() % 1000}") }
        // 取色/框选完直接建规则：类型、颜色、区域、模板图都按最近一次的取色/框选自动填好
        var type by remember { mutableStateOf(if (Pick.lastKind == "region") "image" else "color") }
        var hex by remember { mutableStateOf(Pick.color ?: "#FF0000") }
        var tol by remember { mutableStateOf("16") }
        var thr by remember { mutableStateOf("0.85") }
        var region by remember { mutableStateOf(Pick.rect?.joinToString(",") ?: "") }
        var delay by remember { mutableStateOf("1000") }
        var ox by remember { mutableStateOf("0") }
        var oy by remember { mutableStateOf("0") }
        var input by remember { mutableStateOf("") }
        var cluster by remember { mutableStateOf(false) }
        var tpl by remember { mutableStateOf(Pick.cropPath ?: "") }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) tpl = saveTemplate(ctx, uri)
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新建规则") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名称") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(type == "color", { type = "color" }, { Text("找色") })
                        FilterChip(type == "image", { type = "image" }, { Text("找图") })
                    }
                    if (type == "color") {
                        OutlinedTextField(hex, { hex = it }, label = { Text("颜色 #RRGGBB") })
                        OutlinedButton({
                            val c = Pick.color
                            if (c != null) hex = c else Toast.makeText(ctx, "还没有取色记录，先用悬浮窗「取色」", Toast.LENGTH_SHORT).show()
                        }) { Text("使用最近取色 ${Pick.color ?: ""}") }
                        OutlinedTextField(tol, { tol = it }, label = { Text("容差 0-255") })
                    } else {
                        OutlinedButton({
                            val p = Pick.cropPath
                            if (p != null) tpl = p else Toast.makeText(ctx, "还没有框选记录，先用悬浮窗「框选」", Toast.LENGTH_SHORT).show()
                        }) { Text("使用最近框选作为模板图") }
                        OutlinedButton({ picker.launch("image/*") }) { Text("或从相册选择") }
                        if (tpl.isNotEmpty()) Text("模板图已选择 ✓")
                        OutlinedTextField(thr, { thr = it }, label = { Text("相似度 0-1") })
                        FilterChip(cluster, { cluster = !cluster }, { Text("优先点扎堆") })
                    }
                    OutlinedTextField(region, { region = it }, label = { Text("区域 x,y,w,h（留空=全屏）") })
                    OutlinedButton({
                        val r = Pick.rect
                        if (r != null) region = r.joinToString(",") else Toast.makeText(ctx, "还没有框选记录，先用悬浮窗「框选」", Toast.LENGTH_SHORT).show()
                    }) { Text("使用最近框选作为区域") }
                    OutlinedTextField(delay, { delay = it }, label = { Text("点击后等待(ms)") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(ox, { ox = it }, label = { Text("点击偏移 X") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(oy, { oy = it }, label = { Text("点击偏移 Y") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(input, { input = it }, label = { Text("输入文字（命中点击后输入，可空）") })
                }
            },
            confirmButton = {
                TextButton({
                    onSave(
                        Rule(
                            id = UUID.randomUUID().toString(), name = name, type = type,
                            colorHex = hex.trim(), tolerance = tol.toIntOrNull() ?: 16,
                            templatePath = tpl, threshold = thr.toDoubleOrNull() ?: 0.85,
                            region = region.split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 },
                            offsetX = ox.toIntOrNull() ?: 0, offsetY = oy.toIntOrNull() ?: 0,
                            delayMs = delay.toLongOrNull() ?: 1000,
                            inputText = input, clusterFirst = cluster,
                        )
                    )
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onDismiss) { Text("取消") } }
        )
    }

    private fun saveTemplate(c: Context, uri: Uri): String {
        val bmp = c.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        val f = File(c.filesDir, "tpl_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }
}
