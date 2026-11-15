package com.example.colorclicker

import android.content.Context
import android.graphics.Point
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/** 记忆学习 v1（影子模式：只记、只算、只写日志，不改点哪只的决定）：
 *  1. 棋盘预测校验——每轮按“点掉一伙、其余上移一格”预测下轮棋盘，下轮拿实测对照，记命中率；
 *     预测对不上就说明有新刷怪或识别漏数（之前结算差 7~19 只的缺口就藏在这里）。
 *  2. 相似度记忆——每种怪每轮的相似度记均值/最低，跨局累积；偏低只写日志提醒，不自动改阈值。
 *  3. 影子前瞻——每轮额外算“点这伙、再点下轮最好的一伙”的两步价值，只在和贪心不一致时写一行日志，
 *     决定仍按贪心走；攒够 3 局数据再判断前瞻值不值得转正。
 *  数据存在 filesDir/guard_memory.json，重启也不丢。 */
object BoardMemory {
    private const val COL0_X = 117
    private const val ROW0_Y = 1511
    private const val STEP = 240
    private const val W0 = 3.0
    private const val W1 = 0.5

    data class Cell(val type: String, val col: Int, val row: Int)
    private data class TypeStat(var n: Int = 0, var sum: Double = 0.0, var min: Double = 9.9, var max: Double = 0.0) {
        val avg get() = if (n == 0) 0.0 else sum / n
    }

    private fun colOf(x: Int) = ((x - COL0_X + STEP / 2) / STEP).coerceIn(0, 5)
    private fun rowOfY(y: Int) = ((y - ROW0_Y).toDouble() / STEP).roundToInt()  // 可为 -1（贴墙怪上移一格，y1271）
    private fun compValue(c: Matcher.Comp): Double {
        val ys = if (c.ys.isEmpty()) listOf(c.point.y) else c.ys
        return c.size + W0 * ys.count { rowOfY(it) <= 0 } + W1 * ys.count { rowOfY(it) == 1 }
    }
    private fun compValueCells(cells: List<Cell>): Double =
        cells.size + W0 * cells.count { it.row <= 0 } + W1 * cells.count { it.row == 1 }

    private var predicted: List<Cell> = emptyList()
    private var predTotal = 0
    private var predHit = 0
    private var predNew = 0
    private var shadowAgree = 0
    private var shadowDiffer = 0
    private var casts = 0
    private var loggedKills = 0
    private var battles = 0
    private val typeStats = HashMap<String, TypeStat>()
    private var loaded = false

    fun beginBattle(ctx: Context) {
        load(ctx)
        predicted = emptyList(); predTotal = 0; predHit = 0; predNew = 0
        shadowAgree = 0; shadowDiffer = 0; casts = 0; loggedKills = 0
    }

    fun noteTap(kills: Int) { casts++; loggedKills += kills }

