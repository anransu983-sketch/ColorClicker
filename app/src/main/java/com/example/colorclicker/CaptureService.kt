package com.example.colorclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlin.math.abs

/** 保存最新一帧屏幕截图 */
object ScreenGrabber {
    @Volatile var reader: ImageReader? = null
    private var last: Bitmap? = null

    @Synchronized
    fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return last   // 画面没变化时沿用上一帧
        img.use {
            val p = it.planes[0]
            val pad = p.rowStride - p.pixelStride * it.width
            val full = Bitmap.createBitmap(it.width + pad / p.pixelStride, it.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            last = if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, it.width, it.height)
        }
        return last
    }
}

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var overlay: View? = null
    private var picker: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "截屏服务", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "cap")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("图色点击器运行中").build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (data == null) {
            AppLog.e("截图", "截屏服务启动失败：没有拿到截屏授权数据")
            stopSelf(); return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.w("截图", "系统收回了截屏授权，服务停止")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }
        val b = wm.currentWindowMetrics.bounds
        val dpi = resources.displayMetrics.densityDpi
        AppLog.i("截图", "截屏服务已启动：画面 ${b.width()}x${b.height()} dpi=$dpi")
        val reader = ImageReader.newInstance(b.width(), b.height(), PixelFormat.RGBA_8888, 2)
        ScreenGrabber.reader = reader
        display = projection!!.createVirtualDisplay(
            "cap", b.width(), b.height(), dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
        showOverlay()
        return START_NOT_STICKY
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun btn(label: String, bg: Long) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setBackgroundColor(bg.toInt())
        layoutParams = LinearLayout.LayoutParams(140, 140).apply { bottomMargin = 6 }
    }

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w("服务", "悬浮窗未授权，工具栏不显示")
            return
        }
        val run = btn("▶", 0xCC1565C0)
        val pickColor = btn("取色", 0xCC2E7D32)
        val pickRect = btn("框选", 0xCC6A1B9A)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(run); addView(pickColor); addView(pickRect)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        // ▶ 按钮：点击开始/停止，拖动整条工具栏
        run.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > 10 || abs(e.rawY - dy) > 10) moved = true
                    if (moved) { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(bar, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) run.text = if (Runner.toggle(this)) "■" else "▶"
            }
            true
        }
        pickColor.setOnClickListener { startPick(bar, "color") }
        pickRect.setOnClickListener { startPick(bar, "region") }
        wm.addView(bar, lp); overlay = bar
    }

    /** 先隐藏工具栏，截一帧干净的画面，再弹出冻结截图选择器 */
    private fun startPick(bar: View, mode: String) {
        if (Runner.isRunning()) { toast("请先停止运行（点 ■）"); return }
        if (picker != null) return
        bar.visibility = View.INVISIBLE
        handler.postDelayed({
            val bmp = ScreenGrabber.grab()
            if (bmp == null) {
                AppLog.w("截图", "框选/取色时还没有截到画面")
                toast("还没有截到画面，请确认已授权截屏"); bar.visibility = View.VISIBLE; return@postDelayed
            }
            val b = wm.currentWindowMetrics.bounds
            val v = PickerView(this, bmp, mode) { color, rect ->
                picker?.let { wm.removeView(it) }; picker = null
                bar.visibility = View.VISIBLE
                onPicked(bmp, color, rect)
            }
            val lp = WindowManager.LayoutParams(
                b.width(), b.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            wm.addView(v, lp); picker = v
        }, 350)
    }

    private fun onPicked(bmp: Bitmap, color: String?, rect: List<Int>?) {
        if (color != null) {
            Pick.color = color
            AppLog.i("截图", "取色 $color（已复制到剪贴板）")
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("color", color))
            toast("已取色 $color（已复制）。新建规则时点「使用最近取色」")
        } else if (rect != null) {
            Pick.rect = rect
            AppLog.i("截图", "框选 ${rect.joinToString(",")}，已保存为模板图")
            val crop = Bitmap.createBitmap(bmp, rect[0], rect[1], rect[2], rect[3])
            val f = File(filesDir, "crop_${System.currentTimeMillis()}.png")
            f.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Pick.cropPath = f.absolutePath
            toast("已框选 ${rect.joinToString(",")}。新建规则时可用作区域或模板图")
        }
    }

    override fun onDestroy() {
        AppLog.i("截图", "截屏服务已停止")
        Runner.stop()
        picker?.let { wm.removeView(it) }; picker = null
        overlay?.let { wm.removeView(it) }; overlay = null
        display?.release(); ScreenGrabber.reader?.close(); ScreenGrabber.reader = null
        projection?.stop()
        super.onDestroy()
    }
}
