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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
        val rules = remember { mutableStateListOf<Rule>().apply { addAll(RuleStore.load(ctx)) } }
        var showAdd by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        fun persist() = RuleStore.save(ctx, rules.toList())

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
                    }
                }
                item { Text("规则（从上到下依次检测，命中即点击）", style = MaterialTheme.typography.titleSmall) }
                items(rules, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (r.type == "color") "找色 ${r.colorHex} ±${r.tolerance}"
                                    else "找图 相似度≥${r.threshold}",
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
            AppLog.i("规则", "新建规则「${it.name}」（${if (it.type == "color") "找色 ${it.colorHex} ±${it.tolerance}" else "找图 相似度≥${it.threshold}"}）")
            showAdd = false
        }
        if (showLog) LogScreen { showLog = false }
    }

    @Composable
    fun AddDialog(onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
        val ctx = LocalContext.current
        var name by remember { mutableStateOf("规则${System.currentTimeMillis() % 1000}") }
        var type by remember { mutableStateOf("color") }
        var hex by remember { mutableStateOf("#FF0000") }
        var tol by remember { mutableStateOf("16") }
        var thr by remember { mutableStateOf("0.85") }
        var region by remember { mutableStateOf("") }
        var delay by remember { mutableStateOf("1000") }
        var tpl by remember { mutableStateOf("") }
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
                    }
                    OutlinedTextField(region, { region = it }, label = { Text("区域 x,y,w,h（留空=全屏）") })
                    OutlinedButton({
                        val r = Pick.rect
                        if (r != null) region = r.joinToString(",") else Toast.makeText(ctx, "还没有框选记录，先用悬浮窗「框选」", Toast.LENGTH_SHORT).show()
                    }) { Text("使用最近框选作为区域") }
                    OutlinedTextField(delay, { delay = it }, label = { Text("点击后等待(ms)") })
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
                            delayMs = delay.toLongOrNull() ?: 1000,
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
