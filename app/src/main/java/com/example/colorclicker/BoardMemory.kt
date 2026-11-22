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
 *  6. 0.45：连续性闸门。本帧不是上一轮预测的延续（预测命中 < 一半：结算屏压暗 / 新一波 / 点空）时，
 *     ① 贴墙漏数不计入、不进代点（prevMiss 清空）；② 上一次点击的记消作废，不进「记消」。
 *  9. 0.49：决策前就确认漏数幽灵伙（confirmedLeakComps：预测里有怪 / 上轮审计在下一排也有），不再等选点后的审计。
 *  8. 0.47：半截帧检测 unshiftedMatch（点后第一帧伙已消、整盘还没上移），Runner 据此等上移再扫。
 *  7. 0.45b：整帧盖层检测 frameVeil（发白 / 压暗），盖层帧按不连续帧处理。
 *     另：漏数伙作为“幽灵伙”进期限余量（见 ghostComps），代点只认含 -1 排格的伙。
 *  10. 0.51：记忆学习回流（39 局数据不再只记数）。① 漏检记分跨局存盘：auditWall 逐格诊断里底色确认有怪、
 *     最佳分低于阈值的格子，按类型记漏检分桶（0.05 一档；同一档出现 ≥2 次才算数；只记 0/1 排整格，
 *     -1 排半遮怪不算——0.41 已证 -1 排怪原生分 0.336–0.591 是长相问题）；② 各类型有效阈值按漏检证据自动下调：
 *     新阈值 = max(0.56, min(基准, 最低有证档位分 − 0.02))，只降不升，下限 0.56（0.32 实测空盘噪声 ≤0.53）；
 *     ③ 整伙落空补点：总落空诊断（整伙原位仍在、一格没清）记下那伙，下轮若它还在盘上（上移一格后）且已到
 *     0 排及上，优先补点它一次，不再白白丢一轮。
 *  11. 0.58：诊断图破上限（FOLLOWUP-049）：漏数 ≥5 格的轮次破 4 张/局上限存图，普通图仍限 4 张。
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
    private var casts = 0           // 0.45 起 = 已确认的施法（点后棋盘是预测的延续）
    private var loggedKills = 0     // 0.45 起 = 已确认施法的记消
    private var tapCount = 0        // 0.45：实际发出的点击数
    private var pendingKills = 0    // 0.45：已点、还没被下一轮棋盘确认的记消
    private var pendingCasts = 0
    private var voidedCasts = 0     // 0.45：点后棋盘对不上预测，记消作废的次数 / 只数
    private var voidedKills = 0
    private var verifiedCasts = 0   // 0.45：点后核实真正覆盖的施法数（核实只在预测命中 ≥ 一半的轮次做）
    private var discMiss = 0        // 0.45：不连续帧上的漏数格·轮（不计入 wallMiss）
    private var pickRoundHit = -1   // 0.48：选点那一轮自己的预测校验命中 / 预测只数（-1 = 那轮没校验），总落空诊断用
    private var pickRoundTotal = 0
    private var tapScanMs = -1L      // 0.48：上次点击的“选点→点击”耗时 / 距上上次点击多久（-1 = 未知）
    private var tapSincePrevMs = -1L
    private var prevActual: List<Cell> = emptyList()   // 0.47：上一轮 onRound 看到的全部怪（没上移的原排位置），给 unshiftedMatch 用
    private var lastVeil = 0        // 0.45b：上一轮整帧盖层判定（0 无 / 1 发白 / 2 压暗），只用来让日志不刷屏
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

    // ───────────── 0.51 漏检记分（跨局存盘）+ 落空补点 ─────────────
    /** 每种怪的漏检分桶：key = 分数×20 的档位（如 0.66→13），value = 该档出现次数。
     *  只记底色确认有怪、最佳分低于基准阈值、且第一名比第二名高 ≥0.05 的格子；只记 0/1 排。 */
    private val leakBuckets = HashMap<String, MutableMap<Int, Int>>()
    /** 各类型按漏检证据算出的有效阈值上限（只降不升）。重建新 map 再发布，读侧无锁安全。 */
    @Volatile private var adaptiveCaps: Map<String, Double> = emptyMap()
    private var missedCells: List<Cell> = emptyList()   // 0.51：上轮整伙落空的那伙（带类型），下轮优先补点
    private var retries = 0                              // 0.51：本局落空补点次数
    private var adjPromoted = 0                          // 0.57：本局邻格补认并入伙的格数
    fun noteAdjPromoted(n: Int) { adjPromoted += n }
    private var cumRetries = 0                           // 0.51：跨局累计落空补点次数
    /** 0.51：漏检自适应下限——空盘噪声实测 ≤0.53，阈值再低会把空地认成怪。 */
    private const val ADAPT_FLOOR = 0.56

    /** 0.51：各类型基准阈值（从 Runner.effThr 搬来，同一张表）。依据：0.19–0.22 相似度累计 +
     *  每局 1–3 次“漏数嫌疑”；0.32 各降 0.02 吃分布尾巴，空盘噪声约 0.26–0.53；
     *  0.41 土豆 0.72→0.68（0/1 排漏掉的土豆原生分 0.702–0.708，-1 排 0.336–0.591 是长相问题不动）；
     *  0.42 野猪 0.8（同排土豆/蝎子对野猪模板 0.706–0.707，0.76 余量只有 0.05）。 */
    fun baseThr(name: String): Double = when {
        name.contains("蛇") -> 0.62
        name.contains("树") -> 0.64
        name.contains("野猪") -> 0.8
        name.contains("土豆") -> 0.68
        else -> 0.76
    }

    /** 0.51：某类型的有效阈值 = min(传进来的基准，自适应上限）。没证据时返回基准。 */
    fun adaptiveThr(name: String, base: Double): Double = adaptiveCaps[name]?.coerceAtMost(base) ?: base

    fun leakCount(name: String): Int = leakBuckets[name]?.values?.sum() ?: 0

    /** 0.51：记一次漏检证据（auditWall 逐格诊断调）。只收 0/1 排、最佳分低于基准、第一名比第二名高 ≥0.05。
     *  0.53：分差门从 0.10 放宽到 0.05——40 局下来桶是空的，第 39 局两格 0 排漏数（土豆 0.660、史莱姆 0.709）
     *  都因分差不到 0.10 没记进去；同一档出现 2 次才动阈值这条不变，错记要连错两次才动阈值，下限仍 0.56。
     *  同一档位第 2 次出现、且上限因此变动时打一行日志。 */
    private fun noteLeak(type: String, row: Int, best: Double, second: Double) {
        if (row < 0) return
        val base = baseThr(type)
        if (best >= base) return
        if (best - second < 0.05) return
        val buckets = leakBuckets.getOrPut(type) { HashMap() }
        val bucket = (best * 20).toInt()
        buckets[bucket] = (buckets[bucket] ?: 0) + 1
        val before = adaptiveCaps[type] ?: base
        refreshAdaptiveCaps()
        val after = adaptiveCaps[type] ?: base
        if (after < before - 0.001) {
            AppLog.i("记忆", "「${type.removePrefix("守卫-")}」累计漏检 ${leakCount(type)} 次、最低有证档 ${"%.2f".format(bucket / 20.0)}，阈值 ${"%.2f".format(before)}→${"%.2f".format(after)}（按漏检数据自动下调，只降不升，下限 $ADAPT_FLOOR）")
        }
    }

    /** 0.51：按漏检分桶重算各类型自适应上限（只降不升：已降过的取更低值，没新证据的类型保持旧上限）。 */
    private fun refreshAdaptiveCaps() {
        val next = HashMap<String, Double>()
        for ((type, buckets) in leakBuckets) {
            val bucket = buckets.entries.filter { it.value >= 2 }.minOfOrNull { it.key } ?: continue
            val cap = maxOf(ADAPT_FLOOR, minOf(baseThr(type), bucket / 20.0 - 0.02))
            val old = adaptiveCaps[type] ?: baseThr(type)
            next[type] = minOf(old, cap)
        }
        for ((type, cap) in adaptiveCaps) if (type !in next) next[type] = cap
        adaptiveCaps = next
    }

    /** 0.51：记下整伙落空的那伙（总落空诊断处调），下轮优先补点。 */
    fun noteTotalMiss(cells: List<Cell>) { missedCells = cells.toList() }

    /** 0.51：Runner 取走落空伙（取后清空，一轮有效）。 */
    fun takeMissedCells(): List<Cell> {
        val m = missedCells
        missedCells = emptyList()
        return m
    }

    fun noteRetry() { retries++ }

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
    /** 0.44：上一轮漏数格的小窗最佳分（Matcher.bestScoresAtCell 的最高分，即日志“逐格最佳分”里的第一名），
     *  由 auditWall 的逐格诊断顺手记下，供下一轮代点选落点用——决策时不再重算（bestScoresAtCell 每次调用
     *  都要整帧转 Mat + 逐模板匹配，重算会把 0–8ms 的决策拖慢几百毫秒）；分数是同一只怪上一轮的实测分
     *  （上一轮 (c,0) 的怪就是现在 (c,-1) 的怪）。没覆盖到的格（诊断只算前 6 格）查不到，取 0.0。 */
    private var prevMissBest: Map<Pair<Int, Int>, Double> = emptyMap()
    private var cumWallMiss = 0
    private var cumWallMissNew = 0
    private var cumOutMiss = 0

    fun beginBattle(ctx: Context) {
        appCtx = ctx.applicationContext
        load(ctx)
        predicted = emptyList(); predTotal = 0; predHit = 0; predNew = 0
        vanished = 0; suspect = 0; overrides = 0; verified = 0; topHit = 0; topTotal = 0; lastPicked = emptyList(); shots = 0
        casts = 0; loggedKills = 0; roundNo = 0; dumps = 0
        tapCount = 0; pendingKills = 0; pendingCasts = 0; voidedCasts = 0; voidedKills = 0; verifiedCasts = 0; discMiss = 0; lastVeil = 0; prevActual = emptyList()
        wallMiss = 0; wallMissNew = 0; outMiss = 0; prevMiss = emptySet(); prevMissBest = emptyMap()
        missedCells = emptyList(); retries = 0   // 0.51：落空伙与补点计数按局清；漏检分桶与自适应上限跨局保留
        adjPromoted = 0
        refLogged = false; assistOffLogged = false; assistOffBattle = false
    }

    /** 0.45：点击发出时只记“待确认”；下一轮 onRound（或整盘全零 onZeroBoard）确认棋盘是预测的延续才计入记消。
     *  0.44 以前在这里直接 casts++ / 加记消：结算屏上的空点、点空的点都被算成功（34 局记消 68 对结算 39）。 */
    fun noteTap(kills: Int) { tapCount++; pendingCasts++; pendingKills += kills }

    /** 0.48：Runner 在点击发出后报告这次点击的时间信息，给下一轮“总落空诊断”用。 */
    fun noteTapTiming(scanToTapMs: Long, sincePrevTapMs: Long) { tapScanMs = scanToTapMs; tapSincePrevMs = sincePrevTapMs }

    /** 0.48：点前复核取消了这次点击。onRound 已按“点了”记下 lastPicked / predicted，这里撤销，
     *  否则下一轮的预测校验、点后核实、漏数可信度（continuesPrediction）都会被一次没发生的点击污染。
     *  没有预测时 onRound 跳过校验、continues() 视为延续；noteTap 没调用过，pending 不受影响。 */
    fun cancelPick() { lastPicked = emptyList(); predicted = emptyList() }

    /** 0.45：整盘全零（这一波清完，或结算屏）：待确认的点击按已发生算。 */
    fun onZeroBoard() { settlePending(true) }

    private fun settlePending(live: Boolean) {
        if (pendingCasts == 0) return
        if (live) { casts += pendingCasts; loggedKills += pendingKills }
        else {
            voidedCasts += pendingCasts; voidedKills += pendingKills
            AppLog.d("记忆", "点后棋盘对不上预测（结算屏 / 新一波 / 没点中），上次点击记消 $pendingKills 只作废，不计入")
        }
        pendingCasts = 0; pendingKills = 0
    }

    /** 0.45：这批怪是不是上一轮预测的延续（命中 ≥ 一半，和点后核实同一个门槛）。没有预测或本帧全空视为延续。 */
    private fun continues(actual: List<Cell>): Boolean {
        if (predicted.isEmpty() || actual.isEmpty()) return true
        val gone = predicted.count { pc -> actual.none { it.type == pc.type && it.col == pc.col && it.row == pc.row } }
        return (predicted.size - gone) * 2 >= predicted.size
    }

    // ───────────── 0.47 半截帧检测：点后第一帧“伙已消、整盘还没上移” ─────────────
    // 35 局：15 次作废的帧里，命中+新刷 = 预测只数（±2）的有 11 帧、命中 ≈ 0（如 12 轮 0/12 新刷 12，21 轮 4/25 新刷 21）；
    // 通常帧的新刷 ≈ 10 只（新怪随整盘上移一起刷出）。说明这些帧是“消除动画已播完、上移还没开始”：位置整齐（过得了滑动闸门）、
    // 没有新怪，但每只怪都还在原排。bot 在这帧上选点，点击落下时盘面刚好上移，落点换成了下面一排的怪（或空地）——
    // 下一轮“点后核实”里 surv=整伙、额外消失 1~2 格，就是点到了邻居；作废的那次点击其实是真的清掉了。
    /** 返回 null = 不是半截帧；否则 (原排吻合格数, 期望只数, 上移后吻合格数)。要求：刚点过、原排吻合 ≥60%、
     *  明显比上移后吻合多（>2 倍）、且总只数没比期望多出 2 只以上（有新怪 = 已经上移过了）。 */
    fun unshiftedMatch(comps: List<Pair<String, Matcher.Comp>>): Triple<Int, Int, Int>? {
        if (pendingCasts == 0 || lastPicked.isEmpty() || prevActual.isEmpty()) return null
        val picked = lastPicked.toSet()
        val expect = prevActual.filter { it !in picked }
        if (expect.size < 3) return null
        val actual = comps.flatMap { (name, c) -> c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) } }
        if (actual.isEmpty() || actual.size > expect.size + 2) return null
        val same = expect.count { e -> actual.any { it.type == e.type && it.col == e.col && it.row == e.row } }
        val shifted = predicted.count { p -> actual.any { it.type == p.type && it.col == p.col && it.row == p.row } }
        return if (same * 10 >= expect.size * 6 && same > shifted * 2) Triple(same, expect.size, shifted) else null
    }

    // ───────────── 0.45b 整帧盖层检测（结算屏淡入淡出）─────────────
    // 04 号像素实测：last2 发白（色度 ≈ 灰 0.342/0.336/0.322），last1 压暗（色度仍 ≈ 空地 0.432/0.361/0.207，亮度只有空地 229 的约 1/6）。
    // 两种都是盖层：发白时空地色度离参考 0.16、压暗时判据因亮度低而不可靠，都会冒出假漏数。
    // 注意方向：压暗（亮度↓、色度不变）和注释里 0.38 起的认识一致；发白（亮度↑、色度→灰）是另一个相位，两种都要拦。
    private const val SAND_BRIGHT = 229
    private const val GREY_DIST = 0.05
    private val GREY = doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3)

    /** 0 = 正常；1 = 发白（≥80% 的格角色度贴近灰色）；2 = 压暗（空地色度的角里 ≥80% 亮度低于 229 的一半，且这样的角 ≥8 个）。
     *  只取第 0~6 排每格下面两个角（84 个 7×7 块，约 1~2ms），不碰模板。取不全/出错返回 0（不误拦）。 */
    fun frameVeil(frame: Bitmap?): Int {
        if (frame == null || frame.isRecycled) return 0
        return try {
            var total = 0; var grey = 0; var sandLike = 0; var dark = 0
            for (r in 0..6) for (c in 0..5) {
                val cx = COL0_X + c * STEP; val cy = ROW0_Y + r * STEP
                for (dx in listOf(-CORNER, CORNER)) {
                    val px = patch(frame, cx + dx, cy + CORNER) ?: continue
                    total++
                    val ch = px.chroma()
                    if (cdist(ch, GREY) < GREY_DIST) grey++
                    else if (cdist(ch, refChroma) <= NOT_SAND) { sandLike++; if (px.bright * 2 < SAND_BRIGHT) dark++ }
                }
            }
            when {
                total < 40 -> 0
                grey * 10 >= total * 8 -> 1
                sandLike >= 8 && dark * 10 >= sandLike * 8 -> 2
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    fun frameVeiled(frame: Bitmap?): Boolean = frameVeil(frame) != 0

    /** Runner 选点前问：当前识别结果是不是上一轮预测的延续（不是就别信漏数、别代点）。 */
    fun continuesPrediction(comps: List<Pair<String, Matcher.Comp>>): Boolean =
        continues(comps.flatMap { (name, c) -> c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) } })
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
        // 0.45：本帧是不是上一轮预测的延续；先结算上次点击的记消，再做贴墙漏数
        val veil = frameVeil(frame)
        if (veil != 0 && veil != lastVeil) {
            AppLog.w("记忆", "整帧被盖层盖住（" + (if (veil == 1) "发白：多数格角色度趋近灰色" else "压暗：空地色度没变但亮度不到正常的一半") + "），按不连续帧处理：不计漏数、不代点、点后记消作废")
        }
        lastVeil = veil
        val live = veil == 0 && continues(actual)
        settlePending(live)
        // 0. 0.38 贴墙漏数日志：只读这一帧已抓到的像素，不改任何决策（0.45：不连续帧不计入、不进代点）
        if (frame != null) {
            val nearCells = near.map { (_, n) -> Pair(colOf(n.x), rowOfY(n.y)) }.toSet()
            auditWall(frame, actual.map { Pair(it.col, it.row) }.toSet(), nearCells, tpls, live)
        }
        var gone: List<Cell> = emptyList()
        var hitNow = -1; var totalNow = 0
        // 1. 校验上一轮的预测
        if (predicted.isNotEmpty() && actual.isNotEmpty()) {
            gone = predicted.filter { pc -> actual.none { it.type == pc.type && it.col == pc.col && it.row == pc.row } }
            val hit = predicted.size - gone.size
            hitNow = hit; totalNow = predicted.size
            val fresh = actual.count { a -> predicted.none { it.type == a.type && it.col == a.col && it.row == a.row } }
            predHit += hit; predTotal += predicted.size; predNew += fresh; vanished += gone.size
            val top = predicted.filter { it.row <= 4 }
            topTotal += top.size; topHit += top.count { it !in gone }
            // 0.23 点后核实：被点那伙的格子上移一格后若原位还认得出同类怪 = 没清干净/没点中；
            // 实际消失格数 = 被点那伙格数 − 原位仍在 + 预测有而实测无。预测命中太低（棋盘没按预期动）时作废。
            if (lastPicked.isNotEmpty() && hit * 2 >= predicted.size) {
                val surv = lastPicked.count { p -> actual.any { it.type == p.type && it.col == p.col && it.row == p.row - 1 } }
                val kv = lastPicked.size - surv + gone.size
                verified += kv; verifiedCasts++
                if (surv > 0 || kv != lastPicked.size) AppLog.w("记忆", "点后核实：记消 ${lastPicked.size} 只，核实消失 $kv 格（原位仍在 $surv 格，额外消失 ${gone.size} 格）")
                // 0.48 总落空诊断（整伙原位仍在、一格没清）：区分“选点帧本身就是半截帧（0.47 闸门漏判）”和“选点帧已上移完、点击落下时才开始动”。
                // 选点那轮预测命中 ≈ 0（新刷 ≈ 预测只数）= 前者；接近满 = 后者。再看选点→点击耗时、距上次点击多久。
                if (surv == lastPicked.size && gone.isEmpty()) {
                    val pr = if (pickRoundHit < 0) "没校验" else "$pickRoundHit/$pickRoundTotal"
                    noteTotalMiss(lastPicked)   // 0.51：整伙落空，记下这伙，下轮优先补点
                    AppLog.w("记忆", "总落空诊断：选点那轮预测命中 $pr（≈0=选点帧是半截帧、闸门漏判；接近满=选点帧已上移完），选点→点击 ${if (tapScanMs < 0) "—" else "${tapScanMs}ms"}，距上次点击 ${if (tapSincePrevMs < 0) "—" else "${tapSincePrevMs}ms"}；已记下这伙，下轮优先补点")
                }
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
        if (gone.size >= 2 || sus > 0) dump(frame, if (sus > 0) "近阈值漏数嫌疑" else "预测消失 ${gone.size} 格", gone.size >= 5 || sus >= 5)
        // 3. 相似度记忆
        for ((name, c) in comps) {
            val s = typeStats.getOrPut(name) { TypeStat() }
            s.n++; s.sum += c.score; if (c.score < s.min) s.min = c.score; if (c.score > s.max) s.max = c.score
        }
        prevActual = actual
        pickRoundHit = hitNow; pickRoundTotal = totalNow
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
    fun simulateNext(actual: List<Cell>, tapped: Matcher.Comp, tappedName: String, ghosts: List<Cell> = emptyList()): Pair<Boolean, Double> {
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val rest = actual.filter { it !in tappedCells }.map { Cell(it.type, it.col, it.row - 1) }
        if (rest.any { it.row < -1 }) return true to 0.0
        // 0.45：漏数幽灵格（模板没认出但底色有怪）：任何一次施法都会让它们上移，已在 -1 排的会越线
        if (ghosts.any { it.row - 1 < -1 }) return true to 0.0
        val groups = groupCells(rest)
        if (groups.isEmpty()) return false to 0.0
        return false to groups.maxOf { it.size }.toDouble()
    }

    /** 0.43 本地最优解用：模拟点掉 tapped 一伙后下一轮的情况。
     *  返回（是否有怪越线，下轮最大一伙只数，下轮 row<=0 的格数）。复用 simulateNext，不改它的语义。 */
    fun simulateNextStats(all: List<Pair<String, Matcher.Comp>>, tapped: Matcher.Comp, tappedName: String,
                          ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Triple<Boolean, Int, Int> {
        val actual = all.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
        }
        val ghostCells = ghosts.flatMap { (name, c) -> c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) } }
        val (ends, next) = simulateNext(actual, tapped, tappedName, ghostCells)
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val threat = actual.filter { it !in tappedCells }.count { it.row - 1 <= 0 }
        return Triple(ends, next.toInt(), threat)
    }

    /** 0.54 合群：点掉 tapped 后，其余上移一格，棋盘上“一个缺口能连起两伙同种怪”的最大可合群数。
     *  缺口 = 空格子，上下左右邻着 ≥2 个同种怪的伙（可以是被点掉那伙腾出来的格子——清掉隔在两伙中间
     *  的怪，下轮一个对的怪刷进缺口就把两伙连成一伙）。返回最大的“两伙只数之和 + 1”（+1 是刷进来的那只）；
     *  没有这种缺口返回 0。只算“能连起两伙”的缺口：单个伙旁边的空格顶多让它多 1 只，那部分两步值里
     *  已经有了，不另算。点完即越线时返回 0。新刷约 10 只/轮、8 种怪，一个缺口每轮大多会被别的怪先占掉，
     *  所以 Solver 里合群只按 1/3 折算进三步值，不按满值算。
     *  0.55：只算两边的伙点完后 minRow ≥ 1 的缺口（至少还能活两轮，等得到对的怪）；墙边的桥（42 局
     *  合群 12 的桥两轮就没了）不再算分。 */
    fun clusterPotential(all: List<Pair<String, Matcher.Comp>>, tapped: Matcher.Comp, tappedName: String): Int {
        val actual = all.flatMap { (name, c) ->
            c.cells.ifEmpty { listOf(c.point) }.map { Cell(name, colOf(it.x), rowOfY(it.y)) }
        }
        val tappedCells = tapped.cells.ifEmpty { listOf(tapped.point) }
            .map { Cell(tappedName, colOf(it.x), rowOfY(it.y)) }.toSet()
        val rest = actual.filter { it !in tappedCells }.map { Cell(it.type, it.col, it.row - 1) }
        if (rest.any { it.row < -1 }) return 0
        val groups = groupCells(rest)
        if (groups.size < 2) return 0
        // 0.55：桥两边的伙都得点完后 minRow ≥ 1（至少还能活两轮）才算——墙边的桥等不到对的怪
        // （42 局合群 12 的桥两轮就没了）；minRow 是伙里最靠墙那只的排，-1 = 下轮就撞墙。
        val groupMinRow = groups.map { g -> g.minOf { it.row } }
        val occupied = rest.map { Pair(it.col, it.row) }.toSet()
        val owner = HashMap<Pair<Int, Int>, Int>()
        groups.forEachIndexed { gi, g -> g.forEach { owner[Pair(it.col, it.row)] = gi } }
        var best = 0
        for (col in 0..5) for (row in -1..6) {
            if (Pair(col, row) in occupied) continue
            val byType = HashMap<String, MutableSet<Int>>()
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val gi = owner[Pair(col + dc, row + dr)] ?: continue
                byType.getOrPut(groups[gi].first().type) { HashSet() }.add(gi)
            }
            for ((_, gis) in byType) {
                val live = gis.filter { groupMinRow[it] >= 1 }
                if (live.size >= 2) {
                    val pot = live.sumOf { groups[it].size } + 1
                    if (pot > best) best = pot
                }
            }
        }
        return best
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

    /** 0.57 邻格补认用：第 col 列第 row 排这格的底色色度（取样、一致性判据与 monsterBg 完全相同；
     *  格子没染色 / 取不全 / 角之间不一致返回 null，即 cellTint(...) != null ⇔ monsterBg(...) == true）。 */
    fun cellTint(f: Bitmap, col: Int, row: Int): DoubleArray? {
        if (f.isRecycled) return null
        try {
            val cx = COL0_X + col * STEP
            val cy = ROW0_Y + row * STEP
            val offs = if (row == -1) listOf(-CORNER to CORNER, CORNER to CORNER)
                else listOf(-CORNER to -CORNER, CORNER to -CORNER, -CORNER to CORNER, CORNER to CORNER)
            val need = if (row == -1) 2 else 3
            val ch = offs.mapNotNull { (dx, dy) -> patch(f, cx + dx, cy + dy) }.map { it.chroma() }
            if (ch.size < need) return null
            val non = ch.indices.filter { cdist(ch[it], refChroma) > NOT_SAND }
            if (non.size < need) return null
            val ok = non.filter { i -> non.all { j -> cdist(ch[i], ch[j]) < SAME_CHROMA } }
            if (ok.size < need) return null
            return DoubleArray(3) { k -> ok.map { ch[it][k] }.sorted()[ok.size / 2] }
        } catch (e: Exception) {
            return null
        }
    }

    /** 0.57：两个色度的欧氏距离（和 monsterBg 同一口径）。 */
    fun tintDist(a: DoubleArray, b: DoubleArray): Double = cdist(a, b)

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

    /** 0.48：判“颜色辅助没开”至少要抽到这么多个已检出的格子。36 局 13:50:46 盘上只剩 2 只，抽 2 个 0 个有染就判“未开”并锁了整局，
     *  可后面贴墙漏数照常出了（辅助其实开着）：2 个格子证明不了什么。不够数 = 不下结论，照常往下做漏数检测（和抽到 0/1 个时一样）。 */
    private const val ASSIST_MIN_SAMPLE = 6

    /** 每轮一次：数第 -1/0/1 排里“底色有怪、模板却没检出”的格子（共 18 格），再看第 -2 排有没有怪底色。列号 1~6 从左到右，排号同棋盘。 */
    private fun auditWall(frame: Bitmap, detected: Set<Pair<Int, Int>>, nearCells: Set<Pair<Int, Int>>, tpls: List<Pair<String, Matcher.Tpl>> = emptyList(), live: Boolean = true) {
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
            if (sampled >= ASSIST_MIN_SAMPLE && tinted * 4 < sampled) {
                assistOffBattle = true
                if (!assistOffLogged) {
                    assistOffLogged = true
                    AppLog.w("记忆", "颜色辅助未开（抽查 $sampled 个检出的格子，只有 $tinted 个底色有染），贴墙漏数检测跳过")
                }
                return
            }
            // 0.48：够数的一轮抽查通过 = 辅助是开的；之前若因（够数的）抽查判过“未开”，这里改判回来，别让整局锁死在“未开”
            if (sampled >= ASSIST_MIN_SAMPLE && assistOffBattle) {
                assistOffBattle = false; assistOffLogged = false
                AppLog.i("记忆", "颜色辅助抽查恢复正常（抽查 $sampled 个，$tinted 个底色有染），改判为已开，继续做贴墙漏数检测")
            }
            // 0.50：多看一排（第 2 排）只进 prevMiss、不计入贴墙漏数：上一轮在 2 排没被认出的怪，这一轮上移到 1 排，
            // 否则 confirmedLeakComps 的②号旁证（查 (c, r+1) 在不在 prevMiss）对 1 排幽灵伙永远查不到——prevMiss 里根本没有 2 排。
            // 38 局 23:52:44 第 5 列第 1 排那格就是这么漏的：预测里没有它（上一轮在 2 排就没认出），②号旁证又够不着 2 排。
            val miss = ArrayList<Pair<Int, Int>>()
            val miss2 = ArrayList<Pair<Int, Int>>()
            for (r in -1..2) for (c in 0..5) {
                if (Pair(c, r) in detected) continue
                if (monsterBg(frame, c, r) == true) {
                    if (r <= 1) miss.add(Pair(c, r)) else miss2.add(Pair(c, r))
                }
            }
            if (!live) {
                // 0.45：不连续帧（结算屏压暗 / 新一波 / 点空）上底色判据不可靠：不计入 wallMiss、清空 prevMiss（不进代点）。
                // 34 局：24 格·轮 = 12:06:37/38/41/43 四帧（5+5+9+5），此前每轮都是 0。
                prevMiss = emptySet(); prevMissBest = emptyMap()
                discMiss += miss.size
                if (miss.isNotEmpty()) AppLog.w("记忆", "贴墙漏数（不连续帧，不计入、不代点）：${miss.size} 格——这一帧不是上一轮预测的延续，底色判据在这种帧上不可靠")
                else AppLog.d("记忆", "贴墙漏数：0 格（不连续帧）")
                val outD = (0..5).filter { beyondWallBg(frame, it) }
                if (outD.isNotEmpty()) AppLog.w("记忆", "贴墙外漏数（不连续帧，不计入）：第 -2 排有 ${outD.size} 格怪底色（${colTxt(outD)}）")
                return
            }
            // 新出现：上一轮同一只若在下一排（施法后上移一格），位置是 (c, r+1)，已在上一轮漏数里就不再算新的
            val newCnt = miss.count { (c, r) -> Pair(c, r + 1) !in prevMiss }
            prevMiss = (miss + miss2).toSet()
            wallMiss += miss.size; wallMissNew += newCnt
            if (miss.isEmpty()) {
                prevMissBest = emptyMap()
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
                // 0.44：把每格的最佳分（第一名）存下来，下一轮代点选落点用（见 missBestScore）。
                prevMissBest = diag.mapValues { (_, top) -> top.maxOfOrNull { it.second } ?: 0.0 }
                // 0.51：漏检记分——逐格诊断的第一名就是“底色确认有怪、模板没检出”的实测分，跨局攒起来回流阈值。
                // 只记 0/1 排（-1 排半遮怪的低分是长相问题，0.41 已证）；第一名须比第二名高 ≥0.05 才认类型（0.53 从 0.10 放宽）。
                for ((cell, top) in diag) {
                    if (cell.second < 0 || top.isEmpty()) continue
                    noteLeak(top[0].first, cell.second, top[0].second, top.getOrNull(1)?.second ?: 0.0)
                }
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

    /** 0.44 贴墙漏数代点（按伙）：上一轮漏数映射到当前排——第 0 排→-1 排（救命级）、
     *  第 1 排→0 排（次级）、第 -1 排→-2 排（已死，丢弃），去掉本轮模板已检出的格子，
     *  再按上下左右相邻成伙（不按模板类型分伙——点下去游戏清的是该格真实怪物的同种连通伙；
     *  墙区怪 bestScores 只有 0.38–0.47 且常平手，拿它分类反而误事）。
     *  返回按“伙大→-1 格多→minRow 小→最左”排好序的伙。
     *  注意时序：Runner 在选点时调它，那时 prevMiss 还是上一轮的（本轮 auditWall 在 onRound 里才跑），
     *  所以第 0 排漏数正好对应现在的 -1 排；上一轮的 -1 排漏数现在已到 -2 排，撞了，没得点。 */
    fun wallMissGroups(detected: Set<Pair<Int, Int>>): List<List<Pair<Int, Int>>> {
        val mapped = prevMiss.mapNotNull { (c, r) ->
            when (r) {
                0 -> Pair(c, -1)
                1 -> Pair(c, 0)
                else -> null
            }
        }.filter { it !in detected }
        if (mapped.isEmpty()) return emptyList()
        val left = mapped.toMutableList()
        val groups = ArrayList<List<Pair<Int, Int>>>()
        while (left.isNotEmpty()) {
            val seed = left.removeAt(0)
            val group = ArrayList<Pair<Int, Int>>()
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.add(seed); group.add(seed)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val it = left.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    if (abs(o.first - cur.first) + abs(o.second - cur.second) == 1) {
                        it.remove(); stack.add(o); group.add(o)
                    }
                }
            }
            groups.add(group)
        }
        return groups.sortedWith(
            compareByDescending<List<Pair<Int, Int>>> { it.size }
                .thenByDescending { g -> g.count { it.second == -1 } }
                .thenBy { g -> g.minOf { it.second } }
                .thenBy { g -> g.minOf { it.first } }
        )
    }

    /** 0.49 确认过的漏数幽灵伙：决策这一帧里底色有怪、模板没检出的格子，且有旁证它真是怪：
     *  ① 上一轮预测里这格有怪（上一帧检出过，被点的伙以外没理由消失；类型取预测的）；
     *  ② 上一轮审计在下一排（c, r+1）也有漏数格（它随盘上移到这里）。
     *  旧口径（wallMissGroups）只用上一轮审计映射，且审计在选点之后才跑：36 局 13:51:04 那轮 9 格漏数全是本帧新出现，
     *  选点时一格都看不到，余量算成 2，实际 -1 排已有 3 只没认出的怪，点谁都越线，却改点了 1 只的史莱姆而不是 7 只的土豆怪。
     *  没有旁证的漏数格仍只记日志（审计照常），不进决策。返回按类型连片后的伙（类型名「漏数-<预测类型>」/「漏数-?」）。 */
    fun confirmedLeakComps(frame: Bitmap?, detected: Set<Pair<Int, Int>>): List<Pair<String, Matcher.Comp>> {
        if (frame == null || frame.isRecycled || assistOffBattle) return emptyList()
        return try {
            val cells = ArrayList<Cell>()
            for (r in -1..1) for (c in 0..5) {
                if (Pair(c, r) in detected) continue
                if (monsterBg(frame, c, r) != true) continue
                val pred = predicted.firstOrNull { it.col == c && it.row == r }
                if (pred != null) cells.add(Cell("漏数-" + pred.type, c, r))
                else if (Pair(c, r + 1) in prevMiss) cells.add(Cell("漏数-?", c, r))
            }
            groupCells(cells).map { g ->
                val pts = g.map { cellCenter(it.col, it.row) }
                g.first().type to Matcher.Comp(pts.minByOrNull { it.y }!!, g.size, 0.0, ys = pts.map { it.y }, cells = pts)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 0.45 代点只认“救命级”伙：含 -1 排格的伙。只在 0 排的漏数伙还能再等一施法，不值得抢这一次施法。 */
    fun wallMissFallbackGroups(detected: Set<Pair<Int, Int>>): List<List<Pair<Int, Int>>> =
        wallMissGroups(detected).filter { g -> g.any { it.second == -1 } }

    /** 0.45 幽灵伙：把漏数伙变成只参与“期限余量/越线模拟”的伙（不是候选，不会被点）。
     *  名字用「贴墙漏数」，和真怪类型不同，不会和真怪连片。 */
    fun ghostComps(groups: List<List<Pair<Int, Int>>>): List<Pair<String, Matcher.Comp>> =
        groups.map { g ->
            val pts = g.map { cellCenter(it.first, it.second) }
            "贴墙漏数" to Matcher.Comp(pts.minByOrNull { it.y }!!, g.size, 0.0, ys = pts.map { it.y }, cells = pts)
        }

    /** 0.44：当前排 (col,row) 的漏数格在上一轮的小窗最佳分（见 prevMissBest）。
     *  当前 -1 排的怪上一轮在 0 排、当前 0 排的上一轮在 1 排；其余排没有漏数映射，取 0.0。 */
    fun missBestScore(col: Int, row: Int): Double {
        val prev = when (row) {
            -1 -> Pair(col, 0)
            0 -> Pair(col, 1)
            else -> return 0.0
        }
        return prevMissBest[prev] ?: 0.0
    }

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

    // 0.58：漏数 ≥5 格的诊断图破上限存（FOLLOWUP-049：上限 4 会把真正要看的高漏轮次拦掉；普通图仍限 4 张/局）。
    private fun dump(frame: Bitmap?, why: String, bypassCap: Boolean = false) {
        val ctx = appCtx ?: return
        if (frame == null || (!bypassCap && dumps >= MAX_DUMPS)) return
        dumps++
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, "gap_${battles + 1}_$roundNo.jpg")
            FileOutputStream(f).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            AppLog.i("记忆", "已存诊断截图 ${f.absolutePath}（$why${if (bypassCap) "，漏数≥5破上限" else ""}）")
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
        if (tapCount == 0 && predTotal == 0) {
            predicted = emptyList()
            return
        }
        settlePending(true)   // 0.45：最后一次点击没等到下一轮确认（战斗结束）：按已发生算
        battles++
        cumPredHit += predHit; cumPredTotal += predTotal; cumVanished += vanished; cumSuspect += suspect
        cumVerified += verified; cumTopHit += topHit; cumTopTotal += topTotal
        cumOverrides += overrides
        cumRetries += retries
        cumCasts += casts; cumKills += loggedKills
        cumWallMiss += wallMiss; cumWallMissNew += wallMissNew; cumOutMiss += outMiss
        save(ctx)
        val pred = if (predTotal > 0) "$predHit/$predTotal" else "—"
        AppLog.i("记忆", "本局记忆：施法 $casts 次，记消 $loggedKills 只（${"%.2f".format(if (casts > 0) loggedKills.toDouble() / casts else 0.0)} 只/次；点击共 $tapCount 次，棋盘对不上而作废 $voidedCasts 次/$voidedKills 只）；" +
            "预测命中 $pred 格，新刷 $predNew 只；预测有而实测无 $vanished 格；近阈值漏数嫌疑 $suspect 格；邻格补认 $adjPromoted 格；" +
            wallMissText() + "期限优先改点 $overrides 次；落空补点 $retries 次")
        AppLog.i("记忆", "核实对账：记消 $loggedKills 只；点后核实清掉 $verified 只（只覆盖 $verifiedCasts/$casts 次施法——预测命中不到一半的轮次不核实，所以不能和记消直接相减）；" +
            "上部(排≤4)预测命中 ${if (topTotal > 0) "$topHit/$topTotal" else "—"}；漏数嫌疑 $suspect 格；" + wallMissText().trimEnd('；'))
        val rate = if (cumPredTotal > 0) "%.1f%%".format(100.0 * cumPredHit / cumPredTotal) else "—"
        AppLog.i("记忆", "累计 $battles 局：预测命中率 $rate（上部 ${if (cumTopTotal > 0) "%.1f%%".format(100.0 * cumTopHit / cumTopTotal) else "—"}），期限优先改点 $cumOverrides 次，累计施法 $cumCasts 次 / 记消 $cumKills 只")
        AppLog.i("记忆", "贴墙漏数累计：$cumWallMiss 格·轮（约 $cumWallMissNew 只），贴墙外 $cumOutMiss 格（含本局）")
        val sim = typeStats.entries.sortedBy { it.key }
            .joinToString(" ") { "${it.key.removePrefix("守卫-")}出现${it.value.n}次均${"%.2f".format(it.value.avg)}最低${"%.2f".format(it.value.min)}" }
        AppLog.i("记忆", "相似度累计：$sim")
        for ((name, s) in typeStats) {
            if (s.min < 0.68) {
                val cap = adaptiveCaps[name]
                if (cap != null) AppLog.i("记忆", "「${name.removePrefix("守卫-")}」相似度最低到 ${"%.3f".format(s.min)}（均值 ${"%.2f".format(s.avg)}），阈值已按累计漏检自动调到 ${"%.2f".format(cap)}")
                else AppLog.w("记忆", "「${name.removePrefix("守卫-")}」相似度最低到 ${"%.3f".format(s.min)}（均值 ${"%.2f".format(s.avg)}），阈值可能要下调，下局留意")
            }
        }
        predicted = emptyList()
    }

    /** 本局贴墙漏数的一小段文字，供「本局记忆 / 核实对账」两行并排放在漏数嫌疑旁边 */
    private fun wallMissText(): String =
        if (assistOffBattle) "贴墙漏数 —（本局颜色辅助未开，未检测）；"
        else "贴墙漏数 $wallMiss 格·轮（约 $wallMissNew 只）（不连续帧另有 $discMiss 格·轮，不计入）；贴墙外漏数 $outMiss 格；"

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
        if (cumRetries > 0) sb.append("落空补点 $cumRetries 次\n")
        val leaks = leakBuckets.entries.sortedBy { it.key }
            .joinToString("\n") { (k, _) ->
                val n = leakCount(k); val cap = adaptiveThr(k, baseThr(k)); val base = baseThr(k)
                "「${k.removePrefix("守卫-")}」漏检 $n 次" + if (cap < base - 0.001) "，阈值 ${"%.2f".format(base)}→${"%.2f".format(cap)}" else "，阈值未动（证据不足：同一档需出现 2 次）"
            }
        if (leaks.isNotEmpty()) sb.append("漏检回补（按漏检数据自动下调阈值，只降不升，下限 $ADAPT_FLOOR）：\n$leaks\n")
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
            cumRetries = j.optInt("cumRetries")
            val leaks = j.optJSONObject("leaks")
            if (leaks != null) {
                for (k in leaks.keys()) {
                    val o = leaks.getJSONObject(k)
                    val buckets = HashMap<Int, Int>()
                    val bj = o.optJSONObject("buckets")
                    if (bj != null) for (bk in bj.keys()) {
                        val bi = bk.toIntOrNull() ?: continue
                        buckets[bi] = bj.optInt(bk)
                    }
                    leakBuckets[k] = buckets
                }
                refreshAdaptiveCaps()
            }
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
            val leaks = JSONObject()
            for ((k, buckets) in leakBuckets) {
                val bj = JSONObject()
                for ((b, n) in buckets) bj.put(b.toString(), n)
                leaks.put(k, JSONObject().apply { put("buckets", bj) })
            }
            memFile(ctx).writeText(JSONObject().apply {
                put("battles", battles); put("types", types); put("leaks", leaks)
                put("cumPredHit", cumPredHit); put("cumPredTotal", cumPredTotal)
                put("cumVanished", cumVanished); put("cumSuspect", cumSuspect)
                put("cumVerified", cumVerified); put("cumTopHit", cumTopHit); put("cumTopTotal", cumTopTotal)
                put("cumOverrides", cumOverrides); put("cumDivN", cumDivN); put("cumDivGain", cumDivGain)
                put("cumCasts", cumCasts); put("cumKills", cumKills); put("cumRetries", cumRetries)
                put("cumWallMiss", cumWallMiss); put("cumWallMissNew", cumWallMissNew); put("cumOutMiss", cumOutMiss)
            }.toString())
        } catch (e: Exception) {
            AppLog.e("记忆", "记忆文件写失败", e)
        }
    }
}
