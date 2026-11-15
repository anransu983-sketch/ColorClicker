package com.example.colorclicker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object Matcher {
    /** 最近一次找图的最高相似度（供日志用；找色不适用） */
    @Volatile var lastImageScore: Double = 0.0
    /** 最近一次找图命中点的扎堆数（含自己；仅扎堆模式有意义） */
    @Volatile var lastClusterSize: Int = 0
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
            lastImageScore = mm.maxVal
            if (mm.maxVal < thr) return null
            return Point(r[0] + mm.maxLoc.x.toInt() + t.cols() / 2, r[1] + mm.maxLoc.y.toInt() + t.rows() / 2)
        } finally {
            roi.release(); src.release(); t.release()
        }
    }

    /** 找图（扎堆优先）：找出所有达标的匹配点，返回“周围同类最多”那只的中心。
     *  同类判定：中心间距 ≤ 模板最大边的 1.6 倍（相邻格子约 1 倍，对角约 1.41 倍）。
     *  模板请框整只怪（连格子一起），框太紧会导致相邻间距大于判定半径。 */
    fun findImageCluster(bmp: Bitmap, tpl: Bitmap, thr: Double, region: List<Int>?): Point? {
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
            lastImageScore = mm.maxVal
            if (mm.maxVal < thr) { res.release(); return null }
            // 达标的全部位置
            val mask = Mat()
            Core.compare(res, Scalar(thr), mask, Core.CMP_GE)
            val pts = MatOfPoint()
            Core.findNonZero(mask, pts)
            val rawList = pts.toList()
            mask.release(); pts.release()
            if (rawList.size > 2000) {   // 模板太通用（如纯色块）到处都达标，退回单点最佳
                AppLog.w("匹配", "扎堆模式：达标点过多（${rawList.size}），退回单点最佳")
                val p = Point(r[0] + mm.maxLoc.x.toInt() + t.cols() / 2, r[1] + mm.maxLoc.y.toInt() + t.rows() / 2)
                res.release()
                return p
            }
            // 每点取分数，按分数降序
            val scored = rawList.map { p ->
                Triple(p.x.toInt(), p.y.toInt(), res.get(p.y.toInt(), p.x.toInt())[0])
            }.sortedByDescending { it.third }
            res.release()
            // 非极大值抑制：同一只怪物的多次命中只留分数最高的一个
            val minDist = maxOf(t.cols(), t.rows()) * 0.75
            val minDist2 = minDist * minDist
            val accepted = ArrayList<Triple<Int, Int, Double>>()
            for (p in scored) {
                var dup = false
                for (a in accepted) {
                    val dx = (p.first - a.first).toDouble()
                    val dy = (p.second - a.second).toDouble()
                    if (dx * dx + dy * dy < minDist2) { dup = true; break }
                }
                if (!dup) accepted.add(p)
            }
            if (accepted.isEmpty()) return null
            // 扎堆计数：半径内同类数量
            val radius = maxOf(t.cols(), t.rows()) * 1.6
            val radius2 = radius * radius
            var best: Triple<Int, Int, Double>? = null
            var bestCount = -1
            for (a in accepted) {
                var count = 0
                for (b in accepted) {
                    if (b === a) continue
                    val dx = (a.first - b.first).toDouble()
                    val dy = (a.second - b.second).toDouble()
                    if (dx * dx + dy * dy <= radius2) count++
                }
                if (count > bestCount || (count == bestCount && best != null && a.third > best.third)) {
                    best = a; bestCount = count
                }
            }
            lastClusterSize = bestCount + 1   // 含自己
            val b = best ?: return null
            return Point(r[0] + b.first + t.cols() / 2, r[1] + b.second + t.rows() / 2)
        } finally {
            roi.release(); src.release(); t.release()
        }
    }
}
