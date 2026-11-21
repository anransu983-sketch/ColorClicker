package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 记忆学习 v2（0.19）。
 *  1. 期限判断（新）：horizonAfterKill() 给 Runner 用。每施法一次全体上移一格，贴墙怪（第 0 排）再上移一格到 y1271（第 -1 排）仍可点，
 *     再移一格才撞墙。一次施法只能清一伙，所以“有几伙在期限前要清”比“一伙有几只”更决定存活。
 *  2. 棋盘预测校验 + 缺口诊断（扩）：预测对不上的格子逐个列出（“预测有而实测无”），近阈值漏数嫌疑，必要时存诊断截图。
 *  3. 相似度记忆：每种怪相似度均值/最低，跨局存盘。
 *  4. 两步前瞻（0.43）：0.43 前只做影子记录；0.43 起改由 Solver.pick 直接选点，onRound 不再累计分歧。
 *  5. 贴墙漏数日志（0.38，只记不改决策）：用「颜色辅助」的格子底色，量第 -1/0/1 排里“有怪底色却没检出”的格数，
 *     以及第 -2 排（已越过城墙线）出现的怪底色。见 auditWall / auditZeroFrame。
 *  数据存在 filesDir/guard_memory.json。 */
object BoardMemory {
    private const val COL0_X = 117
    private const val ROW0_Y = 1511
    private const val STEP = 240
    /** “没有可预见的过载”的哨兵值 */
    const val HZ_NONE = 99
    private const val MAX_DUMPS = 4

    data class Cell(val type: String, val col: Int, val row: Int)
    private data class TypeStat(var n: Int = 0, var sum: Double = 0.0, var min: Double = 9.9, var max: Double = 0.0) {
        val avg get() = if (n == 0) 0.0 else sum / n
    }

    private fun colOf(x: Int) = ((x - COL0_X + STEP / 2) / STEP).coerceIn(0, 5)
    /** 可为 -1（贴墙怪上移一格，y1271） */
    private fun rowOfY(y: Int) = ((y - ROW0_Y).toDouble() / STEP).roundToInt()

    /** 一伙里最靠墙那只所在排（可为 -1） */
    fun minRowOf(c: Matcher.Comp): Int = (c.ys.ifEmpty { listOf(c.point.y) }).minOf { rowOfY(it) }

    /** 期限判断：点掉 killed 这一伙之后，剩下的伙还能不能全部在撞墙前清完。
     *  规则：施法后全体上移一格；一伙里最靠墙那只在第 -1 排还能点，再上移就撞墙。一次施法清一伙。
     *  返回 -1：点完这一下，就有一伙上移越线（本轮就结束）；
     *  返回 k ≥ 0：此后第 k+1 次施法时必有一伙撞墙（k 越大越晚；0 = 下一次施法就会漏一伙，典型是两伙同时压线）；
     *  返回 HZ_NONE：按当前棋盘没有可预见的撞墙（不含将来新刷的怪）。 */
    fun horizonAfterKill(all: List<Pair<String, Matcher.Comp>>, killed: Matcher.Comp): Int {
        val rows = ArrayList<Int>()
        for ((_, c) in all) {
            if (c === killed) continue
            rows.add(minRowOf(c) - 1)   // 这一施法后上移一格
        }
        if (rows.isEmpty()) return HZ_NONE
        rows.sort()
        if (rows[0] < -1) return -1
        for ((i, p) in rows.withIndex()) {
            // 第 i+1 紧急的一伙，最晚能在 p+1 个决策点之内清掉，但前面已有 i+1 伙要清：i+1 > p+2 即过载
            if (i > p + 1) return p + 1
        }
        return HZ_NONE
    }

    private var appCtx: Context? = null
    private var predicted: List<Cell> = emptyList()
    private var predTotal = 0
    private var predHit = 0
    private var predNew = 0
    private var vanished = 0
    private var verified = 0
    private var topHit = 0
    private var topTotal = 0
    private var lastPicked: List<Cell> = emptyList()
    private var shots = 0
    private var suspect = 0
    private var overrides = 0
    private var casts = 0
    private var loggedKills = 0
    private var roundNo = 0
    private var dumps = 0
    private var battles = 0
    // 跨局累计（存盘）
    private var cumPredHit = 0
    private var cumPredTotal = 0
    private var cumVanished = 0
    private var cumVerified = 0
    private var cumTopHit = 0
    private var cumTopTotal = 0
    private var cumSuspect = 0
    private var cumOverrides = 0
    private var cumDivN = 0
    private var cumDivGain = 0.0
    private var cumCasts = 0
    private var cumKills = 0
    private val typeStats = HashMap<String, TypeStat>()
    private var loaded = false

