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

    /** 一伙连着的同类怪：point=这伙最上面那只的中心（点它清整伙，也离城墙最近），size=这伙只数，score=那只的相似度 */
    data class Comp(val point: Point, val size: Int, val score: Double)

    /** 连片找图：找出所有达标的匹配点，按“上下左右相连”分成一伙（一伙就是雷电一次能清掉的），返回每伙的信息。
     *  注意：不是“周围一圈有几只”——一长条连着的怪，周围一圈只能数到 2~3 只，会被一小撮扎堆的比下去（0.13 日志：上面一列土豆怪只数出 3 只，下面蝎子数出 7 只）。
     *  相邻只认上下左右（斜向不算）：0.13 日志里蝎子 7 只连点两次同一坐标，说明斜向的怪没一起清掉。
     *  格距按最近邻距离估算（实测 240，夹在 200~300）。 */
    fun findImageComponents(bmp: Bitmap, tpl: Bitmap, thr: Double, region: List<Int>?): List<Comp> {
        val r = clamp(bmp, region)
        val src = Mat(); val t = Mat()
        Utils.bitmapToMat(bmp, src); Utils.bitmapToMat(tpl, t)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(t, t, Imgproc.COLOR_RGBA2RGB)
        val roi = Mat(src, Rect(r[0], r[1], r[2], r[3]))
        try {
            if (t.cols() > roi.cols() || t.rows() > roi.rows()) return emptyList()
            val res = Mat()
            Imgproc.matchTemplate(roi, t, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            lastImageScore = mm.maxVal
            if (mm.maxVal < thr) { res.release(); return emptyList() }
            // 达标的全部位置
            val mask = Mat()
            Core.compare(res, Scalar(thr), mask, Core.CMP_GE)
            val pts = MatOfPoint()
            Core.findNonZero(mask, pts)
            val rawList = pts.toList()
            mask.release(); pts.release()
            if (rawList.size > 2000) {   // 模板太通用（如纯色块）到处都达标，退回单点最佳
                AppLog.w("匹配", "连片模式：达标点过多（${rawList.size}），退回单点最佳")
                val p = Point(r[0] + mm.maxLoc.x.toInt() + t.cols() / 2, r[1] + mm.maxLoc.y.toInt() + t.rows() / 2)
                res.release()
                lastClusterSize = 1
                return listOf(Comp(p, 1, mm.maxVal))
            }
            // 达标点太多时只留分数最高的 600 个再做 NMS：板子越满达标点越多，抑制和计数的 O(n²) 循环会把每轮从 1.5 秒拖到 3 秒多
            val scored = rawList.map { p ->
                Triple(p.x.toInt(), p.y.toInt(), res.get(p.y.toInt(), p.x.toInt())[0])
            }.sortedByDescending { it.third }.let { if (it.size > 600) it.take(600) else it }
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
            if (accepted.isEmpty()) return emptyList()
            // 估算格距：每个点到最近邻的距离取中位数
            val step = if (accepted.size >= 3) {
                val nn = accepted.map { a ->
                    accepted.filter { it !== a }.minOf { b ->
                        val dx = (a.first - b.first).toDouble()
                        val dy = (a.second - b.second).toDouble()
                        kotlin.math.sqrt(dx * dx + dy * dy)
                    }
                }.sorted()
                nn[nn.size / 2].coerceIn(200.0, 300.0)
            } else 240.0
            // 连片（BFS）：上下左右相连才算一伙
            fun adjacent(a: Triple<Int, Int, Double>, b: Triple<Int, Int, Double>): Boolean {
                val dx = kotlin.math.abs(a.first - b.first).toDouble()
                val dy = kotlin.math.abs(a.second - b.second).toDouble()
                return (dx in step * 0.75..step * 1.25 && dy <= step * 0.25) ||
                       (dy in step * 0.75..step * 1.25 && dx <= step * 0.25)
            }
            val visited = BooleanArray(accepted.size)
            val comps = ArrayList<Comp>()
            for (i in accepted.indices) {
                if (visited[i]) continue
                val group = ArrayList<Int>()
                val stack = ArrayDeque<Int>()
                stack.add(i); visited[i] = true
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    group.add(cur)
                    for (j in accepted.indices) {
                        if (!visited[j] && adjacent(accepted[cur], accepted[j])) {
                            visited[j] = true; stack.add(j)
                        }
                    }
                }
                val top = group.map { accepted[it] }.minWithOrNull(compareBy({ it.second }, { it.first }))!!
                comps.add(Comp(
                    Point(r[0] + top.first + t.cols() / 2, r[1] + top.second + t.rows() / 2),
                    group.size, top.third
                ))
            }
            lastClusterSize = comps.maxOf { it.size }   // 供日志兜底用；真正点的那伙由调用方按 Comp 取
            return comps
        } finally {
            roi.release(); src.release(); t.release()
        }
    }
}
