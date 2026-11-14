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

    private fun sc() = bmp.width.toFloat() / width
    private fun bx(v: Float) = (v * sc()).toInt().coerceIn(0, bmp.width - 1)
    private fun by(v: Float) = (v * sc()).toInt().coerceIn(0, bmp.height - 1)

    override fun onDraw(c: Canvas) {
        val dst = Rect(0, 0, width, height)
        c.drawBitmap(bmp, null, dst, null)
        c.drawColor(0x44000000)

        if (mode == "region" && touching) {
            val l = minOf(x0, x1); val t = minOf(y0, y1); val r = maxOf(x0, x1); val b = maxOf(y0, y1)
            val s = sc()
            c.drawBitmap(bmp, Rect((l * s).toInt(), (t * s).toInt(), (r * s).toInt(), (b * s).toInt()), RectF(l, t, r, b), null)
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
            onDone(String.format("#%06X", px and 0xFFFFFF), null)
        } else {
            val l = bx(minOf(x0, x1)); val t = by(minOf(y0, y1))
            val r = (maxOf(x0, x1) * sc()).toInt().coerceIn(l + 1, bmp.width)
            val b = (maxOf(y0, y1) * sc()).toInt().coerceIn(t + 1, bmp.height)
            if (r - l < 8 || b - t < 8) return   // 太小，当作误触，继续选
            onDone(null, listOf(l, t, r - l, b - t))
        }
    }
}
