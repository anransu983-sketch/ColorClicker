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

    fun isRunning() = job?.isActive == true

    /** 扎堆比数量的结果：规则、点、该规则的相似度、能消只数 */
    private data class ClusterPick(val rule: Rule, val point: Point, val score: Double, val count: Int)

    /** 棋盘第 0 排（贴墙）中心 y 与行距。第 0 排的怪下一次施法后到第 -1 排（y1271）仍可点，再下一次才撞墙 */
    private const val ROW0_Y = 1511
    private const val ROW_STEP = 240
    private fun rowOf(y: Int) = Math.round((y - ROW0_Y).toDouble() / ROW_STEP).toInt()   // 可为 -1

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
                    val best = matchClusterRound(frame, clusterRules)
                    if (noEffectStop) { stop(); return@launch }
                    if (best != null) {
                        lastTapFrameSig = sig(frame)
                        val n = doTap(best.rule, best.point, maxClicks, clicks, best.score, best.count)
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
                BoardMemory.endBattle(ctx)
            }
        }
    }

    private suspend fun doTap(r: Rule, p: Point, maxClicks: Int, clicks: Int, score: Double? = null, cluster: Int? = null): Int {
        // 停了就别再发手势（0.17 实测：停的日志都出了，下一轮的点还发出去了）
        if (job?.isActive != true) {
            AppLog.w("点击", "已停止，本次点击取消 (${p.x},${p.y})")
            return clicks
        }
        logHit(r, p, score, cluster)
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        val ok = ClickService.tap(tx, ty)
        if (!ok) {
            AppLog.e("点击", "规则「${r.name}」点击 ($tx,$ty) 失败：无障碍手势被拒或服务断开，停止运行")
            return -1
        }
        lastTapAt = System.currentTimeMillis()
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

    private fun scanRule(frame: Bitmap, board: Matcher.BoardView?, r: Rule, tpl: Matcher.Tpl?): RuleScan {
        return try {
            if (board != null && tpl != null) {
                val s = Matcher.findBoardComponents(board, tpl, r.threshold, r.region)
                RuleScan(r, s.comps, s.near, null)
            } else {
                val bmp = getBmp(r)
                if (bmp == null) {
                    AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                    RuleScan(r, emptyList(), emptyList(), "模板缺失")
                } else RuleScan(r, Matcher.findImageComponents(frame, bmp, r.threshold, r.region), emptyList(), null)
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
    private suspend fun matchClusterRound(frame: Bitmap, clusterRules: List<Rule>): ClusterPick? {
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
            return null
        }
        // 棋盘签名：施法后整盘会上移一行，签名必变；连续一样 = 点击没触发施法。
        // 签名里去掉贴着阈值的临界识别（0.19 实测：蛇 0.71~0.73 对 0.7 阈值，一轮有一轮没，
        // 签名每轮都变、无效果停永远触发不了，才会在结算画面上空点 7 轮）。临界只影响签名，不影响选目标。
        val sigComps = all.filter { it.comp.score >= it.rule.threshold + 0.05 }
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
        val pickEv = ranked.first()
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
        AppLog.d("匹配", "$summary → 点「${pick.rule.name}」（连成 ${pick.comp.size} 只，其中贴墙 $wallCnt 只，y${pick.comp.point.y}，相似度 ${"%.3f".format(pick.comp.score)}，${hzText(pickEv.hz)}）候选前三：$top3")
        AppLog.d("性能", "本轮识别 ${(t3 - t0) / 1_000_000}ms（截图转换 ${(t1 - t0) / 1_000_000} / 匹配 ${(t2 - t1) / 1_000_000} / 决策 ${(t3 - t2) / 1_000_000}）")
        if (bySize.cand.comp !== pick.comp && bySize.cand.comp.size > pick.comp.size) {
            BoardMemory.noteOverride()
            AppLog.w("匹配", "期限优先：只数最大的「${bySize.cand.rule.name.removePrefix("守卫-")}」${bySize.cand.comp.size}只，点完它 ${hzText(bySize.hz)}；改点「${pick.rule.name.removePrefix("守卫-")}」${pick.comp.size}只（${hzText(pickEv.hz)}）")
        }
        val near = scans.flatMap { s -> s.near.map { s.rule.name to it } }
        BoardMemory.onRound(compsAll, pick.comp, pick.rule.name, frame, near)?.let { AppLog.d("记忆", it) }
        pendingBoardSig = roundSig
        return ClusterPick(pick.rule, pick.comp.point, pick.comp.score, pick.comp.size)
    }

    private fun logHit(r: Rule, p: Point, score: Double? = null, cluster: Int? = null) {
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
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
