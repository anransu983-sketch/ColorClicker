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

    /** 丢弃上一帧（截屏尺寸重建后旧帧方向不对，不能再用） */
    @Synchronized
    fun reset() { last = null }

    @Synchronized
    fun grab(): Bitmap? {
        return try {
            val img = reader?.acquireLatestImage() ?: return last   // 画面没变化时沿用上一帧
            img.use {
                val p = it.planes[0]
                val pad = p.rowStride - p.pixelStride * it.width
                val full = Bitmap.createBitmap(it.width + pad / p.pixelStride, it.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(p.buffer)
                last = if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, it.width, it.height)
            }
            last
        } catch (e: Exception) {
            // reader 已关闭/画面异常时不崩溃，沿用上一帧
            last
        }
    }
}

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var overlay: View? = null
    private var picker: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var capW = 0
    private var capH = 0

    /**
     * 截屏画面只建一次：首次使用时按当前方向建，之后方向变了也不重建。
     * 这台 Android 不允许多次调 createVirtualDisplay（第二次直接抛
     * "Don't take multiple captures..."，还会把截屏授权搞掉致服务停止），
     * 所以 0.4/0.5 的“重建”路线走不通，改成首次使用时一次建对。
     * 返回 0=不可用（方向变了或建失败，需停止后重新授权），1=已就绪，2=刚建好。
     */
    fun ensureCapture(): Int {
        val b = wm.currentWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        if (ScreenGrabber.reader != null) {
            if (capW == w && capH == h) return 1
            AppLog.w("截图", "方向变了（建画面时 ${capW}x${capH}，现在 ${w}x${h}），系统不允许多次建画面，请先点 ■ 停止，再回主界面重新点「3. 授权截屏并启动」")
            return 0
        }
        val proj = projection
        if (proj == null) {
            AppLog.e("截图", "建截屏画面失败：截屏授权已失效，请重新授权")
            return 0
        }
        return try {
            val dpi = resources.displayMetrics.densityDpi
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val disp = proj.createVirtualDisplay(
                "cap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            )
            if (disp == null) {
                reader.close()
                AppLog.e("截图", "建截屏画面失败（${w}x${h}）：系统没给画面")
                return 0
            }
            ScreenGrabber.reader = reader
            display = disp
            capW = w; capH = h
            AppLog.i("截图", "截屏画面已建 ${w}x${h}（首次使用时按当前方向）")
            2
        } catch (e: Exception) {
            ScreenGrabber.reader?.close(); ScreenGrabber.reader = null
            AppLog.e("截图", "建截屏画面失败（${w}x${h}）", e)
            0
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)   // 装好崩溃 handler（服务进程也要能记崩溃）
    }

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
        AppLog.i("截图", "截屏服务已启动：画面在首次取色/运行时按当前方向建（当前 ${b.width()}x${b.height()}）")
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
        val guard = btn("守卫", 0xCCEF6C00)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(run); addView(pickColor); addView(pickRect); addView(guard)
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
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (GuardScript.isRunning() && !Runner.isRunning()) toast("一键守卫进场中：先点「守卫」停了它")
                    else run.text = if (Runner.toggle(this)) "■" else "▶"
                }
            }
            true
        }
        pickColor.setOnClickListener { startPick(bar, "color") }
        pickRect.setOnClickListener { startPick(bar, "region") }
        // 守卫：一键守卫城邦，不用回应用点（0.20）。跑着再点一次就全停。
        guard.setOnClickListener {
            if (GuardScript.isRunning()) {
                GuardScript.stop(); Runner.stop()
                toast("一键守卫已停")
            } else if (Runner.isRunning()) {
                toast("消消乐跑着，先点 ■ 停了再开守卫")
            } else if (ensureCapture() == 0) {
                toast("建画面失败或方向变了，请先停止再重新授权截屏")
            } else {
                GuardScript.toggle(this)
            }
        }
        wm.addView(bar, lp); overlay = bar
    }

    /** 先隐藏工具栏，首次使用时按当前方向建截屏画面，截一帧干净的画面，再弹出冻结截图选择器 */
    private fun startPick(bar: View, mode: String) {
        if (Runner.isRunning()) { toast("请先停止运行（点 ■）"); return }
        if (picker != null) return
        bar.visibility = View.INVISIBLE
        val ready = ensureCapture()
        if (ready == 0) {
            toast("建画面失败或方向变了，请先点 ■ 停止，再回主界面重新授权截屏")
            bar.visibility = View.VISIBLE; return
        }
        handler.postDelayed({
            try {
                val bmp = ScreenGrabber.grab()
                if (bmp == null) {
                    AppLog.w("截图", "框选/取色时还没有截到画面")
                    toast("还没有截到画面，请确认已授权截屏"); bar.visibility = View.VISIBLE; return@postDelayed
                }
            // 诊断：冻结截图是不是全黑的（全黑时取色永远是 #000000）
            var nonBlack = 0; var total = 0
            val stepX = (bmp.width / 20).coerceAtLeast(1); val stepY = (bmp.height / 20).coerceAtLeast(1)
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    total++
                    if (bmp.getPixel(x, y) and 0xFFFFFF != 0) nonBlack++
                    x += stepX
                }
                y += stepY
            }
            val ratio = if (total == 0) 0 else nonBlack * 100 / total
            AppLog.i("截图", "冻结截图 ${bmp.width}x${bmp.height}，采样 $total 点，非黑像素占比 $ratio%")
            if (ratio < 1) AppLog.w("截图", "冻结截图几乎全黑！取色会一直是 #000000，请检查目标画面是否黑屏/锁屏")
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
            } catch (e: Exception) {
                AppLog.e("截图", "弹出取色/框选失败", e)
                toast("取色失败：${e.message}，已记入日志")
                bar.visibility = View.VISIBLE
            }
        }, if (ready == 2) 600 else 350)
    }

    private fun onPicked(bmp: Bitmap, color: String?, rect: List<Int>?) {
        if (color != null) {
            Pick.color = color
            Pick.lastKind = "color"
            AppLog.i("截图", "取色 $color（已复制到剪贴板）")
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("color", color))
            toast("已取色 $color（已复制）。新建规则时点「使用最近取色」")
        } else if (rect != null) {
            Pick.rect = rect
            Pick.lastKind = "region"
            AppLog.i("截图", "框选 ${rect.joinToString(",")}，已保存为模板图")
            val crop = Bitmap.createBitmap(bmp, rect[0], rect[1], rect[2], rect[3])
            val f = File(filesDir, "crop_${System.currentTimeMillis()}.png")
            f.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Pick.cropPath = f.absolutePath
            toast("已框选 ${rect.joinToString(",")}。新建规则时可用作区域或模板图")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)   // 销毁后不再跑取色的延时任务
        AppLog.i("截图", "截屏服务已停止")
        Runner.stop()
        try {
            picker?.let { wm.removeView(it) }; picker = null
            overlay?.let { wm.removeView(it) }; overlay = null
            display?.release(); ScreenGrabber.reader?.close(); ScreenGrabber.reader = null
            projection?.stop()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
