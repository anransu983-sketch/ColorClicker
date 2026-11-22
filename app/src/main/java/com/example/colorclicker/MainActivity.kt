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
import androidx.core.content.FileProvider
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
        var showMemory by remember { mutableStateOf(false) }
        var memVersion by remember { mutableStateOf(0) }
        val memText = remember(showMemory, memVersion) { if (showMemory) BoardMemory.summaryText(ctx) else null }
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
                        OutlinedButton({ memVersion++; showMemory = true }) { Text("查看记忆学习") }
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
                                Triple("builtin_jellyfish", "守卫-水母", "tpl_jellyfish.png"),
                                // 0.42 预备：野猪（红皮野猪怪，蓝鬃毛，红底色）——31 局后她手动那局里拍到 3 只排底排，
                                // 模板 tpl_boar.png 待她发 02:19:40 那张的原图（1440×3120）后裁入，齐了再编 0.42 推
                                Triple("builtin_boar", "守卫-野猪", "tpl_boar.png"),
                                // 0.61：蘑菇怪（绿帽＋浅斑＋顶上两片叶＋白茎）——第 9 种怪，49 局结算底排 1 只、50 局
                                // 贴墙 12 格整块最佳分多为 0.000（bot 完全看不见）。模板从 51 局前她发的亮屏实况图 C
                                // 底排第 5 列裁出（186×238 原生，886 图×1.626 放大）；与它怪交叉最高仅土豆 0.135，
                                // 阈值 0.75（模板经放大略糊，留余量；野猪原生图才用 0.8）
                                Triple("builtin_mushroom", "守卫-蘑菇怪", "tpl_mushroom.png"),
                            )
                            // 土豆 0.75：0.13/0.15 局里曾掉到 0.848，离 0.8 太近；树/蛇/花仙模板从
                            // 战斗结束暗屏裁出，颜色偏暗，阈值放宽到 0.7；史莱姆/蝎子一直 0.98+，保持 0.8
                            // 水母 0.75：模板是 0.29 AI 自动裁的亮屏原图（第 12 局 22:21:39 那只），先按土豆档起步
                            // 野猪 0.8：原生图 NCC——三只野猪互认 0.994–1.000（同一精灵），但同排土豆/蝎子 0.706–0.707
                            // 偏高（底排共有的暗红底色）；0.8 既够得着真野猪（0.99），又压住误认。effThr 另有野猪 0.8 的 cap
                            val thresholds = mapOf("builtin_potato" to 0.75, "builtin_tree" to 0.7, "builtin_snake" to 0.7, "builtin_fairy" to 0.7, "builtin_jellyfish" to 0.75, "builtin_boar" to 0.8, "builtin_mushroom" to 0.75)
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
                            AppLog.i("规则", "守卫城邦内置规则：新增 $added 条，更新 $updated 条（点后延迟 600ms，按期限选目标）")
                            Toast.makeText(ctx, if (added + updated > 0) "内置规则已同步（新增 $added，更新 $updated）" else "内置规则已是最新", Toast.LENGTH_SHORT).show()
                        }) { Text("加载守卫城邦内置规则") }
                        Button({ GuardScript.toggle(ctx) }) { Text("一键守卫城邦") }
                        Text("在挑战页点它：自动进场→免费（没次数就点钻石×100）→关介绍→点颜色辅助→开跑一局", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton({
                            val dir = ctx.getExternalFilesDir(null)
                            val latest = dir?.listFiles { f -> f.name.startsWith("settle_title_") && f.name.endsWith(".png") }
                                ?.maxByOrNull { it.lastModified() }
                            if (latest == null) {
                                Toast.makeText(ctx, "还没裁过结算标题图", Toast.LENGTH_SHORT).show()
                            } else {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", latest)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "分享结算标题图"))
                            }
                        }) { Text("分享结算标题图（最新一张）") }
                        Text("打完回挑战页时从结算屏上自裁的那张标题图（settle_title_…png），认不到结算屏时点这个发出来，我拿它做新模板。", style = MaterialTheme.typography.bodySmall)
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
        if (showMemory) {
            AlertDialog(
                onDismissRequest = { showMemory = false },
                title = { Text("记忆学习（累计）") },
                text = {
                    Text(
                        memText ?: "还没打过一局，记忆是空的。\n打完一局后回来这里看累计；单局的本局记忆、核实对账在日志里，分类选「记忆」就能只看它。"
                    )
                },
                confirmButton = { TextButton({ showMemory = false }) { Text("知道了") } }
            )
        }
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
