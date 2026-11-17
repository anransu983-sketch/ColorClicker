package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import kotlin.random.Random

object Runner {
    private var job: Job? = null
    private val bmpCache = HashMap<String, Bitmap?>()            // 旧路径 / 非扎堆规则用
    private val tplCache = HashMap<String, Matcher.Tpl?>()       // 快路径用：RGB 全/半分辨率模板，常驻
    /** 连续多久（毫秒）一次都没点出去就自动停止。0.17 最长的“波次重刷”空档约 2 轮×6 秒=13 秒；轮次变快后按时间算，不按轮数 */
    private const val AUTO_STOP_IDLE_MS = 25_000L

    /** 期限余量封顶：点完后余量 ≥ 此值（次施法）的候选视为一样安全，再比只数。调大更保守，调小更贪 */
    private const val HZ_CAP = BoardMemory.HZ_CAP

    /** 点后画面稳定等待：每隔 GAP 毫秒再截一帧比对，变化像素占比 < FRAC 视为稳定；最多等 TRIES 次 */
    private const val SETTLE_GAP_MS = 110L
    private const val SETTLE_TRIES = 8
    private const val SETTLE_FRAC = 0.02

    /** 0.23 有效阈值 = min(规则阈值, 下面的数)，按规则名含的字匹配（她不用调）。
     *  依据：0.19–0.22 相似度累计（蛇均0.93最低0.71、树均0.91最低0.77、土豆均0.95最低0.78、蝎子最低0.81）
     *  + 每局 1–3 次“漏数嫌疑”（点掉那伙旁边的同类怪只比阈值低一点点被漏数）。放低必须配合跨类去重（见 resolveConflicts），否则会认错怪。 */
    // 0.32：14 局累计最低相似度贴着 cap 走（蛇 0.645/cap 0.64、树 0.672/cap 0.66），
    // 各降 0.02 吃掉分布尾巴；空盘噪声约 0.26–0.53，离 0.62 还远
    private fun effThr(r: Rule): Double {
        val cap = when {
            r.name.contains("蛇") -> 0.62
            r.name.contains("树") -> 0.64
            r.name.contains("土豆") -> 0.72
            else -> 0.76
        }
        return minOf(r.threshold, cap)
    }

    // ───────── 0.23 滑动帧闸门 ─────────
    /** 怪的 y 应落在 1511+240k 的整格上（模板中心与格中心有固定小偏差，如土豆约 +10，按规则学一个偏差）。偏差超过容差 = 画面在滑动，不扫、重截 */
    private const val LATTICE_TOL = 22
    private const val LATTICE_TOL_INIT = 40
    private const val SLIDE_MAX_RETRY = 8
    private const val SLIDE_RETRY_MS = 60L
    /** 只检查 y ≤ 此值的格；最底下刚刷进来的怪是半截入画，位置不准，不拿来判滑动 */
    private const val GATE_MAX_Y = 2800
    private val latticeBias = HashMap<String, ArrayList<Int>>()
    private var slideRetries = 0
    @Volatile private var slidingFlag = false

    // ───────── 0.23 复盘（死前回放）─────────
    private val recentRounds = java.util.ArrayDeque<String>()
    private val recentShots = ArrayList<Bitmap>()
    private var zeroShotDone = false
    private var zeroShots = 0

    fun isRunning() = job?.isActive == true

    /** 扎堆比数量的结果：规则、点、该规则的相似度、能消只数 */
    private data class ClusterPick(val rule: Rule, val point: Point, val score: Double, val count: Int, val aiNote: String? = null)

    /** 棋盘第 0 排（贴墙）中心 y 与行距。第 0 排的怪下一次施法后到第 -1 排（y1271）仍可点，再下一次才撞墙 */
    private const val ROW0_Y = 1511
    private const val ROW_STEP = 240
    private fun rowOf(y: Int) = Math.round((y - ROW0_Y).toDouble() / ROW_STEP).toInt()   // 可为 -1
    private fun colOf(x: Int) = ((x - 117 + 120) / 240).coerceIn(0, 5)   // 0.39：和 BoardMemory.colOf 一个算法（第一列中心 117，格宽 240）

    /** 0.39 贴墙漏数代点用的假规则：不是模板识别出来的怪，只是个落点。delayMs 跟扎堆规则一样 600ms；threshold 0 只是让日志照实写“相似度 0.000”（本来就没模板分）。 */
    private val WALL_FALLBACK_RULE = Rule(id = "wall_fallback", name = "贴墙漏数", type = "image", threshold = 0.0, delayMs = 600, clusterFirst = true)

    /** 点击没效果的检测：连续几轮棋盘签名一字不差就停 */
    private var lastSig = ""
    private var sameRounds = 0
    @Volatile private var noEffectStop = false
    @Volatile private var lastTapAt = 0L