    // ───────────── 0.38 贴墙漏数日志（只记不改决策）─────────────
    // 空地 = 沙漠底（229,172,105），不是“素色深色”；有怪的格子才按类型染底色。结算屏把整盘压暗，但色度（各通道占三通道和的比例）不变，
    // 所以一律用色度比，不用亮度：空地色度 ≈ (0.452,0.340,0.208)。
    private const val CORNER = 95            // 取样角离格中心的偏移（格半宽 120；取 95 避开怪的身体）
    private const val CORNER_WALL_Y = 85     // 第 -2 排只取下面两个角：y=1031+85=1116，仍在 BoardView/整帧内，且在城墙砖带之下
    private const val PATCH = 3              // 取样块半边长：7×7 像素取每通道中位数
    private const val NOT_SAND = 0.07        // 色度欧氏距离 > 0.07 = 底色不像空地（实测：怪底色离空地 ≥0.10，空地之间 <0.01）
    private const val SAME_CHROMA = 0.06     // 同一格的几个角色度距离 < 0.06 才算同一种底色
    private const val SAME_CHROMA_WALL = 0.12 // 第 -2 排两个下角的一致性放宽（砖墙底，且结算屏边缘有渐变）
    private const val SAT_MIN = 0.40         // 第 -2 排：饱和度 (max-min)/max ≥ 0.40 才算怪底色（砖墙是灰蓝，约 0.2）
    private const val REF_LEARN_TOL = 0.03   // 全零轮学空地色：角的色度离默认值 ≤0.03 的占 80% 以上才采信
    private val DEF_REF = doubleArrayOf(229.0 / 506, 172.0 / 506, 105.0 / 506)
    private var refChroma = DEF_REF
    private var refFromZero = false
    private var refLogged = false
    private var assistOffLogged = false
    private var assistOffBattle = false
    private var wallMiss = 0          // 本局：贴墙(-1~1排)漏数 格·轮（同一只没认出的怪连续几轮都算）
    private var wallMissNew = 0       // 本局：其中“新出现”的格数（上一轮上移一格的位置不在漏数里）≈ 漏掉的怪数
    private var outMiss = 0           // 本局：第 -2 排出现怪底色的格数
    private var prevMiss: Set<Pair<Int, Int>> = emptySet()
    private var cumWallMiss = 0
    private var cumWallMissNew = 0
    private var cumOutMiss = 0

    fun beginBattle(ctx: Context) {
        appCtx = ctx.applicationContext
        load(ctx)
        predicted = emptyList(); predTotal = 0; predHit = 0; predNew = 0
        vanished = 0; suspect = 0; overrides = 0; verified = 0; topHit = 0; topTotal = 0; lastPicked = emptyList(); shots = 0
        casts = 0; loggedKills = 0; roundNo = 0; dumps = 0
        wallMiss = 0; wallMissNew = 0; outMiss = 0; prevMiss = emptySet()
        refLogged = false; assistOffLogged = false; assistOffBattle = false
    }

    fun noteTap(kills: Int) { casts++; loggedKills += kills }
    fun noteOverride() { overrides++ }

