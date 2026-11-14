package com.example.colorclicker

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** 最近一次取色 / 框选的结果，新建规则时可一键使用 */
object Pick {
    @Volatile var color: String? = null
    @Volatile var rect: List<Int>? = null       // x,y,w,h
    @Volatile var cropPath: String? = null      // 框选区域裁出的模板图
    @Volatile var lastKind: String? = null      // "color" | "region"，最近一次是哪种操作
    fun clear() { color = null; rect = null; cropPath = null; lastKind = null }
}

/**
 * 全屏冻结截图选择器。
 * mode = "color"：按住拖动，放大镜显示像素，松手确认取色
 * mode = "region"：拖动框选矩形，松手确认
 */
class PickerView(
    ctx: Context,
    private val bmp: Bitmap,
    private val mode: String,
    private val onDone: (color: String?, rect: List<Int>?) -> Unit,
) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val cancel = RectF()
    private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
    private var touching = false

    /**
     * 等比适配：把 bitmap 完整放进 view（不变形），返回 (scale, offsetX, offsetY)。
     * 之前用宽高共用一个缩放且拉满全屏：竖屏截的图在横屏游戏里会被拉变形，
     * 取色 y 坐标又按宽的比例换算，点哪都落在黑边上，全是 #000000。
     */
    private fun fit(): Triple<Float, Float, Float> {
        if (width == 0 || height == 0) return Triple(1f, 0f, 0f)
        val s = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        return Triple(s, (width - bmp.width * s) / 2, (height - bmp.height * s) / 2)
    }

    private fun bx(v: Float): Int {
        val (s, ox, _) = fit()
        return ((v - ox) / s).toInt().coerceIn(0, bmp.width - 1)
    }

    private fun by(v: Float): Int {
        val (s, _, oy) = fit()
        return ((v - oy) / s).toInt().coerceIn(0, bmp.height - 1)
    }

    override fun onDraw(c: Canvas) {
        val (s, ox, oy) = fit()
        val dst = RectF(ox, oy, ox + bmp.width * s, oy + bmp.height * s)
        c.drawBitmap(bmp, null, dst, null)
        c.drawColor(0x44000000)

        if (mode == "region" && touching) {
            val l = minOf(x0, x1); val t = minOf(y0, y1); val r = maxOf(x0, x1); val b = maxOf(y0, y1)
            val src = Rect(bx(l), by(t), maxOf(bx(r), bx(l) + 1), maxOf(by(b), by(t) + 1))
            c.drawBitmap(bmp, src, RectF(l, t, r, b), null)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0xFF42A5F5.toInt()
            c.drawRect(l, t, r, b, p)
        }

        if (mode == "color" && touching) {
            val half = 12
            val cx = bx(x1).coerceIn(half, bmp.width - half - 1)
            val cy = by(y1).coerceIn(half, bmp.height - half - 1)
            val size = 260f
            val mx = (x1 - size / 2).coerceIn(0f, width - size)
            var my = y1 - size - 90f
            if (my < 0) my = y1 + 90f
            val box = RectF(mx, my, mx + size, my + size)
            c.drawBitmap(bmp, Rect(cx - half, cy - half, cx + half + 1, cy + half + 1), box, pixelPaint)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.WHITE
            c.drawRect(box, p)
            val cell = size / (half * 2 + 1)
            p.color = Color.BLACK; p.strokeWidth = 3f
            c.drawRect(box.centerX() - cell / 2, box.centerY() - cell / 2, box.centerX() + cell / 2, box.centerY() + cell / 2, p)
            val px = bmp.getPixel(bx(x1), by(y1))
            val hex = String.format("#%06X", px and 0xFFFFFF)
            p.style = Paint.Style.FILL; p.color = px or 0xFF000000.toInt()
            c.drawRect(mx, box.bottom + 8, mx + 60f, box.bottom + 68f, p)
            p.color = Color.WHITE; p.textSize = 44f
            c.drawText("$hex  (${bx(x1)},${by(y1)})", mx + 72f, box.bottom + 55f, p)
        }

        // 提示 + 取消按钮
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 42f
        c.drawText(if (mode == "color") "按住拖动，松手取色" else "拖动框选区域", 40f, 120f, p)
        cancel.set(width - 260f, 60f, width - 30f, 170f)
        p.color = 0xCCB71C1C.toInt(); c.drawRoundRect(cancel, 20f, 20f, p)
        p.color = Color.WHITE; c.drawText("取消", cancel.left + 60f, cancel.centerY() + 15f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (cancel.contains(e.x, e.y)) { onDone(null, null); return true }
                x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; touching = true
            }
            MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
            MotionEvent.ACTION_UP -> { x1 = e.x; y1 = e.y; touching = false; finish() }
        }
        invalidate(); return true
    }

    private fun finish() {
        if (mode == "color") {
            val px = bmp.getPixel(bx(x1), by(y1))
            val hex = String.format("#%06X", px and 0xFFFFFF)
            // 诊断：取色点周围 5x5 的均值与非黑像素数，判断是真黑还是单点异常
            var r = 0; var g = 0; var b = 0; var n = 0; var nonBlack = 0
            val cx = bx(x1); val cy = by(y1)
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (cx + dx).coerceIn(0, bmp.width - 1)
                val yy = (cy + dy).coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(xx, yy)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                if (c and 0xFFFFFF != 0) nonBlack++
            }
            val mean = String.format("#%02X%02X%02X", r / n, g / n, b / n)
            AppLog.d("截图", "取色 $hex @($cx,$cy)，周围5x5均值 $mean，非黑像素 $nonBlack/25")
            onDone(hex, null)
        } else {
            val l = bx(minOf(x0, x1)); val t = by(minOf(y0, y1))
            val r = bx(maxOf(x0, x1)).coerceIn(l + 1, bmp.width)
            val b = by(maxOf(y0, y1)).coerceIn(t + 1, bmp.height)
            if (r - l < 8 || b - t < 8) return   // 太小，当作误触，继续选
            onDone(null, listOf(l, t, r - l, b - t))
        }
    }
}
