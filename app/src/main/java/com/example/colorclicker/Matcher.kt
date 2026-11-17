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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

object Matcher {
    /** 最近一次找图的最高相似度（供日志用；找色不适用） */
    @Volatile var lastImageScore: Double = 0.0
    /** 最近一次找图命中点的扎堆数（含自己；仅扎堆模式有意义） */
    @Volatile var lastClusterSize: Int = 0

    // ───────────── 0.19 快路径参数 ─────────────
    /** 棋盘 ROI 上沿：第 -1 排（贴墙怪上移一格）中心 y1271，模板半高约 100，再留余量；城墙以上没有怪 */
    const val BOARD_Y0 = 1100
    /** 粗筛缩放比（0.5 = 半分辨率，matchTemplate 约快 5 倍） */
    private const val COARSE = 0.5
    /** 粗筛放宽量：粗筛门槛 = 阈值 − 0.15，保证召回；最终是否算命中仍按全分辨率相似度 ≥ 阈值判 */
    private const val COARSE_MARGIN = 0.15
    /** 全分辨率精修窗（粗筛点周围 ±px） */
    private const val REFINE_WIN = 10
    /** 每个模板最多精修多少个粗筛峰（一屏最多 6×8=48 格） */
    private const val MAX_CAND = 80
    /** 低于阈值但差不到 0.08 的叫“近阈值”，只用于诊断漏数 */
    private const val NEAR_MARGIN = 0.08
    /** 棋盘格距（已实测 240），快路径连片直接用它，不再估算 */
    private const val STEP_PX = 240.0

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