    /** 0.22 防重复点：上次扎堆点击的目标与点之前那盘的签名。棋盘没变又选中同一目标 = 读到的是点之前的旧帧，跳过不点 */
    private var lastTapRuleId: String? = null
    private var lastTapPoint: Point? = null
    private var lastTapBoardSig: String = ""
    private var pendingBoardSig: String = ""
    private var repeatSkips = 0
    /** 0.22 settle 用：点之前那帧的画面签名（72x120 下采样）。等到画面相对它真变了才走 */
    private var lastTapFrameSig: IntArray? = null

    /** 一键守卫交棒用：已在跑返回 true；没跑起来返回 false（调用方记日志）。 */
    fun startBattle(ctx: Context): Boolean {
        if (isRunning()) return true
        if (ClickService.instance == null) {
            AppLog.e("点击", "启动失败：无障碍服务未连接，请先开启无障碍服务")
            return false
        }
        if (ScreenGrabber.reader == null) {
            AppLog.e("点击", "启动失败：还没截到画面（可能未授权截屏或服务未启动）")
            return false
        }
        start(ctx.applicationContext, RuleStore.getLoopLimit(ctx)); return true
    }

    /** 返回切换后是否在运行 */
    fun toggle(ctx: Context): Boolean {
        if (isRunning()) { stop(); return false }
        if (ClickService.instance == null) {
            AppLog.e("点击", "启动失败：无障碍服务未连接，请先开启无障碍服务")
            Toast.makeText(ctx, "请先开启无障碍服务", Toast.LENGTH_SHORT).show(); return false
        }
        val svc = ctx as? CaptureService
        if (svc != null && svc.ensureCapture() == 0) {
            Toast.makeText(ctx, "建画面失败或方向变了，请先停止再重新授权截屏", Toast.LENGTH_LONG).show()
            return false
        }
        start(ctx.applicationContext, RuleStore.getLoopLimit(ctx)); return true
    }

    fun stop() {
        if (isRunning()) AppLog.i("服务", "运行已停止")
        job?.cancel(); job = null
    }

    private fun cacheKey(path: String) = path + "#" + File(path).lastModified()

    private fun getBmp(r: Rule): Bitmap? = synchronized(bmpCache) {
        val key = cacheKey(r.templatePath)
        bmpCache[key] ?: BitmapFactory.decodeFile(r.templatePath)?.also { bmpCache[key] = it }
    }

    private fun getTpl(r: Rule): Matcher.Tpl? = synchronized(tplCache) {
        val key = cacheKey(r.templatePath)
        tplCache[key] ?: run {
            val bmp = BitmapFactory.decodeFile(r.templatePath)
            if (bmp == null) null else try {
                Matcher.prepareTemplate(bmp).also { tplCache[key] = it }
            } catch (e: Exception) {
                AppLog.e("匹配", "规则「${r.name}」模板转换失败", e); null
            }
        }
    }

    // ───────── 画面稳定等待（防止点后动画没播完就识别，轮次变快后更要紧）─────────
    private fun sig(b: Bitmap): IntArray = try {
        val top = (b.height * 0.33).toInt().coerceIn(0, b.height - 1)
        val sub = Bitmap.createBitmap(b, 0, top, b.width, b.height - top)
        val sm = Bitmap.createScaledBitmap(sub, 72, 120, false)
        val px = IntArray(72 * 120)
        sm.getPixels(px, 0, 72, 0, 0, 72, 120)
        if (sm !== sub) sm.recycle()
        if (sub !== b) sub.recycle()
        px
    } catch (e: Exception) { IntArray(0) }

