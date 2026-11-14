package com.example.colorclicker

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条日志；x/y 非空表示这条日志关联一个可点击的坐标（点日志行即可模拟点击该坐标） */
data class LogLine(
    val time: Long,
    val level: String,   // DEBUG / INFO / WARN / ERROR
    val tag: String,     // 截图 / 匹配 / 点击 / 规则 / 服务 / 启动
    val msg: String,
    val x: Int? = null,
    val y: Int? = null,
) {
    fun format(): String {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))
        return "$t [$level][$tag] $msg"
    }
}

/**
 * 详细日志：三处同时写
 * 1. Logcat（adb 可看）
 * 2. 内存环形缓冲（应用内日志页实时看，最多保留 1000 条）
 * 3. 文件 filesDir/logs/app.log（跨重启保留，超 1MB 自动轮换为 app.log.1）
 */
object AppLog {
    const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 1024 * 1024L

    val lines = mutableStateListOf<LogLine>()

    @Volatile private var filesDir: File? = null

    fun init(ctx: Context) {
        filesDir = ctx.applicationContext.filesDir
    }

    fun d(tag: String, msg: String, x: Int? = null, y: Int? = null) = write("DEBUG", tag, msg, x, y)
    fun i(tag: String, msg: String, x: Int? = null, y: Int? = null) = write("INFO", tag, msg, x, y)
    fun w(tag: String, msg: String, x: Int? = null, y: Int? = null) = write("WARN", tag, msg, x, y)
    fun e(tag: String, msg: String, t: Throwable? = null, x: Int? = null, y: Int? = null) =
        write("ERROR", tag, if (t == null) msg else "$msg：${t.message}", x, y)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, x: Int? = null, y: Int? = null) {
        val line = LogLine(System.currentTimeMillis(), level, tag, msg, x, y)
        when (level) {
            "DEBUG" -> Log.d("ColorClicker/$tag", msg)
            "INFO" -> Log.i("ColorClicker/$tag", msg)
            "WARN" -> Log.w("ColorClicker/$tag", msg)
            else -> Log.e("ColorClicker/$tag", msg)
        }
        lines.add(line)
        while (lines.size > MAX_LINES) lines.removeAt(0)
        appendFile(line)
    }

    @Synchronized
    private fun appendFile(line: LogLine) {
        try {
            val dir = filesDir ?: return
            val logDir = File(dir, "logs").apply { if (!exists()) mkdirs() }
            val f = File(logDir, "app.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(logDir, "app.log.1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText(line.format() + "\n")
        } catch (_: Exception) {
            // 日志落盘失败不影响主流程
        }
    }

    fun clear() {
        lines.clear()
        try {
            val dir = filesDir ?: return
            File(dir, "logs/app.log").delete()
            File(dir, "logs/app.log.1").delete()
        } catch (_: Exception) {
        }
    }

    /** 导出全部内存日志为文本（用于分享） */
    fun exportText(): String = lines.joinToString("\n") { it.format() }
}