    /** 0.40 诊断：对 frame 里以 (cx,cy) 为中心的格子，逐规则在小窗内跑全分辨率 TM_CCOEFF_NORMED，返回每规则的最佳分。
     *  只给贴墙漏数格做诊断用（每轮最多几格），不参与决策。已算好的匹配响应只保留 ≥阈值和近阈值点，
     *  墙边怪若只有 0.3 分在里面根本留不下痕，所以这里对着同一帧、同一套模板重算一次小窗分——
     *  口径和决策路一样（全分辨率 TM_CCOEFF_NORMED），数字可以直接和日志里的相似度比。 */
    fun bestScoresAtCell(frame: Bitmap, tpls: List<Pair<String, Tpl>>, cx: Int, cy: Int): List<Pair<String, Double>> {
        if (tpls.isEmpty() || frame.isRecycled) return emptyList()
        val src = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(frame, src)
            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
            val half = 170   // 半窗 170：模板约 200，窗内可平移 ±70 找最佳位置，够 cover 格子偏差
            val x0 = (cx - half).coerceAtLeast(0); val y0 = (cy - half).coerceAtLeast(0)
            val x1 = (cx + half).coerceAtMost(rgb.cols()); val y1 = (cy + half).coerceAtMost(rgb.rows())
            if (x1 - x0 < 8 || y1 - y0 < 8) return emptyList()
            val roi = Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0))
            try {
                val out = ArrayList<Pair<String, Double>>()
                for ((name, tpl) in tpls) {
                    val t = tpl.full
                    if (t.cols() > roi.cols() || t.rows() > roi.rows()) { out.add(name to 0.0); continue }
                    val res = Mat()
                    Imgproc.matchTemplate(roi, t, res, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(res)
                    res.release()
                    out.add(name to mm.maxVal)
                }
                return out
            } finally { roi.release() }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            src.release(); rgb.release()
        }
    }

    /** 一伙连着的同类怪：point=这伙最上面那只的中心（点它清整伙，也离城墙最近），size=这伙只数，score=那只的相似度 */
    data class Comp(val point: Point, val size: Int, val score: Double, val ys: List<Int> = emptyList(), val cells: List<Point> = emptyList(), val scores: List<Double> = emptyList())   // ys = 这伙每只怪的中心 y；cells = 每只怪的中心点

    /** 近阈值点（相似度在 阈值−0.08 ~ 阈值 之间，未算命中）：x、y 为整帧坐标里的怪中心 */
    data class Near(val x: Int, val y: Int, val score: Double)

    /** 快路径一次扫描的结果：comps=连片后的各伙，near=近阈值点，peak=本模板这轮最高的全分辨率相似度 */
    class BoardScan(val comps: List<Comp>, val near: List<Near>, val peak: Double)

    /** 一帧的棋盘视图：只含 y ≥ y0 的部分，全分辨率 + 半分辨率两份 RGB，一轮只转换一次，所有规则共用。用完必须 release()。 */
    class BoardView internal constructor(val full: Mat, val half: Mat, val y0: Int) {
        fun release() { full.release(); half.release() }
    }

    /** 模板的两份缓存（全分辨率 + 半分辨率，RGB）。常驻内存，不需要每轮转换。 */
    class Tpl internal constructor(val full: Mat, val half: Mat)

    fun prepareBoard(bmp: Bitmap, y0: Int = BOARD_Y0): BoardView {
        val top = y0.coerceIn(0, bmp.height - 1)
        val src = Mat()
        Utils.bitmapToMat(bmp, src)
        val sub = Mat(src, Rect(0, top, src.cols(), src.rows() - top))
        val rgb = Mat()
        Imgproc.cvtColor(sub, rgb, Imgproc.COLOR_RGBA2RGB)
        sub.release(); src.release()
        val half = Mat()
        Imgproc.resize(rgb, half, Size(), COARSE, COARSE, Imgproc.INTER_AREA)
        return BoardView(rgb, half, top)
    }

    fun prepareTemplate(bmp: Bitmap): Tpl {
        val src = Mat()
        Utils.bitmapToMat(bmp, src)
        val rgb = Mat()
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
        src.release()
        val half = Mat()
        Imgproc.resize(rgb, half, Size(), COARSE, COARSE, Imgproc.INTER_AREA)
        return Tpl(rgb, half)
    }

    /** 0.19 快路径：半分辨率粗筛 → 每个粗筛峰在全分辨率小窗里精修 → 相似度 ≥ 阈值才算命中 → 全分辨率去重 → 连片。
     *  相似度口径和旧路径一样（全分辨率 TM_CCOEFF_NORMED），阈值不用改。
     *  region 非空时只保留中心落在区域内的命中（旧路径是限制搜索范围，这里是事后过滤，效果等价）。
     *  线程安全：只读 board / tpl，可多条规则并行调用。 */
    fun findBoardComponents(board: BoardView, tpl: Tpl, thr: Double, region: List<Int>? = null): BoardScan {
        val tw = tpl.full.cols(); val th = tpl.full.rows()
        val hw = tpl.half.cols(); val hh = tpl.half.rows()
        val bf = board.full
        val none = BoardScan(emptyList(), emptyList(), 0.0)
        if (hw < 8 || hh < 8 || tw > bf.cols() || th > bf.rows()) return none
        if (hw > board.half.cols() || hh > board.half.rows()) return none

        // 1. 半分辨率粗筛
        val res = Mat()
        Imgproc.matchTemplate(board.half, tpl.half, res, Imgproc.TM_CCOEFF_NORMED)
        val rc = res.cols(); val rr = res.rows()
        val arr = FloatArray(rc * rr)
        res.get(0, 0, arr)
        res.release()
        val coarseThr = (thr - COARSE_MARGIN).toFloat()
        var cnt = 0
        for (v in arr) if (v >= coarseThr) cnt++
        if (cnt == 0) return none
        val limit = if (cnt > 20000) thr.toFloat() else coarseThr   // 模板太通用时收紧，避免排序过大
        val idx = ArrayList<Int>()
        for (i in arr.indices) if (arr[i] >= limit) idx.add(i)
        idx.sortByDescending { arr[it] }

        // 2. 粗筛峰值去重（分数从高到低，互相距离 < 0.75×模板边长的丢掉）
        val md = max(hw, hh) * 0.75
        val md2 = md * md
        val cand = ArrayList<IntArray>()
        for (i in idx) {
            val cx = i % rc; val cy = i / rc
            var dup = false
            for (a in cand) {
                val dx = (cx - a[0]).toDouble(); val dy = (cy - a[1]).toDouble()
                if (dx * dx + dy * dy < md2) { dup = true; break }
            }
            if (!dup) { cand.add(intArrayOf(cx, cy)); if (cand.size >= MAX_CAND) break }
        }

        // 3. 全分辨率精修
        val inv = 1.0 / COARSE
        val accepted = ArrayList<Triple<Int, Int, Double>>()   // (左上 x, 左上 y（棋盘内坐标）, 相似度)
        val near = ArrayList<Near>()
        var best = 0.0
        for (a in cand) {
            val gx = (a[0] * inv).toInt(); val gy = (a[1] * inv).toInt()
            val rx = (gx - REFINE_WIN).coerceAtLeast(0)
            val ry = (gy - REFINE_WIN).coerceAtLeast(0)
            val rw = (gx + REFINE_WIN + tw).coerceAtMost(bf.cols()) - rx
            val rh = (gy + REFINE_WIN + th).coerceAtMost(bf.rows()) - ry
            if (rw < tw || rh < th) continue
            val sub = Mat(bf, Rect(rx, ry, rw, rh))
            val r2 = Mat()
            Imgproc.matchTemplate(sub, tpl.full, r2, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(r2)
            r2.release(); sub.release()
            val sc = mm.maxVal
            if (sc > best) best = sc
            val px = rx + mm.maxLoc.x.toInt(); val py = ry + mm.maxLoc.y.toInt()
            val cxF = px + tw / 2; val cyF = py + th / 2 + board.y0
            if (region != null && region.size >= 4 &&
                (cxF < region[0] || cxF > region[0] + region[2] || cyF < region[1] || cyF > region[1] + region[3])) continue
            if (sc >= thr) accepted.add(Triple(px, py, sc))
            else if (sc >= thr - NEAR_MARGIN) near.add(Near(cxF, cyF, sc))
        }
        lastImageScore = best
        if (accepted.isEmpty()) return BoardScan(emptyList(), near, best)

        // 4. 全分辨率去重（精修后可能收敛到同一只）
        val md3 = max(tw, th) * 0.75
        val md3sq = md3 * md3
        val uniq = ArrayList<Triple<Int, Int, Double>>()
        for (p in accepted.sortedByDescending { it.third }) {
            var dup = false
            for (u in uniq) {
                val dx = (p.first - u.first).toDouble(); val dy = (p.second - u.second).toDouble()
                if (dx * dx + dy * dy < md3sq) { dup = true; break }
            }
            if (!dup) uniq.add(p)
        }

        val comps = groupComps(uniq, 0, board.y0, tw, th, STEP_PX)
        lastClusterSize = comps.maxOfOrNull { it.size } ?: 0
        return BoardScan(comps, near, best)
    }

    /** 按上下左右相连分伙（斜向不算）。accepted = 各只怪模板左上角 (x, y, 相似度)，加上 offX/offY 即整帧坐标。 */
    private fun groupComps(accepted: List<Triple<Int, Int, Double>>, offX: Int, offY: Int, tw: Int, th: Int, step: Double): List<Comp> {
        fun adjacent(a: Triple<Int, Int, Double>, b: Triple<Int, Int, Double>): Boolean {
            val dx = abs(a.first - b.first).toDouble()
            val dy = abs(a.second - b.second).toDouble()
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
                Point(offX + top.first + tw / 2, offY + top.second + th / 2),
                group.size, top.third,
                group.map { offY + accepted[it].second + th / 2 },
                group.map { Point(offX + accepted[it].first + tw / 2, offY + accepted[it].second + th / 2) },
                group.map { accepted[it].third }
            ))
        }
        return comps
    }

    /** 0.23：按格中心点 + 每格相似度重新连片（跨类去重丢掉一些格之后用）。点已是整帧坐标中心，不再加偏移。 */
    fun regroup(cells: List<Point>, scores: List<Double>): List<Comp> {
        if (cells.isEmpty()) return emptyList()
        val acc = cells.indices.map { Triple(cells[it].x, cells[it].y, scores[it]) }
        return groupComps(acc, 0, 0, 0, 0, STEP_PX)
    }

    /** 旧路径（全帧全分辨率，逐规则转换）：0.19 仅在“快路径整轮零命中”时用来复核，也给带特殊 region 的单条规则兜底。
     *  逻辑与 0.18 一致，只是连片部分改调 groupComps。 */
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
            val mask = Mat()
            Core.compare(res, Scalar(thr), mask, Core.CMP_GE)
            val pts = MatOfPoint()
            Core.findNonZero(mask, pts)
            val rawList = pts.toList()
            mask.release(); pts.release()
            if (rawList.size > 2000) {
                AppLog.w("匹配", "连片模式：达标点过多（${rawList.size}），退回单点最佳")
                val p = Point(r[0] + mm.maxLoc.x.toInt() + t.cols() / 2, r[1] + mm.maxLoc.y.toInt() + t.rows() / 2)
                res.release()
                lastClusterSize = 1
                return listOf(Comp(p, 1, mm.maxVal, cells = listOf(p)))
            }
            val scored = rawList.map { p ->
                Triple(p.x.toInt(), p.y.toInt(), res.get(p.y.toInt(), p.x.toInt())[0])
            }.sortedByDescending { it.third }.let { if (it.size > 600) it.take(600) else it }
            res.release()
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
            val comps = groupComps(accepted, r[0], r[1], t.cols(), t.rows(), step)
            lastClusterSize = comps.maxOf { it.size }
            return comps
        } finally {
            roi.release(); src.release(); t.release()
        }
    }
}
