package com.example.colorclicker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 应用内更新：从 GitHub 滚动 Release 拉 version.json，比对 versionCode，有新版就下载安装 */
object Updater {
    private const val VERSION_URL =
        "https://github.com/anransu983-sketch/ColorClicker/releases/download/ci-debug/version.json"
    private const val APK_URL =
        "https://github.com/anransu983-sketch/ColorClicker/releases/download/ci-debug/app-debug.apk"

    data class UpdateInfo(val versionCode: Long, val versionName: String)

    fun installedVersionCode(ctx: Context): Long {
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (_: Exception) {
            0L
        }
    }

    /** 拉取 version.json；失败（没网/没发版）返回 null，不打扰用户 */
    fun checkUpdate(): UpdateInfo? {
        return try {
            val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "ColorClicker")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val vc = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            val vn = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
            UpdateInfo(vc, vn)
        } catch (_: Exception) {
            null
        }
    }

    /** 有新版时返回 true（供启动时静默检查） */
    fun hasUpdate(ctx: Context): UpdateInfo? {
        val info = checkUpdate() ?: return null
        return if (info.versionCode > installedVersionCode(ctx)) info else null
    }

    /** 下载 APK（回调进度 0~100），下完直接调起系统安装器；失败返回错误信息 */
    fun downloadAndInstall(ctx: Context, info: UpdateInfo, onProgress: (Int) -> Unit): String? {
        return try {
            val dir = (ctx.getExternalFilesDir("updates") ?: ctx.filesDir).apply { if (!exists()) mkdirs() }
            val out = File(dir, "ColorClicker-${info.versionName}.apk")
            val conn = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 30000
                setRequestProperty("User-Agent", "ColorClicker")
            }
            if (conn.responseCode != 200) return "下载失败：HTTP ${conn.responseCode}"
            val total = conn.contentLengthLong
            AppLog.i("启动", "开始下载更新 ${info.versionName}（共 ${if (total > 0) "${total / 1024 / 1024} MB" else "大小未知"}）")
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (total > 0 && out.length() != total) {
                AppLog.e("启动", "更新下载不完整：已下 ${out.length()} / 应下 $total 字节")
                return "下载不完整（${out.length() / 1024 / 1024} / ${total / 1024 / 1024} MB），请重试"
            }
            AppLog.i("启动", "更新下载完成：${out.length() / 1024 / 1024} MB")
            // 没开「安装未知应用」时调安装器会被系统拦下，先带她去开
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                AppLog.w("启动", "未授予「安装未知应用」权限，正在打开设置页")
                val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(settings)
                return "请在设置里允许「安装未知应用」，再点更新"
            }
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AppLog.i("启动", "正在调起安装器")
            ctx.startActivity(intent)
            AppLog.i("启动", "安装器已调起")
            null
        } catch (e: Exception) {
            AppLog.e("启动", "下载更新失败", e)
            "下载失败：${e.message}"
        }
    }
}
