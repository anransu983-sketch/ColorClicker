package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** 记忆学习 v2（0.19）。
 *  1. 期限判断（新）：horizonAfterKill() 给 Runner 用。每施法一次全体上移一格，贴墙怪（第 0 排）再上移一格到 y1271（第 -1 排）仍可点，
 *     再移一格才撞墙。一次施法只能清一伙，所以“有几伙在期限前要清”比“一伙有几只”更决定存活。
 *  2. 棋盘预测校验 + 缺口诊断（扩）：预测对不上的格子逐个列出（“预测有而实测无”），近阈值漏数嫌疑，必要时存诊断截图。
 *  3. 相似度记忆：每种怪相似度均值/最低，跨局存盘。
 *  4. 影子前瞻（改）：只在“同样安全”的候选里比两步值；分歧时记 差值，跨局累计，供判断要不要转正。
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
    private var shadowAgree = 0
    private var shadowDiffer = 0
    private var shadowGain = 0.0
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

    fun beginBattle(ctx: Context) {
        appCtx = ctx.applicationContext
        load(ctx)
        predicted = emptyList(); predTotal = 0; predHit = 0; predNew = 0
        vanished = 0; suspect = 0; overrides = 0; verified = 0; topHit = 0; topTotal = 0; lastPicked = emptyList(); shots = 0
        shadowAgree = 0; shadowDiffer = 0; shadowGain = 0.0
        casts = 0; loggedKills = 0; roundNo = 0; dumps = 0
    }

    fun noteTap(kills: Int) { casts++; loggedKills += kills }
    fun noteOverride() { overrides++ }

    /** 每轮调用一次。comps=本轮所有伙（规则名→伙），pick=实际点的那伙（0.19 起是期限优先选出的）。
     *  frame/near 只用于缺口诊断（可不传）。返回影子前瞻提示（无分歧时为 null）。 */
    fun onRound(
        comps: List<Pair<String, Matcher.Comp>>,
        pick: Matcher.Comp?,
        pickRule: String,
        frame: Bitmap? = null,
        near: List<Pair<String, Matcher.Near>> = emptyList()
    ): String? {
        roundNo++
        val actual = comps.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
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
        // 5. 影子前瞻：只在“和实际点的一样安全”的候选里，比 本轮只数 + 0.5×下轮最大一伙只数
        if (pick == null || comps.size < 2) return null
        val pickHz = horizonAfterKill(comps, pick)
        val floor = minOf(pickHz, HZ_CAP)
        data class Two(val name: String, val comp: Matcher.Comp, val two: Double, val next: Double, val ends: Boolean)
        fun twoOf(name: String, c: Matcher.Comp): Two {
            val (ends, next) = simulateNext(actual, c, name)
            return Two(name, c, c.size + if (ends) 0.0 else 0.5 * next, next, ends)
        }
        val pickTwo = twoOf(pickRule, pick)
        val alts = comps.sortedByDescending { it.second.size }.take(4)
            .filter { it.second !== pick && minOf(horizonAfterKill(comps, it.second), HZ_CAP) >= floor }
            .map { twoOf(it.first, it.second) }
        val bestAlt = alts.maxByOrNull { it.two }
        return if (bestAlt != null && bestAlt.two > pickTwo.two + 0.01) {
            shadowDiffer++
            shadowGain += bestAlt.two - pickTwo.two
            "记忆·前瞻：实点「${pickRule.removePrefix("守卫-")}」${pick.size}只（两步值${"%.1f".format(pickTwo.two)}）；" +
                "两步算「${bestAlt.name.removePrefix("守卫-")}」${bestAlt.comp.size}只更优（${"%.1f".format(bestAlt.two)}，" +
                "差 +${"%.1f".format(bestAlt.two - pickTwo.two)}），本轮仍按实点（影子模式）"
        } else {
            shadowAgree++
            null
        }
    }

    const val HZ_CAP = 3   // Runner 同口径：余量 ≥ 3 次施法的视为一样安全

    /** 模拟：点掉 tapped 一伙后其余上移一格。返回（是否有怪越线，下轮最大一伙只数）。 */
    private fun simulateNext(actual: List<Cell>, tapped: Matcher.Comp, tappedName: String): Pair<Boolean, Double> {
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val rest = actual.filter { it !in tappedCells }.map { Cell(it.type, it.col, it.row - 1) }
        if (rest.any { it.row < -1 }) return true to 0.0
        val groups = groupCells(rest)
        if (groups.isEmpty()) return false to 0.0
        return false to groups.maxOf { it.size }.toDouble()
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
        if (casts == 0 && predTotal == 0 && shadowAgree == 0 && shadowDiffer == 0) {
            predicted = emptyList()
            return
        }
        battles++
        cumPredHit += predHit; cumPredTotal += predTotal; cumVanished += vanished; cumSuspect += suspect
        cumVerified += verified; cumTopHit += topHit; cumTopTotal += topTotal
        cumOverrides += overrides; cumDivN += shadowDiffer; cumDivGain += shadowGain
        cumCasts += casts; cumKills += loggedKills
        save(ctx)
        val pred = if (predTotal > 0) "$predHit/$predTotal" else "—"
        AppLog.i("记忆", "本局记忆：施法 $casts 次，记消 $loggedKills 只（${"%.2f".format(if (casts > 0) loggedKills.toDouble() / casts else 0.0)} 只/次）；" +
            "预测命中 $pred 格，新刷 $predNew 只；预测有而实测无 $vanished 格；近阈值漏数嫌疑 $suspect 格；期限优先改点 $overrides 次；" +
            "影子前瞻一致 $shadowAgree 次、不同 $shadowDiffer 次" + if (shadowDiffer > 0) "（平均差 +${"%.2f".format(shadowGain / shadowDiffer)}）" else "")
        AppLog.i("记忆", "核实对账：记消 $loggedKills 只；点后核实清掉 $verified 只（逐施法对照前后棋盘，不依赖结算；两者差 ${verified - loggedKills}）；" +
            "上部(排≤4)预测命中 ${if (topTotal > 0) "$topHit/$topTotal" else "—"}；漏数嫌疑 $suspect 格")
        val rate = if (cumPredTotal > 0) "%.1f%%".format(100.0 * cumPredHit / cumPredTotal) else "—"
        AppLog.i("记忆", "累计 $battles 局：预测命中率 $rate（上部 ${if (cumTopTotal > 0) "%.1f%%".format(100.0 * cumTopHit / cumTopTotal) else "—"}），前瞻分歧 $cumDivN 次" +
            (if (cumDivN > 0) "（平均差 +${"%.2f".format(cumDivGain / cumDivN)}）" else "") +
            "，期限优先改点 $cumOverrides 次，累计施法 $cumCasts 次 / 记消 $cumKills 只")
        val sim = typeStats.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key.removePrefix("守卫-")}均${"%.2f".format(it.value.avg)}最低${"%.2f".format(it.value.min)}" }
        AppLog.i("记忆", "相似度累计：$sim")
        for ((name, s) in typeStats) {
            if (s.min < 0.68) AppLog.w("记忆", "「${name.removePrefix("守卫-")}」相似度最低到 ${"%.3f".format(s.min)}（均值 ${"%.2f".format(s.avg)}），阈值可能要下调，下局留意")
        }
        predicted = emptyList()
    }

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
        sb.append("期限优先改点 $cumOverrides 次；影子前瞻分歧 $cumDivN 次")
        if (cumDivN > 0) sb.append("（平均差 +${"%.2f".format(cumDivGain / cumDivN)}）")
        sb.append("，仍在影子模式，没改决策\n")
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
            }.toString())
        } catch (e: Exception) {
            AppLog.e("记忆", "记忆文件写失败", e)
        }
    }
}
