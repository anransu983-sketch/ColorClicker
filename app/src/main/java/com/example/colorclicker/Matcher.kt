package com.example.colorclicker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object Matcher {
    private fun clamp(b: Bitmap, r: List<Int>?): IntArray {
        if (r == null || r.size < 4) return intArrayOf(0, 0, b.width, b.height)
        val x = r[0].coerceIn(0, b.width - 1)
        val y = r[1].coerceIn(0, b.height - 1)
        val w = r[2].coerceIn(1, b.width - x)
        val h = r[3].coerceIn(1, b.height - y)
        return intArrayOf(x, y, w, h)
    }

    /** 找色：返回区域内第一个匹配像素的坐标 */
    fun findColor(bmp: Bitmap, hex: String, tol: Int, region: List<Int>?): Point? {
        val t = Color.parseColor(hex)
        val r = clamp(bmp, region)
        val px = IntArray(r[2] * r[3])
        bmp.getPixels(px, 0, r[2], r[0], r[1], r[2], r[3])
        val tr = Color.red(t); val tg = Color.green(t); val tb = Color.blue(t)
        for (i in px.indices) {
            val c = px[i]
            if (abs(Color.red(c) - tr) <= tol && abs(Color.green(c) - tg) <= tol && abs(Color.blue(c) - tb) <= tol)
                return Point(r[0] + i % r[2], r[1] + i / r[2])
        }
        return null
    }

    /** 找图：模板匹配，返回匹配区域中心 */
    fun findImage(bmp: Bitmap, tpl: Bitmap, thr: Double, region: List<Int>?): Point? {
        val r = clamp(bmp, region)
        val src = Mat(); val t = Mat()
        Utils.bitmapToMat(bmp, src); Utils.bitmapToMat(tpl, t)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(t, t, Imgproc.COLOR_RGBA2RGB)
        val roi = Mat(src, Rect(r[0], r[1], r[2], r[3]))
        try {
            if (t.cols() > roi.cols() || t.rows() > roi.rows()) return null
            val res = Mat()
            Imgproc.matchTemplate(roi, t, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            res.release()
            if (mm.maxVal < thr) return null
            return Point(r[0] + mm.maxLoc.x.toInt() + t.cols() / 2, r[1] + mm.maxLoc.y.toInt() + t.rows() / 2)
        } finally {
            roi.release(); src.release(); t.release()
        }
    }
}