    /** 每轮调用一次。comps=本轮所有伙（规则名→伙），pick=贪心点的那伙。返回影子前瞻提示（与贪心一致时为 null）。 */
    fun onRound(comps: List<Pair<String, Matcher.Comp>>, pick: Matcher.Comp?, pickRule: String): String? {
        val actual = comps.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
        }
        // 1. 校验上一轮的预测
        if (predicted.isNotEmpty() && actual.isNotEmpty()) {
            var hit = 0
            for (pc in predicted) if (actual.any { it.type == pc.type && it.col == pc.col && it.row == pc.row }) hit++
            val fresh = actual.count { a -> predicted.none { it.type == a.type && it.col == a.col && it.row == a.row } }
            predHit += hit; predTotal += predicted.size; predNew += fresh
            AppLog.d("记忆", "棋盘预测校验：命中 $hit/${predicted.size} 格，新刷 $fresh 只")
        }
        // 2. 相似度记忆（记每伙最上面那只的相似度）
        for ((name, c) in comps) {
            val s = typeStats.getOrPut(name) { TypeStat() }
            s.n++; s.sum += c.score; if (c.score < s.min) s.min = c.score; if (c.score > s.max) s.max = c.score
        }
        // 3. 预测下一轮：去掉点的那伙，其余上移一行；row < -1 的是撞墙死的，不进预测
        // （她 11:08 确认：怪碰到城墙=一轮结束，然后退回；贴墙怪上移一格到 y1271 还没碰到，再移一格到 y1031 才算碰到）
        predicted = if (pick != null) {
            val picked = pick.cells.ifEmpty { listOf(pick.point) }
                .map { Cell(pickRule, colOf(it.x), rowOfY(it.y)) }.toSet()
            actual.filter { it !in picked }.map { Cell(it.type, it.col, it.row - 1) }.filter { it.row >= -1 }
        } else actual
        // 4. 影子前瞻：贪心前三里算两步价值（本轮只数 + 0.5×下轮最好一伙的价值）
        if (pick == null || comps.size < 2) return null
        val ranked = comps.sortedWith(
            compareByDescending<Pair<String, Matcher.Comp>> { compValue(it.second) }.thenBy { it.second.point.y }
        )
        val greedyName = ranked.first().first
        data class Two(val name: String, val comp: Matcher.Comp, val two: Double, val next: Double, val ends: Boolean)
        var best: Two? = null
        for ((name, c) in ranked.take(3)) {
            val (ends, next) = simulateNext(actual, c, name)
            val two = c.size + if (ends) 0.0 else 0.5 * next
            val cand = Two(name, c, two, next, ends)
            if (best == null || two > best.two) best = cand
        }
        val b = best ?: return null
        return if (b.name != greedyName) {
            shadowDiffer++
            "记忆·前瞻：贪心点「${greedyName.removePrefix("守卫-")}」价值${"%.1f".format(compValue(ranked.first().second))}；" +
                "两步算「${b.name.removePrefix("守卫-")}」更优（本轮${b.comp.size}只" +
                (if (b.ends) "，点完即撞墙" else "＋下轮${"%.1f".format(b.next)}×0.5=${"%.1f".format(b.two)}") +
                "），本轮仍按贪心点（影子模式）"
        } else {
            shadowAgree++
            null
        }
    }

    /** 模拟：点掉 tapped 一伙后其余上移一格。返回（是否撞墙结束，下轮最好一伙的价值）。 */
    private fun simulateNext(actual: List<Cell>, tapped: Matcher.Comp, tappedName: String): Pair<Boolean, Double> {
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val rest = actual.filter { it !in tappedCells }.map { Cell(it.type, it.col, it.row - 1) }
        if (rest.any { it.row < -1 }) return true to 0.0   // 有怪上移到墙里了，这轮就是最后一击
        val groups = groupCells(rest)
        if (groups.isEmpty()) return false to 0.0
        return false to groups.maxOf { compValueCells(it) }
    }

    /** 按上下左右相连分伙（和 Matcher 的连片规则一致：斜向不算一伙）。 */
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
                    if (o.type == cur.type && kotlin.math.abs(o.col - cur.col) + kotlin.math.abs(o.row - cur.row) == 1) {
                        it.remove(); stack.add(o); group.add(o)
                    }
                }
            }
            groups.add(group)
        }
        return groups
    }

    /** 一局结束时记一笔：没打过（0 施法 0 预测）就静默，只清预测。 */
    fun endBattle(ctx: Context) {
        if (casts == 0 && predTotal == 0 && shadowAgree == 0 && shadowDiffer == 0) {
            predicted = emptyList()
            return
        }
        battles++
        save(ctx)
        val pred = if (predTotal > 0) "$predHit/$predTotal" else "—"
        val sim = typeStats.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key.removePrefix("守卫-")}均${"%.2f".format(it.value.avg)}最低${"%.2f".format(it.value.min)}" }
        AppLog.i("记忆", "本局记忆：施法 $casts 次，记消 $loggedKills 只；预测命中 $pred 格，新刷 $predNew 只；" +
            "影子前瞻一致 $shadowAgree 次、不同 $shadowDiffer 次；相似度累计：$sim")
        for ((name, s) in typeStats) {
            if (s.min < 0.78) AppLog.w("记忆", "「${name.removePrefix("守卫-")}」相似度最低到 ${"%.3f".format(s.min)}（均值 ${"%.2f".format(s.avg)}），阈值可能要下调，下局留意")
        }
        predicted = emptyList()
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
            memFile(ctx).writeText(JSONObject().apply { put("battles", battles); put("types", types) }.toString())
        } catch (e: Exception) {
            AppLog.e("记忆", "记忆文件写失败", e)
        }
    }
}