    /** 每轮调用一次。comps=本轮所有伙（规则名→伙），pick=实际点的那伙（0.19 起是期限优先选出的）。
     *  frame/near 只用于缺口诊断（可不传）。0.43 起恒返回 null（两步前瞻已由 Solver 直接选点）。 */
    fun onRound(
        comps: List<Pair<String, Matcher.Comp>>,
        pick: Matcher.Comp?,
        pickRule: String,
        frame: Bitmap? = null,
        near: List<Pair<String, Matcher.Near>> = emptyList(),
        tpls: List<Pair<String, Matcher.Tpl>> = emptyList()
    ): String? {
        roundNo++
        val actual = comps.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
        }
        // 0. 0.38 贴墙漏数日志：只读这一帧已抓到的像素，不改任何决策
        if (frame != null) {
            val nearCells = near.map { (_, n) -> Pair(colOf(n.x), rowOfY(n.y)) }.toSet()
            auditWall(frame, actual.map { Pair(it.col, it.row) }.toSet(), nearCells, tpls)
        }
        var gone: List<Cell> = emptyList()
        // 1. 校验上一轮的预测
        if (predicted.isNotEmpty() && actual.isNotEmpty()) {
            gone = predicted.filter { pc -> actual.none { it.type == pc.type && it.col == pc.col && it.row == pc.row } }
            val hit = predicted.size - gone.size
            val fresh = actual.count { a -> predicted.none { it.type == a.type && it.col == a.col && it.row == a.row } }
            predHit += hit; predTotal += predicted.size; predNew += fresh; vanished += gone.size
            val top = predicted.filter { it.row <= 4 }
            topTotal += top.size; topHit += top.count { it !in gone }
            // 0.23 点后核实：被点那伙的格子上移一格后若原位还认得出同类怪 = 没清干净/没点中；
            // 实际消失格数 = 被点那伙格数 − 原位仍在 + 预测有而实测无。预测命中太低（棋盘没按预期动）时作废。
            if (lastPicked.isNotEmpty() && hit * 2 >= predicted.size) {
                val surv = lastPicked.count { p -> actual.any { it.type == p.type && it.col == p.col && it.row == p.row - 1 } }
                val kv = lastPicked.size - surv + gone.size
                verified += kv
                if (surv > 0 || kv != lastPicked.size) AppLog.w("记忆", "点后核实：记消 ${lastPicked.size} 只，核实消失 $kv 格（原位仍在 $surv 格，额外消失 ${gone.size} 格）")
            } else if (lastPicked.isNotEmpty()) {
                AppLog.d("记忆", "点后核实作废：预测命中 $hit/${predicted.size} 太低，棋盘没按预期动")
            }
            val goneTxt = if (gone.isEmpty()) "" else "；预测有而实测无 ${gone.size} 格：" +
                gone.take(6).joinToString("、") { "${it.type.removePrefix("守卫-")}@${it.col}列${it.row}排" }
            AppLog.d("记忆", "棋盘预测校验：命中 $hit/${predicted.size} 格，新刷 $fresh 只$goneTxt")
        }
        // 2. 近阈值漏数嫌疑：被点那伙旁边（上下左右）有同类怪，相似度略低于阈值，没被算进只数
        var sus = 0
        if (pick != null && near.isNotEmpty()) {
            val pc = pick.cells.ifEmpty { listOf(pick.point) }.map { Cell(pickRule, colOf(it.x), rowOfY(it.y)) }
            val seen = HashSet<Cell>()
            for ((name, n) in near) {
                if (name != pickRule) continue
                val cell = Cell(name, colOf(n.x), rowOfY(n.y))
                if (cell in pc) continue
                if (pc.any { abs(it.col - cell.col) + abs(it.row - cell.row) == 1 } && seen.add(cell)) sus++
            }
            if (sus > 0) {
                suspect += sus
                AppLog.w("记忆", "漏数嫌疑：被点那伙（${pick.size} 只）旁边有 $sus 格同类怪相似度略低于阈值，没算进只数，实际可能清得更多")
            }
        }
        if (gone.size >= 2 || sus > 0) dump(frame, if (sus > 0) "近阈值漏数嫌疑" else "预测消失 ${gone.size} 格")
        // 3. 相似度记忆
        for ((name, c) in comps) {
            val s = typeStats.getOrPut(name) { TypeStat() }
            s.n++; s.sum += c.score; if (c.score < s.min) s.min = c.score; if (c.score > s.max) s.max = c.score
        }
        // 4. 预测下一轮：去掉点的那伙，其余上移一行；row < -1 的是撞墙的，不进预测
        lastPicked = if (pick != null) pick.cells.ifEmpty { listOf(pick.point) }.map { Cell(pickRule, colOf(it.x), rowOfY(it.y)) } else emptyList()
        predicted = if (pick != null) {
            val picked = pick.cells.ifEmpty { listOf(pick.point) }
                .map { Cell(pickRule, colOf(it.x), rowOfY(it.y)) }.toSet()
            actual.filter { it !in picked }.map { Cell(it.type, it.col, it.row - 1) }.filter { it.row >= -1 }
        } else actual
        // 5. 0.43 已删：两步前瞻改由 Solver.pick 直接选点，onRound 不再返回提示。
        return null
    }

    // 0.33 HZ_CAP 3 → 6（只改选点）：封顶 3 会把余量 3/4/5/6/∞ 的候选当成一样安全，再按只数选，
    // 等于在“能多活几次施法”这一项上不分高下；模拟里余量封顶 ≥6 与不封顶等效，比封顶 3 多拿 3%~17% 总只数。
    // Runner 与 Solver 同用这个值（Runner 里 HZ_CAP = BoardMemory.HZ_CAP），不用另改。
    const val HZ_CAP = 6

    /** 模拟：点掉 tapped 一伙后其余上移一格。返回（是否有怪越线，下轮最大一伙只数）。 */
    fun simulateNext(actual: List<Cell>, tapped: Matcher.Comp, tappedName: String): Pair<Boolean, Double> {
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val rest = actual.filter { it !in tappedCells }.map { Cell(it.type, it.col, it.row - 1) }
        if (rest.any { it.row < -1 }) return true to 0.0
        val groups = groupCells(rest)
        if (groups.isEmpty()) return false to 0.0
        return false to groups.maxOf { it.size }.toDouble()
    }

    /** 0.43 本地最优解用：模拟点掉 tapped 一伙后下一轮的情况。
     *  返回（是否有怪越线，下轮最大一伙只数，下轮 row<=0 的格数）。复用 simulateNext，不改它的语义。 */
    fun simulateNextStats(all: List<Pair<String, Matcher.Comp>>, tapped: Matcher.Comp, tappedName: String): Triple<Boolean, Int, Int> {
        val actual = all.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
        }
        val (ends, next) = simulateNext(actual, tapped, tappedName)
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val threat = actual.filter { it !in tappedCells }.count { it.row - 1 <= 0 }
        return Triple(ends, next.toInt(), threat)
    }

    /** 按上下左右相连分伙（和 Matcher 一致：斜向不算一伙）。 */
    private fun groupCells(cells: List<Cell>): List<List<Cell>> {
        val left = cells.toMutableList()
        val groups = ArrayList<List<Cell>>()
        while (left.isNotEmpty()) {
            val seed = left.removeAt(0)
            val group = ArrayList<Cell>()
            val stack = ArrayDeque<Cell>()
            stack.add(seed); group.add(seed)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val it = left.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    if (o.type == cur.type && abs(o.col - cur.col) + abs(o.row - cur.row) == 1) {
                        it.remove(); stack.add(o); group.add(o)
                    }
                }
            }
            groups.add(group)
        }
        return groups
    }

    // ───────────── 0.38 贴墙漏数：取样与判定 ─────────────
    private class Px(val r: Int, val g: Int, val b: Int) {
        val sat: Double get() { val mx = maxOf(r, g, b); return if (mx == 0) 0.0 else (mx - minOf(r, g, b)).toDouble() / mx }
        val bright: Int get() = maxOf(r, g, b)
        fun chroma(): DoubleArray { val t = (r + g + b).coerceAtLeast(1).toDouble(); return doubleArrayOf(r / t, g / t, b / t) }
    }

    private fun cdist(a: DoubleArray, b: DoubleArray): Double {
        val d0 = a[0] - b[0]; val d1 = a[1] - b[1]; val d2 = a[2] - b[2]
        return sqrt(d0 * d0 + d1 * d1 + d2 * d2)
    }

    private fun med(v: IntArray): Int { v.sort(); return v[v.size / 2] }

    /** 以 (x,y) 为中心的 7×7 块，每通道取中位数；出界返回 null。一次 getPixels，不转 Mat、不多截帧。 */
    private fun patch(f: Bitmap, x: Int, y: Int): Px? {
        val n = 2 * PATCH + 1
        if (x - PATCH < 0 || y - PATCH < 0 || x + PATCH >= f.width || y + PATCH >= f.height) return null
        val buf = IntArray(n * n)
        f.getPixels(buf, 0, n, x - PATCH, y - PATCH, n, n)
        return Px(
            med(IntArray(buf.size) { (buf[it] shr 16) and 255 }),
            med(IntArray(buf.size) { (buf[it] shr 8) and 255 }),
            med(IntArray(buf.size) { buf[it] and 255 })
        )
    }

    /** 第 col 列第 row 排这一格“底色有染”吗（= 有怪）。四个角里 ≥3 个非空地色、且彼此色度一致才算；取不全返回 null。
     *  0.39：-1 排上面两个角（y=1271-95=1176）在城墙砖带里（墙底 ~1200），读到砖墙不是格子底色，
     *  四角互认会被顶掉——27 局撞墙的那个 -1 排怪就是这么漏的。-1 排只看下面两个角（和 -2 排一个路子）。 */
    private fun monsterBg(f: Bitmap, col: Int, row: Int): Boolean? {
        val cx = COL0_X + col * STEP
        val cy = ROW0_Y + row * STEP
        val offs = if (row == -1) listOf(-CORNER to CORNER, CORNER to CORNER)
            else listOf(-CORNER to -CORNER, CORNER to -CORNER, -CORNER to CORNER, CORNER to CORNER)
        val ps = offs.mapNotNull { (dx, dy) -> patch(f, cx + dx, cy + dy) }
        val need = if (row == -1) 2 else 3
        if (ps.size < need) return null
        val ch = ps.map { it.chroma() }
        val non = ps.indices.filter { cdist(ch[it], refChroma) > NOT_SAND }
        if (non.size < need) return false
        return non.count { i -> non.all { j -> cdist(ch[i], ch[j]) < SAME_CHROMA } } >= need
    }

    /** 第 -2 排（越过城墙线）：背景是灰蓝砖墙，不能和空地色比，改看饱和度。只取下面两个角，两个都要饱和且色度相近。 */
    private fun beyondWallBg(f: Bitmap, col: Int): Boolean {
        val cx = COL0_X + col * STEP
        val cy = ROW0_Y - 2 * STEP + CORNER_WALL_Y
        val a = patch(f, cx - CORNER, cy) ?: return false
        val b = patch(f, cx + CORNER, cy) ?: return false
        if (a.sat < SAT_MIN || b.sat < SAT_MIN || a.bright < 20 || b.bright < 20) return false
        return cdist(a.chroma(), b.chroma()) < SAME_CHROMA_WALL
    }

    private fun colTxt(cols: List<Int>) = cols.joinToString("、") { "第 ${it + 1} 列" }

    /** 每轮一次：数第 -1/0/1 排里“底色有怪、模板却没检出”的格子（共 18 格），再看第 -2 排有没有怪底色。列号 1~6 从左到右，排号同棋盘。 */
    private fun auditWall(frame: Bitmap, detected: Set<Pair<Int, Int>>, nearCells: Set<Pair<Int, Int>>, tpls: List<Pair<String, Matcher.Tpl>> = emptyList()) {
        try {
            if (frame.isRecycled) return
            if (!refLogged) {
                refLogged = true
                AppLog.i("记忆", "贴墙漏数：空地参考色度 ${"%.3f".format(refChroma[0])}/${"%.3f".format(refChroma[1])}/${"%.3f".format(refChroma[2])}" +
                    "（来源：${if (refFromZero) "全零轮学到" else "固定默认值，本局还没有全零轮可学"}；有怪判据＝色度距离>0.07）；列号 1~6 从左到右，排 0=贴墙排，-1=墙后一格")
            }
            // 颜色辅助开了没：抽至多 8 个已检出的格子，看底色是不是“非空地”。检出的格子底色几乎都像空地 = 没开，整轮跳过，不把素色盘当漏数。
            var sampled = 0; var tinted = 0
            for ((c, r) in detected.take(8)) {
                val t = monsterBg(frame, c, r) ?: continue
                sampled++; if (t) tinted++
            }
            if (sampled >= 2 && tinted * 4 < sampled) {
                assistOffBattle = true
                if (!assistOffLogged) {
                    assistOffLogged = true
                    AppLog.w("记忆", "颜色辅助未开（抽查 $sampled 个检出的格子，只有 $tinted 个底色有染），贴墙漏数检测跳过")
                }
                return
            }
            val miss = ArrayList<Pair<Int, Int>>()
            for (r in -1..1) for (c in 0..5) {
                if (Pair(c, r) in detected) continue
                if (monsterBg(frame, c, r) == true) miss.add(Pair(c, r))
            }
            // 新出现：上一轮同一只若在下一排（施法后上移一格），位置是 (c, r+1)，已在上一轮漏数里就不再算新的
            val newCnt = miss.count { (c, r) -> Pair(c, r + 1) !in prevMiss }
            prevMiss = miss.toSet()
            wallMiss += miss.size; wallMissNew += newCnt
            if (miss.isEmpty()) {
                AppLog.d("记忆", "贴墙漏数：0 格")
            } else {
                // 0.40：每个漏数格逐规则算小窗最佳分（原生分辨率）。已算好的匹配响应只留 ≥阈值和近阈值点，
                // 墙边怪若只有 0.3 分在里面根本留不下痕——重算一次才能看到它到底是 0.75 还是 0.3。
                val diag = if (tpls.isNotEmpty()) {
                    miss.take(6).associateWith { (c, r) ->
                        val p = cellCenter(c, r)
                        Matcher.bestScoresAtCell(frame, tpls, p.x, p.y)
                            .sortedByDescending { it.second }.take(2)
                    }
                } else emptyMap()
                val diagTxt = if (diag.isEmpty()) "" else "；逐格最佳分：" + diag.entries.joinToString("；") { (cell, top) ->
                    "第 ${cell.first + 1} 列第 ${cell.second} 排→" + if (top.isEmpty()) "无响应" else top.joinToString("/") { "${it.first.removePrefix("守卫-")}${"%.3f".format(it.second)}" }
                }
                AppLog.w("记忆", "贴墙漏数：${miss.size} 格（" + miss.joinToString("、") {
                    "第 ${it.first + 1} 列第 ${it.second} 排" + if (it in nearCells) "·近阈值" else ""
                } + "），其中新出现 $newCnt 格；这些格底色有怪，模板没检出$diagTxt")
            }
            val out = (0..5).filter { beyondWallBg(frame, it) }
            if (out.isNotEmpty()) {
                outMiss += out.size
                AppLog.w("记忆", "贴墙外漏数：第 -2 排有 ${out.size} 格怪底色（${colTxt(out)}）——怪已越过城墙线；识别窗口 y≥1100 看不到第 -2 排，候选里不会有它们")
            }
        } catch (e: Exception) {
            AppLog.w("记忆", "贴墙漏数检测出错，本轮跳过：${e.javaClass.simpleName}")
        }
    }

    /** 格子中心（原生坐标）。BoardMemory 自己的几何：第一列中心 117、第一排中心 1511、格距 240。 */
    fun cellCenter(col: Int, row: Int) = Point(COL0_X + col * STEP, ROW0_Y + row * STEP)

    /** 0.39 贴墙漏数代点：上一轮漏数的第 0 排格子，这一轮已上移到 -1 排（再不点，下一施法就撞墙）。
     *  传进本轮模板已检出的格子，去掉已检出的——剩下的就是“底色有怪、模板认不出、马上撞墙”的格子。
     *  注意时序：Runner 在选点时调它，那时 prevMiss 还是上一轮的（本轮 auditWall 在 onRound 里才跑），
     *  所以第 0 排漏数正好对应现在的 -1 排；上一轮的 -1 排漏数现在已到 -2 排，撞了，没得点。 */
    fun wallMissFallback(detected: Set<Pair<Int, Int>>): List<Pair<Int, Int>> =
        prevMiss.filter { it.second == 0 }.map { Pair(it.first, -1) }
            .filter { it !in detected }

    /** Runner 在“点过之后第一次整盘全零”时调用（每局最多 3 次）：① 学空地参考色（角的色度大多贴近默认值才采信，压暗的结算屏也适用）；
     *  ② 看第 -2 排有没有怪底色。整盘全零的帧可能是结算屏（压暗、模板全认不出），所以这里不数第 -1~1 排，免得把压暗误报成漏数。 */
    fun auditZeroFrame(frame: Bitmap?) {
        if (frame == null || frame.isRecycled) return
        try {
            val chs = ArrayList<DoubleArray>()
            var total = 0
            for (r in -1..6) for (c in 0..5) {
                val cx = COL0_X + c * STEP; val cy = ROW0_Y + r * STEP
                for ((dx, dy) in listOf(-CORNER to -CORNER, CORNER to -CORNER, -CORNER to CORNER, CORNER to CORNER)) {
                    val px = patch(frame, cx + dx, cy + dy) ?: continue
                    total++
                    val ch = px.chroma()
                    if (cdist(ch, DEF_REF) <= REF_LEARN_TOL) chs.add(ch)
                }
            }
            if (total >= 100 && chs.size * 10 >= total * 8) {
                val m = DoubleArray(3) { k -> chs.map { it[k] }.sorted()[chs.size / 2] }
                refChroma = m; refFromZero = true
                AppLog.i("记忆", "贴墙漏数：全零轮学到空地参考色度 ${"%.3f".format(m[0])}/${"%.3f".format(m[1])}/${"%.3f".format(m[2])}（${chs.size}/$total 个角贴近默认值）")
            }
            val out = (0..5).filter { beyondWallBg(frame, it) }
            if (out.isNotEmpty()) {
                outMiss += out.size
                AppLog.w("记忆", "贴墙外漏数（整盘全零帧）：第 -2 排有 ${out.size} 格怪底色（${colTxt(out)}）——怪已越过城墙线")
            }
        } catch (e: Exception) {
            AppLog.w("记忆", "贴墙外检测出错：${e.javaClass.simpleName}")
        }
    }

    private fun dump(frame: Bitmap?, why: String) {
        val ctx = appCtx ?: return
        if (frame == null || dumps >= MAX_DUMPS) return
        dumps++
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, "gap_${battles + 1}_$roundNo.jpg")
            FileOutputStream(f).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            AppLog.i("记忆", "已存诊断截图 ${f.absolutePath}（$why）")
        } catch (e: Exception) {
            AppLog.w("记忆", "诊断截图保存失败")
        }
    }

    /** 0.23：存一张图到 App 外部私有目录（日志里有完整路径）。每局最多 8 张。 */
    fun saveBitmap(bmp: Bitmap?, tag: String) {
        val ctx = appCtx ?: return
        if (bmp == null || bmp.isRecycled || shots >= 8) return
        shots++
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, "shot_${battles + 1}_$tag.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            AppLog.i("复盘", "已存图 ${f.absolutePath}")
        } catch (e: Exception) {
            AppLog.w("复盘", "存图失败：$tag")
        }
    }

    /** 一局结束时记一笔：没打过（0 施法 0 预测）就静默，只清预测。 */
    fun endBattle(ctx: Context) {
        if (casts == 0 && predTotal == 0) {
            predicted = emptyList()
            return
        }
        battles++
        cumPredHit += predHit; cumPredTotal += predTotal; cumVanished += vanished; cumSuspect += suspect
        cumVerified += verified; cumTopHit += topHit; cumTopTotal += topTotal
        cumOverrides += overrides
        cumCasts += casts; cumKills += loggedKills
        cumWallMiss += wallMiss; cumWallMissNew += wallMissNew; cumOutMiss += outMiss
        save(ctx)
        val pred = if (predTotal > 0) "$predHit/$predTotal" else "—"
        AppLog.i("记忆", "本局记忆：施法 $casts 次，记消 $loggedKills 只（${"%.2f".format(if (casts > 0) loggedKills.toDouble() / casts else 0.0)} 只/次）；" +
            "预测命中 $pred 格，新刷 $predNew 只；预测有而实测无 $vanished 格；近阈值漏数嫌疑 $suspect 格；" +
            wallMissText() + "期限优先改点 $overrides 次")
        AppLog.i("记忆", "核实对账：记消 $loggedKills 只；点后核实清掉 $verified 只（逐施法对照前后棋盘，不依赖结算；两者差 ${verified - loggedKills}）；" +
            "上部(排≤4)预测命中 ${if (topTotal > 0) "$topHit/$topTotal" else "—"}；漏数嫌疑 $suspect 格；" + wallMissText().trimEnd('；'))
        val rate = if (cumPredTotal > 0) "%.1f%%".format(100.0 * cumPredHit / cumPredTotal) else "—"
        AppLog.i("记忆", "累计 $battles 局：预测命中率 $rate（上部 ${if (cumTopTotal > 0) "%.1f%%".format(100.0 * cumTopHit / cumTopTotal) else "—"}），期限优先改点 $cumOverrides 次，累计施法 $cumCasts 次 / 记消 $cumKills 只")
        AppLog.i("记忆", "贴墙漏数累计：$cumWallMiss 格·轮（约 $cumWallMissNew 只），贴墙外 $cumOutMiss 格（含本局）")
        val sim = typeStats.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key.removePrefix("守卫-")}出现${it.value.n}次均${"%.2f".format(it.value.avg)}最低${"%.2f".format(it.value.min)}" }
        AppLog.i("记忆", "相似度累计：$sim")
        for ((name, s) in typeStats) {
            if (s.min < 0.68) AppLog.w("记忆", "「${name.removePrefix("守卫-")}」相似度最低到 ${"%.3f".format(s.min)}（均值 ${"%.2f".format(s.avg)}），阈值可能要下调，下局留意")
        }
        predicted = emptyList()
    }

    /** 本局贴墙漏数的一小段文字，供「本局记忆 / 核实对账」两行并排放在漏数嫌疑旁边 */
    private fun wallMissText(): String =
        if (assistOffBattle) "贴墙漏数 —（本局颜色辅助未开，未检测）；"
        else "贴墙漏数 $wallMiss 格·轮（约 $wallMissNew 只）；贴墙外漏数 $outMiss 格；"

    /** 给界面看的累计摘要（主界面「查看记忆学习」用）；没打过任何一局返回 null。 */
    fun summaryText(ctx: Context): String? {
        load(ctx)
        if (battles == 0 && cumCasts == 0) return null
        val rate = if (cumPredTotal > 0) "%.1f%%".format(100.0 * cumPredHit / cumPredTotal) else "—"
        val topRate = if (cumTopTotal > 0) "%.1f%%".format(100.0 * cumTopHit / cumTopTotal) else "—"
        val sb = StringBuilder()
        sb.append("累计 $battles 局，施法 $cumCasts 次，记消 $cumKills 只\n")
        sb.append("棋盘预测命中 $cumPredHit/$cumPredTotal（$rate），上部（排≤4）$cumTopHit/$cumTopTotal（$topRate）\n")
        sb.append("点后核实清掉 $cumVerified 只，预测有而实测无 $cumVanished 格，漏数嫌疑 $cumSuspect 格\n")
        sb.append("贴墙漏数 $cumWallMiss 格·轮（约 $cumWallMissNew 只），贴墙外 $cumOutMiss 格\n")
        sb.append("期限优先改点 $cumOverrides 次\n")
        val sim = typeStats.entries.sortedBy { it.key }
            .joinToString("\n") { "「${it.key.removePrefix("守卫-")}」出现 ${it.value.n} 次，均值 ${"%.2f".format(it.value.avg)}，最低 ${"%.2f".format(it.value.min)}" }
        if (sim.isNotEmpty()) sb.append("相似度累计：\n$sim")
        return sb.toString()
    }

    private fun memFile(ctx: Context) = File(ctx.filesDir, "guard_memory.json")

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = memFile(ctx)
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            battles = j.optInt("battles", 0)
            cumPredHit = j.optInt("cumPredHit"); cumPredTotal = j.optInt("cumPredTotal")
            cumVanished = j.optInt("cumVanished"); cumSuspect = j.optInt("cumSuspect")
            cumVerified = j.optInt("cumVerified"); cumTopHit = j.optInt("cumTopHit"); cumTopTotal = j.optInt("cumTopTotal")
            cumOverrides = j.optInt("cumOverrides"); cumDivN = j.optInt("cumDivN")
            cumDivGain = j.optDouble("cumDivGain", 0.0)
            cumCasts = j.optInt("cumCasts"); cumKills = j.optInt("cumKills")
            cumWallMiss = j.optInt("cumWallMiss"); cumWallMissNew = j.optInt("cumWallMissNew"); cumOutMiss = j.optInt("cumOutMiss")
            val types = j.optJSONObject("types") ?: return
            for (k in types.keys()) {
                val o = types.getJSONObject(k)
                typeStats[k] = TypeStat(o.optInt("n"), o.optDouble("sum"), o.optDouble("min", 9.9), o.optDouble("max"))
            }
        } catch (_: Exception) {
            AppLog.w("记忆", "记忆文件读失败，从头记")
        }
    }

    private fun save(ctx: Context) {
        try {
            val types = JSONObject()
            for ((k, s) in typeStats) {
                types.put(k, JSONObject().apply {
                    put("n", s.n); put("sum", s.sum); put("min", s.min); put("max", s.max)
                })
            }
            memFile(ctx).writeText(JSONObject().apply {
                put("battles", battles); put("types", types)
                put("cumPredHit", cumPredHit); put("cumPredTotal", cumPredTotal)
                put("cumVanished", cumVanished); put("cumSuspect", cumSuspect)
                put("cumVerified", cumVerified); put("cumTopHit", cumTopHit); put("cumTopTotal", cumTopTotal)
                put("cumOverrides", cumOverrides); put("cumDivN", cumDivN); put("cumDivGain", cumDivGain)
                put("cumCasts", cumCasts); put("cumKills", cumKills)
                put("cumWallMiss", cumWallMiss); put("cumWallMissNew", cumWallMissNew); put("cumOutMiss", cumOutMiss)
            }.toString())
        } catch (e: Exception) {
            AppLog.e("记忆", "记忆文件写失败", e)
        }
    }
}
