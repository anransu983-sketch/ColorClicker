package com.example.colorclicker

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/** 一条日志；x/y 非空表示这条日志关联一个可点击的坐标（点日志行即可模拟点击该坐标） */
data class LogLine(
    val time: Long,
    val level: String,   // DEBUG / INFO / WARN / ERROR
    val tag: String,     // 截图 / 匹配 / 点击 / 规则 / 服务 / 启动 / 崩溃
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
 * 3. 文件 filesDir/logs/app.log（保存在本机，跨重启保留，超 1MB 自动轮换为 app.log.1）
 *
 * 崩溃也不丢日志：启动时把文件里的旧日志读回内存，日志页直接能看到崩溃前的记录；
 * 未捕获异常由全局 handler 先把堆栈写进 logs/crash.log 与 app.log 再交给系统。
 */
object AppLog {
    const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private val LINE_PATTERN =
        Pattern.compile("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\[(DEBUG|INFO|WARN|ERROR)]\\[(.+?)] (.*)$")

    val lines = mutableStateListOf<LogLine>()

    @Volatile private var filesDir: File? = null
    @Volatile private var historyLoaded = false
    @Volatile private var crashHandlerInstalled = false

    fun init(ctx: Context) {
        filesDir = ctx.applicationContext.filesDir
        loadHistory()
        installCrashHandler()
    }

    /** 启动时把本机文件里的旧日志读回内存（崩溃重启后也能看到崩溃前的记录） */
    @Synchronized
    private fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        try {
            val dir = filesDir ?: return
            val logDir = File(dir, "logs")
            val raw = StringBuilder()
            val old = File(logDir, "app.log.1")
            if (old.exists()) raw.append(old.readText())
            val cur = File(logDir, "app.log")
            if (cur.exists()) raw.append(cur.readText())
            val parsed = raw.lines().mapNotNull { parseLine(it) }
            val tail = parsed.takeLast(MAX_LINES)
            lines.addAll(tail)
            if (tail.isNotEmpty()) {
                Log.i("ColorClicker/启动", "已从本机文件恢复 ${tail.size} 条旧日志")
            }
        } catch (_: Exception) {
            // 恢复失败不影响主流程
        }
    }

    /** 把文件里的一行日志解析回 LogLine；时间只精确到当天时分秒（显示用） */
    private fun parseLine(s: String): LogLine? {
        val m = LINE_PATTERN.matcher(s)
        if (!m.matches()) return null
        val timeOfDay = try {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).parse(m.group(1))?.time ?: return null
        } catch (_: Exception) {
            return null
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // 文件时间可能是昨天的（跨午夜崩溃），时分秒本身不变，显示足够用
        return LogLine(today + timeOfDay, m.group(2), m.group(3), m.group(4))
    }

    /** 全局未捕获异常：先把堆栈写进本机文件，再交给系统原 handler */
    @Synchronized
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = e.stackTraceToString()
                val head = "未捕获异常 thread=${t.name}: ${e::class.java.name}: ${e.message}"
                write("ERROR", "崩溃", head)
                appendRaw(trace)
                val dir = filesDir
                if (dir != null) {
                    val logDir = File(dir, "logs").apply { if (!exists()) mkdirs() }
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    File(logDir, "crash.log").appendText("==== $stamp $head ====\n$trace\n")
                }
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
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
        appendFile(line.format())
    }

    /** 不经格式化的原始文本直接追加到 app.log（用于崩溃堆栈） */
    @Synchronized
    private fun appendRaw(text: String) {
        appendFile(text)
    }

    @Synchronized
    private fun appendFile(text: String) {
        try {
            val dir = filesDir ?: return
            val logDir = File(dir, "logs").apply { if (!exists()) mkdirs() }
            val f = File(logDir, "app.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(logDir, "app.log.1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            f.appendText(text + "\n")
        } catch (_: Exception) {
            // 日志落盘失败不影响主流程
        }
    }

    /** 读本机文件里的全部日志文本（app.log.1 + app.log），用于导出 */
    private fun readFileText(): String? {
        return try {
            val dir = filesDir ?: return null
            val logDir = File(dir, "logs")
            val sb = StringBuilder()
            val old = File(logDir, "app.log.1")
            if (old.exists()) sb.append(old.readText())
            val cur = File(logDir, "app.log")
            if (cur.exists()) sb.append(cur.readText())
            val crash = File(logDir, "crash.log")
            if (crash.exists()) sb.append("\n==== 崩溃记录 crash.log ====\n").append(crash.readText())
            val s = sb.toString()
            if (s.isBlank()) null else s
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        lines.clear()
        try {
            val dir = filesDir ?: return
            File(dir, "logs/app.log").delete()
            File(dir, "logs/app.log.1").delete()
            File(dir, "logs/crash.log").delete()
        } catch (_: Exception) {
        }
    }

    /** 导出本机文件里的全部日志（崩溃重启后也有）；文件不存在时退回内存日志 */
    fun exportText(): String = readFileText() ?: lines.joinToString("\n") { it.format() }
}