    private fun changedFrac(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var n = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            val dr = Math.abs(((x shr 16) and 255) - ((y shr 16) and 255))
            val dg = Math.abs(((x shr 8) and 255) - ((y shr 8) and 255))
            val db = Math.abs((x and 255) - (y and 255))
            if (maxOf(dr, dg, db) > 48) n++
        }
        return n.toDouble() / a.size
    }

    private suspend fun settle(first: Bitmap): Bitmap {
        var cur = first
        var tries = 0
        val ref = lastTapFrameSig
        if (ref != null && ref.isNotEmpty()) {
            // 0.22：等到画面相对“点之前那帧”真正变了才走。
            // 旧逻辑是“画面稳定就走”，但 grab() 没新帧时会复用同一张旧图，变化率 0 也算稳定，
            // 于是下一轮读到的还是点之前的旧棋盘、把同一伙又点一遍（0.19/0.21 实测每局浪费 2~4 次施法）。
            while (tries < SETTLE_TRIES) {
                if (changedFrac(ref, sig(cur)) > SETTLE_FRAC) break
                delay(SETTLE_GAP_MS)
                cur = ScreenGrabber.grab() ?: break
                tries++
            }
            if (tries >= SETTLE_TRIES && changedFrac(ref, sig(cur)) <= SETTLE_FRAC) {
                AppLog.d("匹配", "点后等画面：${SETTLE_TRIES} 帧都没变（帧复用或战斗已结束），继续用当前帧")
            }
        } else {
            var prev = sig(cur)
            while (tries < SETTLE_TRIES) {
                delay(SETTLE_GAP_MS)
                val nxt = ScreenGrabber.grab() ?: break
                val s = sig(nxt)
                val ch = changedFrac(prev, s)
                cur = nxt; prev = s
                tries++
                if (ch < SETTLE_FRAC) break
            }
        }
        if (tries > 1) AppLog.d("匹配", "点后等画面稳定：多截了 $tries 帧（约 ${tries * SETTLE_GAP_MS}ms）")
        return cur
    }

    private fun start(ctx: Context, maxClicks: Int) {
        job = CoroutineScope(Dispatchers.Default).launch {
            val rules = RuleStore.load(ctx).filter { it.enabled }
            val clusterRules = rules.filter { it.type != "color" && it.clusterFirst }
            val normalRules = rules - clusterRules.toSet()
            AppLog.i("服务", "开始运行：${rules.size} 条启用规则 [${rules.joinToString("、") { it.name }}]，循环 ${if (maxClicks > 0) "$maxClicks 次" else "无限"}" + if (clusterRules.isNotEmpty()) "（扎堆规则每轮比期限再点）" else "")
            if (rules.isEmpty()) AppLog.w("服务", "没有启用的规则，不会执行任何点击")
            lastSig = ""; sameRounds = 0; noEffectStop = false; lastTapAt = 0L
            lastTapRuleId = null; lastTapPoint = null; lastTapBoardSig = ""; pendingBoardSig = ""; repeatSkips = 0; lastTapFrameSig = null
            slideRetries = 0; slidingFlag = false; zeroShotDone = false; zeroShots = 0; clearReplay()
            BoardMemory.beginBattle(ctx)
            var frameLogged = false
            var nullLogged = false
            var clicks = 0
            var lastActAt = System.currentTimeMillis()
            try {
            while (isActive) {
                var frame = ScreenGrabber.grab()
                if (frame == null) {
                    if (!nullLogged) {
                        AppLog.w("截图", "还没截到画面（可能未授权截屏或服务未启动）")
                        nullLogged = true
                    }
                    delay(300); continue
                }
                nullLogged = false
                if (!frameLogged) {
                    AppLog.i("截图", "首帧画面 ${frame.width}x${frame.height}")
                    frameLogged = true
                }
                if (lastTapAt > 0 && System.currentTimeMillis() - lastTapAt < 4000) frame = settle(frame)
                var acted = false
                // 1. 普通规则按顺序，先命中先点（保持老行为）
                for (r in normalRules) {
                    val p = match(frame, r)
                    if (p == null) {
                        logMiss(r)
                        continue
                    }
                    lastTapFrameSig = sig(frame)
                    val n = doTap(r, p, maxClicks, clicks)
                    if (n < 0) { stop(); return@launch }
                    clicks = n
                    acted = true
                    break
                }
                // 2. 没普通规则命中时，扎堆规则按期限+只数选一伙再点
                if (!acted && clusterRules.isNotEmpty()) {
                    val best = matchClusterRound(ctx, frame, clusterRules)
                    if (noEffectStop) { stop(); return@launch }
                    if (slidingFlag) { slidingFlag = false; delay(SLIDE_RETRY_MS); continue }
                    if (best != null) {
                        lastTapFrameSig = sig(frame)
                        val n = doTap(best.rule, best.point, maxClicks, clicks, best.score, best.count, best.aiNote)
                        if (n < 0) { stop(); return@launch }
                        clicks = n
                        acted = true
                    }
                }
                if (acted) {
                    lastActAt = System.currentTimeMillis()
                } else {
                    if (System.currentTimeMillis() - lastActAt >= AUTO_STOP_IDLE_MS) {
                        AppLog.i("服务", "连续 ${AUTO_STOP_IDLE_MS / 1000} 秒没有可点的怪，自动停止")
                        stop(); return@launch
                    }
                    delay(300)
                }
            }
            } finally {
                deathReport()
                BoardMemory.endBattle(ctx)
            }
        }
    }

    private suspend fun doTap(r: Rule, p: Point, maxClicks: Int, clicks: Int, score: Double? = null, cluster: Int? = null, note: String? = null): Int {
        // 停了就别再发手势（0.17 实测：停的日志都出了，下一轮的点还发出去了）
        if (job?.isActive != true) {
            AppLog.w("点击", "已停止，本次点击取消 (${p.x},${p.y})")
            return clicks
        }
        logHit(r, p, score, cluster, note)
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        val ok = ClickService.tap(tx, ty)
        if (!ok) {
            AppLog.e("点击", "规则「${r.name}」点击 ($tx,$ty) 失败：无障碍手势被拒或服务断开，停止运行")
            return -1
        }
        lastTapAt = System.currentTimeMillis()
        zeroShotDone = false
        if (cluster != null) {
            // 0.22：记下这次扎堆点击的目标 + 点之前那盘的签名，供下轮防重复点
            lastTapRuleId = r.id; lastTapPoint = Point(p.x, p.y)
            lastTapBoardSig = pendingBoardSig; repeatSkips = 0
        } else {
            lastTapRuleId = null; lastTapPoint = null; lastTapBoardSig = ""
        }
        BoardMemory.noteTap(cluster ?: 1)
        val wait = (r.delayMs * Random.nextDouble(0.8, 1.2)).toLong()
        val n = clicks + 1
        AppLog.d("点击", "规则「${r.name}」点击 ($tx,$ty) 已发出，点击后等待 ${wait}ms" + if (maxClicks > 0) "（第 $n/$maxClicks 次）" else "")
        delay(wait)
        if (r.inputText.isNotBlank()) {
            delay(500)
            ClickService.inputText(r.inputText)
        }
        if (maxClicks > 0 && n >= maxClicks) {
            AppLog.i("服务", "已完成 $maxClicks 次点击，自动停止")
            return -1
        }
        return n
    }

    private data class RuleScan(val rule: Rule, val comps: List<Matcher.Comp>, val near: List<Matcher.Near>, val note: String?)
    private data class Cand(val rule: Rule, val comp: Matcher.Comp)
    private data class Ev(val cand: Cand, val hz: Int)

    private fun foldOff(y: Int): Int {
        var d = (y - ROW0_Y) % ROW_STEP
        if (d > ROW_STEP / 2) d -= ROW_STEP
        if (d < -ROW_STEP / 2) d += ROW_STEP
        return d
    }

    /** 没对齐整格的格数（只看 y ≤ GATE_MAX_Y）；对齐的格顺便更新该规则的固定偏差（中位数，最近 60 个） */
    private fun misalignedCells(all: List<Cand>): Int {
        var bad = 0
        for (c in all) {
            val hist = latticeBias.getOrPut(c.rule.name) { ArrayList() }
            val learned = hist.size >= 10
            val bias = if (learned) hist.sorted()[hist.size / 2] else 0
            val tol = if (learned) LATTICE_TOL else LATTICE_TOL_INIT
            for (y in c.comp.ys.ifEmpty { listOf(c.comp.point.y) }) {
                if (y > GATE_MAX_Y) continue
                val d = foldOff(y)
                if (Math.abs(d - bias) > tol) bad++
                else { hist.add(d); if (hist.size > 60) hist.removeAt(0) }
            }
        }
        return bad
    }

    /** 跨类去重：同一位置（±90px）被两条规则都认出时只留相似度高的那个，再按剩下的格重新连片。
     *  放低蛇/树/土豆阈值后，别类怪偶尔会以低分“认”成它们，这一步挡掉。 */
    private fun resolveConflicts(scans: List<RuleScan>): List<RuleScan> {
        if (scans.count { it.comps.isNotEmpty() } < 2) return scans
        val si = ArrayList<Int>(); val pts = ArrayList<Point>(); val sc = ArrayList<Double>()
        for ((i, s) in scans.withIndex()) for (c in s.comps) {
            val cs = c.cells.ifEmpty { listOf(c.point) }
            for ((k, p) in cs.withIndex()) { si.add(i); pts.add(p); sc.add(c.scores.getOrNull(k) ?: c.score) }
        }
        val dead = BooleanArray(pts.size)
        var nDead = 0
        for (a in pts.indices) for (b in pts.indices) {
            if (si[a] == si[b]) continue
            if (Math.abs(pts[a].x - pts[b].x) < 90 && Math.abs(pts[a].y - pts[b].y) < 90 &&
                (sc[b] > sc[a] + 1e-9 || (Math.abs(sc[b] - sc[a]) <= 1e-9 && si[b] < si[a]))) { dead[a] = true; nDead++; break }
        }
        if (nDead == 0) return scans
        AppLog.d("匹配", "跨类去重：$nDead 格同位置被相似度更高的别类怪占了，已丢弃")
        return scans.mapIndexed { i, s ->
            if (s.comps.isEmpty()) s else {
                val kp = ArrayList<Point>(); val ks = ArrayList<Double>()
                for (k in pts.indices) if (si[k] == i && !dead[k]) { kp.add(pts[k]); ks.add(sc[k]) }
                s.copy(comps = Matcher.regroup(kp, ks))
            }
        }
    }

    // ───────── 复盘：保留最近 3 轮决策文字 + 最近 2 张棋盘半尺寸图，一局结束时输出/存盘 ─────────
    private fun clearReplay() {
        recentRounds.clear()
        for (b in recentShots) if (!b.isRecycled) b.recycle()
        recentShots.clear()
    }

    private fun rememberRound(evs: List<Ev>, pick: Cand, frame: Bitmap) {
        val txt = evs.sortedBy { BoardMemory.minRowOf(it.cand.comp) }.joinToString(" ") { e ->
            val c = e.cand.comp
            val rows = (c.ys.ifEmpty { listOf(c.point.y) }).map { y -> rowOf(y) }.sorted().joinToString(",")
            "${e.cand.rule.name.removePrefix("守卫-")}${c.size}只[排$rows]${hzText(e.hz)}"
        } + " → 点${pick.rule.name.removePrefix("守卫-")}${pick.comp.size}只"
        recentRounds.addLast(txt)
        while (recentRounds.size > 3) recentRounds.removeFirst()
        try {
            val top = 1100.coerceAtMost(frame.height - 2)
            val crop = Bitmap.createBitmap(frame, 0, top, frame.width, frame.height - top)
            val small = Bitmap.createScaledBitmap(crop, crop.width / 2, crop.height / 2, true)
            if (crop !== frame) crop.recycle()
            recentShots.add(small)
            while (recentShots.size > 2) recentShots.removeAt(0).recycle()
        } catch (e: Exception) { }
    }

    private fun deathReport() {
        if (recentRounds.isNotEmpty()) {
            AppLog.i("复盘", "本局最后 ${recentRounds.size} 轮决策（旧→新；[排]=该伙每只所在排，-1=贴墙后一格）：")
            for (t in recentRounds) AppLog.i("复盘", t)
        }
        val n = recentShots.size
        for ((i, b) in recentShots.withIndex()) BoardMemory.saveBitmap(b, "last${n - i}")
        clearReplay()
    }

    private fun scanRule(frame: Bitmap, board: Matcher.BoardView?, r: Rule, tpl: Matcher.Tpl?): RuleScan {
        return try {
            if (board != null && tpl != null) {
                val s = Matcher.findBoardComponents(board, tpl, effThr(r), r.region)
                RuleScan(r, s.comps, s.near, null)
            } else {
                val bmp = getBmp(r)
                if (bmp == null) {
                    AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                    RuleScan(r, emptyList(), emptyList(), "模板缺失")
                } else RuleScan(r, Matcher.findImageComponents(frame, bmp, effThr(r), r.region), emptyList(), null)
            }
        } catch (e: Exception) {
            AppLog.e("匹配", "规则「${r.name}」匹配异常", e)
            RuleScan(r, emptyList(), emptyList(), "异常")
        }
    }

    private fun hzText(hz: Int) = when {
        hz < 0 -> "点完即越线"
        hz >= BoardMemory.HZ_NONE -> "余量∞"
        else -> "余量${hz}"
    }

    /** 扎堆规则选点（0.19 期限优先）：
     *  1) 先看“点完它之后，剩下的伙还能不能全在撞墙前清完”（BoardMemory.horizonAfterKill）；余量封顶 HZ_CAP 后再比；
     *  2) 余量相同比只数；再同则 y 小（更靠墙）；再同比相似度。
     *  另外检测“点击没效果”（棋盘连续一字不差）并自动停。 */
    private suspend fun matchClusterRound(ctx: Context, frame: Bitmap, clusterRules: List<Rule>): ClusterPick? {
        val t0 = System.nanoTime()
        val board: Matcher.BoardView? = try { Matcher.prepareBoard(frame) } catch (e: Exception) {
            AppLog.e("匹配", "棋盘裁剪失败，本轮走旧路径", e); null
        }
        val tpls = clusterRules.map { if (board != null) getTpl(it) else null }
        val t1 = System.nanoTime()
        var scans: List<RuleScan> = try {
            coroutineScope {
                clusterRules.mapIndexed { i, r -> async(Dispatchers.Default) { scanRule(frame, board, r, tpls[i]) } }.awaitAll()
            }
        } finally {
            board?.release()
        }
        var verified = ""
        if (board != null && scans.all { it.comps.isEmpty() }) {
            // 零命中复核：整轮什么都没找到时用旧路径（全分辨率）再扫一遍，既防快路径漏识别，也量出漏了多少
            val legacy = clusterRules.map { scanRule(frame, null, it, null) }
            val n = legacy.sumOf { s -> s.comps.sumOf { it.size } }
            if (n > 0) AppLog.w("匹配", "零命中复核：快路径整轮 0 只，旧路径（全分辨率）却找到 $n 只——快路径漏识别，请把这行和当时截图发我")
            else verified = "（旧路径复核也是 0）"
            scans = legacy
        }
        scans = resolveConflicts(scans)
        val t2 = System.nanoTime()

        val all = ArrayList<Cand>()
        val counts = ArrayList<String>()
        for (s in scans) {
            if (s.note != null) { counts.add("${s.rule.name} ${s.note}"); continue }
            if (s.comps.isEmpty()) { counts.add("${s.rule.name} 0只"); continue }
            val b = s.comps.sortedWith(compareByDescending<Matcher.Comp> { it.size }.thenBy { it.point.y }).first()
            counts.add("${s.rule.name} ${b.size}只(y${b.point.y})")
            for (c in s.comps) all.add(Cand(s.rule, c))
        }
        val summary = "本轮比数量：${counts.joinToString(" / ")}"
        if (all.isEmpty()) {
            AppLog.d("匹配", "$summary → 都没找到$verified")
            // 点过之后第一次整盘全零：要么这一波清完了，要么战斗结束。存一张整屏图当凭证（每局最多 3 张）
            if (!zeroShotDone && lastTapAt > 0 && zeroShots < 3) {
                zeroShotDone = true; zeroShots++
                BoardMemory.saveBitmap(frame, "zero$zeroShots")
                BoardMemory.auditZeroFrame(frame)   // 0.38：学空地参考色 + 看第 -2 排有没有越线怪底色（只记不改决策）
            }
            return null
        }
        // 0.23 滑动帧闸门：怪没对齐到整格 = 画面正在滑动，这一帧不用（不进预测校验、不选点），稍等重截。
        // 连等 SLIDE_MAX_RETRY 次还没对齐（帧复用或画面冻住）就按最近排处理，避免卡死。
        val bad = misalignedCells(all)
        if (bad > 0 && slideRetries < SLIDE_MAX_RETRY) {
            slideRetries++
            slidingFlag = true
            if (slideRetries == 1) AppLog.d("匹配", "画面在滑动：$bad 格没对齐到整格，等稳定再扫")
            return null
        }
        if (slideRetries > 0) AppLog.d("匹配", "滑动等待结束：重试 $slideRetries 次" + if (bad > 0) "（仍有 $bad 格没对齐，按最近排处理）" else "")
        slideRetries = 0
        // 棋盘签名：施法后整盘会上移一行，签名必变；连续一样 = 点击没触发施法。
        // 签名里去掉贴着阈值的临界识别（0.19 实测：蛇 0.71~0.73 对 0.7 阈值，一轮有一轮没，
        // 签名每轮都变、无效果停永远触发不了，才会在结算画面上空点 7 轮）。临界只影响签名，不影响选目标。
        val sigComps = all.filter { it.comp.score >= effThr(it.rule) + 0.05 }
        var roundSig = ""
        if (sigComps.isNotEmpty()) {
            val sig = sigComps.sortedWith(compareBy({ it.rule.name }, { it.comp.point.y }, { it.comp.point.x }))
                .joinToString(";") { "${it.rule.name}${it.comp.size}@${it.comp.point.x},${it.comp.point.y}" }
            roundSig = sig
            if (sig == lastSig) sameRounds++ else sameRounds = 0
            lastSig = sig
            if (sameRounds >= 2) {
                AppLog.w("匹配", "连续 ${sameRounds + 1} 轮棋盘一字不差：点击没有触发施法，战斗可能已结束（或城墙已破），自动停止")
                noEffectStop = true
                return null
            }
        }
        val compsAll = all.map { it.rule.name to it.comp }
        val evs = all.map { Ev(it, BoardMemory.horizonAfterKill(compsAll, it.comp)) }
        val ranked = evs.sortedWith(
            compareByDescending<Ev> { minOf(it.hz, HZ_CAP) }
                .thenByDescending { it.cand.comp.size }
                .thenBy { it.cand.comp.point.y }
                .thenByDescending { it.cand.comp.score }
        )
        // 0.26 AI 判点：把排好序的候选发给 Gemini 选一伙；失败/超时/没开/模型说没得点时用期限规则的 pick。
        // 模型报的未知怪如果贴墙（第 0/1 排）就优先点它那个格子中心——模板认不出的怪是撞墙的主因。
        var pickEv = ranked.first()
        var aiChanged = false
        var unknownTap: Point? = null
        var unknownDesc = ""
        var unknownCol = -1
        var unknownRow = -1
        if (AiJudge.isEnabled(ctx)) {
            val cands = ranked.take(5).mapIndexed { i, e ->
                AiJudge.Candidate(i, e.cand.rule.name, e.cand.comp.size, BoardMemory.minRowOf(e.cand.comp), hzText(e.hz), e.cand.comp.point.x, e.cand.comp.point.y)
            }
            val verdict = AiJudge.judge(ctx, frame, cands)
            if (verdict != null) {
                verdict.unknown?.let { u ->
                    unknownCol = u.col; unknownRow = u.row; unknownDesc = u.desc
                    AppLog.i("AI判点", "发现未知怪：${u.desc}（第 ${u.col} 列第 ${u.row} 排）")
                    if (u.row <= 1) unknownTap = AiJudge.cellCenter(u.col, u.row)
                }
                if (unknownTap == null) {
                    if (verdict.pick in cands.indices) {
                        val aiEv = ranked[verdict.pick]
                        // 0.35：AI 只许在和期限规则同余量的候选里挑（比封顶后的余量），
                        // 不许越级点低余量的——第 21 局 00:23:53 模型越过余量 3 的蝎子 1 只、
                        // 点了余量 2 的史莱姆 3 只。0.28 的越线兜底是它的特例（越线余量 -1
                        // 比不越线低，一定被这道拦下）。
                        // 0.37：再加只数关——同余量也只许选只数不小于期限规则那伙的。
                        // 0.35 没拦住同余量看小：23 局三连（蝎子 2 只替史莱姆 7 只，影子
                        // 差 +5.0；土豆 2 只替树怪 4 只；土豆 5 只替史莱姆 6 只）、25 局
                        // 两连（史莱姆 1 只替土豆怪 2 只；树怪 2 只替蛇 4 只），影子全判
                        // 更差。加了这道，AI 能动的只剩同余量同只数的平局（按位置挑）。
                        if (minOf(aiEv.hz, HZ_CAP) < minOf(pickEv.hz, HZ_CAP)) {
                            AppLog.w("AI判点", "AI 选了余量更低的「${aiEv.cand.rule.name.removePrefix("守卫-")}」${aiEv.cand.comp.size}只（${hzText(aiEv.hz)}），期限规则是「${pickEv.cand.rule.name.removePrefix("守卫-")}」${pickEv.cand.comp.size}只（${hzText(pickEv.hz)}），改回期限规则")
                        } else if (aiEv.cand.comp.size < pickEv.cand.comp.size) {
                            AppLog.w("AI判点", "AI 选了只数更少的「${aiEv.cand.rule.name.removePrefix("守卫-")}」${aiEv.cand.comp.size}只（${hzText(aiEv.hz)}），期限规则是「${pickEv.cand.rule.name.removePrefix("守卫-")}」${pickEv.cand.comp.size}只（${hzText(pickEv.hz)}），改回期限规则")
                        } else if (aiEv.cand.comp !== pickEv.cand.comp) {
                            aiChanged = true
                            AppLog.i("AI判点", "选第 ${verdict.pick} 伙「${aiEv.cand.rule.name.removePrefix("守卫-")}」${aiEv.cand.comp.size}只（第${BoardMemory.minRowOf(aiEv.cand.comp)}排，${hzText(aiEv.hz)}），期限规则本来选「${pickEv.cand.rule.name.removePrefix("守卫-")}」${pickEv.cand.comp.size}只")
                            pickEv = aiEv
                        } else {
                            AppLog.d("AI判点", "和期限规则一致，点「${pickEv.cand.rule.name}」${pickEv.cand.comp.size}只")
                        }
                    } else if (verdict.pick == -1) {
                        AppLog.d("AI判点", "模型说没有能点的，退回期限规则")
                    }
                }
            }
        }
        if (unknownTap != null) {
            val cell = unknownTap!!
            pickEv = Ev(Cand(pickEv.cand.rule, Matcher.Comp(cell, 1, 0.0, ys = listOf(cell.y), cells = listOf(cell))), pickEv.hz)
        }
        // 0.39 贴墙漏数代点：上一轮漏数的第 0 排怪现在已到 -1 排，下一施法就撞墙；
        // 模板认不出它、底色却有怪，坐标是清楚的。期限规则点的不是 -1 排时改点它——
        // 不点它就等撞墙。AI 未知怪已接管时不叠加。
        var wallFallback: Point? = null
        var wallFallbackCol = -1
        var origPickDesc = ""
        if (unknownTap == null) {
            val detectedCells = all.flatMap { it.comp.cells.ifEmpty { listOf(it.comp.point) } }
                .map { Pair(colOf(it.x), rowOf(it.y)) }.toSet()
            val fb = BoardMemory.wallMissFallback(detectedCells)
            if (fb.isNotEmpty() && BoardMemory.minRowOf(pickEv.cand.comp) > -1) {
                origPickDesc = "${pickEv.cand.rule.name.removePrefix("守卫-")}${pickEv.cand.comp.size}只（第${BoardMemory.minRowOf(pickEv.cand.comp)}排，${hzText(pickEv.hz)}）"
                val (fc, _) = fb.minByOrNull { it.first }!!
                wallFallbackCol = fc
                wallFallback = BoardMemory.cellCenter(fc, -1)
                val cell = wallFallback!!
                pickEv = Ev(Cand(WALL_FALLBACK_RULE, Matcher.Comp(cell, 1, 0.0, ys = listOf(cell.y), cells = listOf(cell))), pickEv.hz)
            }
        }
        val pick = pickEv.cand
        val bySize = evs.sortedWith(compareByDescending<Ev> { it.cand.comp.size }.thenBy { it.cand.comp.point.y }).first()
        val top3 = ranked.take(3).joinToString(" | ") {
            "${it.cand.rule.name.removePrefix("守卫-")}${it.cand.comp.size}只 第${BoardMemory.minRowOf(it.cand.comp)}排 ${hzText(it.hz)}"
        }
        val wallCnt = (pick.comp.ys.ifEmpty { listOf(pick.comp.point.y) }).count { rowOf(it) <= 0 }
        val t3 = System.nanoTime()
        // 0.22 防重复点：棋盘和上次点之前一字不差、选中的又是同一目标 = 读到的是点之前的旧帧。
        // 跳过这一轮不点，等新画面；连跳 3 轮还不变，说明那次点可能真没打中，放行重打。
        if (roundSig.isNotEmpty() && pick.rule.id == lastTapRuleId && pick.comp.point == lastTapPoint && roundSig == lastTapBoardSig) {
            repeatSkips++
            if (repeatSkips < 3) {
                AppLog.d("匹配", "棋盘没变（与上次点 (${lastTapPoint!!.x},${lastTapPoint!!.y}) 时同一盘），跳过重复点，等新画面（第 $repeatSkips 次）")
                return null
            }
            AppLog.w("匹配", "同一目标连跳 $repeatSkips 轮仍没变，放行重打 (${pick.comp.point.x},${pick.comp.point.y})")
            repeatSkips = 0
        } else {
            repeatSkips = 0
        }
        if (wallFallback != null) {
            AppLog.w("匹配", "$summary → 贴墙漏数代点：第 ${wallFallbackCol + 1} 列第 -1 排（${wallFallback!!.x},${wallFallback!!.y}）（模板没认出、底色有怪，马上撞墙；期限规则本来点「$origPickDesc」）候选前三：$top3")
        } else if (unknownTap != null) {
            AppLog.d("匹配", "$summary → AI判点：点未知怪「$unknownDesc」（第 $unknownCol 列第 $unknownRow 排，(${unknownTap!!.x},${unknownTap!!.y})）候选前三：$top3")
        } else {
            AppLog.d("匹配", "$summary → 点「${pick.rule.name}」（连成 ${pick.comp.size} 只，其中贴墙 $wallCnt 只，y${pick.comp.point.y}，相似度 ${"%.3f".format(pick.comp.score)}，${hzText(pickEv.hz)}）候选前三：$top3")
        }
        AppLog.d("性能", "本轮识别 ${(t3 - t0) / 1_000_000}ms（截图转换 ${(t1 - t0) / 1_000_000} / 匹配 ${(t2 - t1) / 1_000_000} / 决策 ${(t3 - t2) / 1_000_000}）")
        if (unknownTap == null && wallFallback == null && !aiChanged && bySize.cand.comp !== pick.comp && bySize.cand.comp.size > pick.comp.size) {
            BoardMemory.noteOverride()
            AppLog.w("匹配", "期限优先：只数最大的「${bySize.cand.rule.name.removePrefix("守卫-")}」${bySize.cand.comp.size}只，点完它 ${hzText(bySize.hz)}；改点「${pick.rule.name.removePrefix("守卫-")}」${pick.comp.size}只（${hzText(pickEv.hz)}）")
        }
        rememberRound(evs, pick, frame)
        val near = scans.flatMap { s -> s.near.map { s.rule.name to it } }
        val diagTpls = clusterRules.mapIndexedNotNull { i, r -> tpls[i]?.let { r.name to it } }
        BoardMemory.onRound(compsAll, pick.comp, pick.rule.name, frame, near, diagTpls)?.let { AppLog.d("记忆", it) }
        pendingBoardSig = roundSig
        val aiNote = if (unknownTap != null) "未知怪「$unknownDesc」（第 $unknownCol 列第 $unknownRow 排）" else null
        return ClusterPick(pick.rule, pick.comp.point, pick.comp.score, pick.comp.size, aiNote)
    }

    private fun logHit(r: Rule, p: Point, score: Double? = null, cluster: Int? = null, note: String? = null) {
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        if (note != null) {
            AppLog.d("匹配", "AI判点：$note，点击 ($tx,$ty)", tx, ty)
            return
        }
        if (r.type == "color") {
            AppLog.d("匹配", "规则「${r.name}」找色 ${r.colorHex} ±${r.tolerance} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)", tx, ty)
        } else {
            val s = score ?: Matcher.lastImageScore
            val c = cluster ?: Matcher.lastClusterSize
            AppLog.d("匹配", "规则「${r.name}」找图 相似度 ${"%.3f".format(s)} ≥ ${r.threshold} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)" + if (r.clusterFirst) "（连成 $c 只）" else "", tx, ty)
        }
    }

    private fun logMiss(r: Rule) {
        if (r.type == "color") {
            AppLog.d("匹配", "规则「${r.name}」找色 ${r.colorHex} ±${r.tolerance} → 未命中")
        } else {
            AppLog.d("匹配", "规则「${r.name}」找图 最高相似度 ${"%.3f".format(Matcher.lastImageScore)} < ${r.threshold} → 未命中")
        }
    }

    private fun match(frame: Bitmap, r: Rule): Point? = try {
        if (r.type == "color") Matcher.findColor(frame, r.colorHex, r.tolerance, r.region)
        else {
            val tpl = getBmp(r)
            if (tpl == null) {
                AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                null
            } else if (r.clusterFirst) {
                Matcher.findImageComponents(frame, tpl, r.threshold, r.region)
                    .sortedWith(compareByDescending<Matcher.Comp> { it.size }.thenBy { it.point.y })
                    .firstOrNull()?.point
            } else Matcher.findImage(frame, tpl, r.threshold, r.region)
        }
    } catch (e: Exception) {
        AppLog.e("匹配", "规则「${r.name}」匹配异常", e)
        null
    }
}
