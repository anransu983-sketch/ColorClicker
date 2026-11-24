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

    /** 0.23 有效阈值 = min(规则阈值, 基准上限)，按规则名含的字匹配（她不用调）。
     *  基准表在 BoardMemory.baseThr（0.32/0.41/0.42 的依据见那里）；0.51 起再按跨局漏检证据自动下调
     *  （BoardMemory.adaptiveThr），只降不升，下限 0.56（空盘噪声实测 ≤0.53）。 */
    private fun effThr(r: Rule): Double {
        val cap = BoardMemory.baseThr(r.name)
        return minOf(r.threshold, BoardMemory.adaptiveThr(r.name, cap))
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
    /** 0.47 半截帧等待：点后第一帧伙已消、整盘还没上移（BoardMemory.unshiftedMatch）时稍等重截；最多等 12×100ms，再不上移就按当前帧处理 */
    private const val STALE_RETRY_MS = 100L
    private const val STALE_MAX_RETRY = 12
    /** 0.78：上移等待中棋盘连续一字不差满 5 轮（本轮 + 4）即判冻住。正常上移 1–2 轮就完成，77/78 局终局白烧 12 次×2 轮约 12 秒。
     *  0.80 改格子级 cellSig：0.78 的 boardSig 用原始匹配坐标，79 局冻盘上仍抖、没触发。 */
    private const val STALE_FROZEN_ROUNDS = 4
    private var staleRetries = 0
    @Volatile private var staleFlag = false
    @Volatile private var slidingFlag = false
    /** 0.48 点前复核：选点后、发点击前再取一帧，只比“被点那伙周围一圈”和选点帧的差异；变化占比 > SETTLE_FRAC（沿用点后稳定的阈值，不新增阈值）
     *  = 盘面已经在动，取消这次点击、重扫。连续最多取消 PRETAP_MAX_ABORT 次，再变也放行（防怪物待机动画误报把轮次卡死）。
     *  PRETAP_RECHECK=false 可整段关掉。 */
    private const val PRETAP_RECHECK = true
    private const val PRETAP_MARGIN = 180
    private const val PRETAP_MAX_ABORT = 1
    private var preTapAborts = 0
    /** 0.48：选点完成的时刻，给总落空诊断算“选点→点击”耗时 */
    private var pickDecidedAt = 0L
    /** 0.48：settle 等满 SETTLE_TRIES 帧画面相对点前那帧一点没变 = 画面冻住（战斗已结束 / 点击没被游戏接收）。
     *  半截帧里被点的伙已消、画面一定有变，所以冻住的帧不是半截帧，上移等待跳过（0.47 实测终局白等 ~12 秒） */
    @Volatile private var settleUnchanged = false

    // ───────── 0.23 复盘（死前回放）─────────
    private val recentRounds = java.util.ArrayDeque<String>()
    private val recentShots = ArrayList<Bitmap>()
    private var zeroShotDone = false
    private var zeroShots = 0

    fun isRunning() = job?.isActive == true

    /** 扎堆比数量的结果：规则、点、该规则的相似度、能消只数 */
    private data class ClusterPick(val rule: Rule, val point: Point, val score: Double, val count: Int, val cells: List<Point> = emptyList())

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
    /** 0.58：棋盘冻住计数（43 局收尾失灵：蝎子相似度在签名临界 0.81 上下抖，签名一字不差永远凑不满 3 轮，
     *  但 settle 的“8 帧不变”冻住信号不依赖签名；连续 2 轮冻住且中间没有活帧就停）。活帧（liveFrame）时清零。 */
    private var frozenRounds = 0
    /** 0.78：上移等待里棋盘连续一字不差的计数（配 STALE_FROZEN_ROUNDS；0.80 起存格子签名 cellSig）。活帧或上移等待结束时清零。 */
    private var staleSig = ""
    private var staleSame = 0
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
        BoardOverlayState.post(BoardSnapshot(running = false))   // 0.67：悬浮棋盘显示“未运行”
        BoardOverlay.hide()   // 0.70：一局结束自动收回关闭悬浮棋盘
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

    /** 0.48：取 b 里 [x0,x1)×[y0,y1) 的下采样像素（宽高各 1/8，至少 1），点前复核比对用；出错返回空数组（changedFrac 对空数组返回 0 = 不拦） */
    private fun regionSig(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray = try {
        val w = (x1 - x0).coerceAtLeast(1); val h = (y1 - y0).coerceAtLeast(1)
        val sub = Bitmap.createBitmap(b, x0, y0, w, h)
        val sm = Bitmap.createScaledBitmap(sub, (w / 8).coerceAtLeast(1), (h / 8).coerceAtLeast(1), false)
        val px = IntArray(sm.width * sm.height)
        sm.getPixels(px, 0, sm.width, 0, 0, sm.width, sm.height)
        if (sm !== sub) sm.recycle()
        if (sub !== b) sub.recycle()
        px
    } catch (e: Exception) { IntArray(0) }

    /** 0.48 点前复核：先从选点帧取被点那伙周围（±PRETAP_MARGIN）的签名，再取一帧新的、同一块区域比一次。
     *  返回（变化占比, 耗时 ms）；没有新帧（grab 复用同一张）/ 尺寸不一致 / 出错返回 null（不拦）。
     *  顺序是先算选点帧签名、后 grab：不假设 grab 会不会回收上一张。 */
    private fun preTapCheck(scan: Bitmap, cells: List<Point>): Pair<Double, Long>? {
        if (cells.isEmpty()) return null
        val t0 = System.nanoTime()
        return try {
            if (scan.isRecycled) return null
            val x0 = (cells.minOf { it.x } - PRETAP_MARGIN).coerceIn(0, scan.width - 1)
            val x1 = (cells.maxOf { it.x } + PRETAP_MARGIN).coerceIn(x0 + 1, scan.width)
            val y0 = (cells.minOf { it.y } - PRETAP_MARGIN).coerceIn(0, scan.height - 1)
            val y1 = (cells.maxOf { it.y } + PRETAP_MARGIN).coerceIn(y0 + 1, scan.height)
            val a = regionSig(scan, x0, y0, x1, y1)
            val fresh = ScreenGrabber.grab() ?: return null
            if (fresh === scan || fresh.isRecycled || fresh.width != scan.width || fresh.height != scan.height) return null
            val b = regionSig(fresh, x0, y0, x1, y1)
            if (a.isEmpty() || a.size != b.size) return null
            Pair(changedFrac(a, b), (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Exception) { null }
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
                settleUnchanged = true
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
            lastSig = ""; sameRounds = 0; noEffectStop = false; frozenRounds = 0; lastTapAt = 0L
            lastTapRuleId = null; lastTapPoint = null; lastTapBoardSig = ""; pendingBoardSig = ""; repeatSkips = 0; lastTapFrameSig = null
            slideRetries = 0; slidingFlag = false; staleRetries = 0; staleFlag = false; zeroShotDone = false; zeroShots = 0; clearReplay()
            preTapAborts = 0; pickDecidedAt = 0L; settleUnchanged = false
            BoardMemory.beginBattle(ctx)
            // 0.64：分局标记——每局开局打一行，日志页按这行把日志切成段、按局看。
            AppLog.i("守卫", "===== 第 ${BoardMemory.currentGameNo()} 局开始 =====")
            var frameLogged = false
            var nullLogged = false
            var clicks = 0
            var lastActAt = System.currentTimeMillis()
            // 0.90：棋盘空了就先收悬浮窗，别等 Runner 25 秒停。用户 2026-11-24 指出小窗收得太慢：
            // 战斗结束（棋盘空了）后 Runner 还要等满 25 秒无怪才停、才收窗，106/107 局末点到收窗约 30 秒，
            // 小窗一直顶在挑战页上。改法：某轮整盘没找到怪（acted==false，且不是滑动/半截帧/点前复核
            // 的 continue——那几条路怪还在，不计），立刻收窗；Runner 照旧等 25 秒确认战斗结束才停，
            // 万一是新一波晚到（实测换波不产生空轮），下一轮点到怪就重新打开。 */
            var overlayHiddenForZero = false
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
                settleUnchanged = false
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
                    if (staleFlag) { staleFlag = false; delay(STALE_RETRY_MS); continue }
                    if (best != null) {
                        val refSig = sig(frame)
                        // 0.48 点前复核：选点帧之后画面又动了（扫→点这一小段里开始上移）就别点，重扫。
                        val chk = if (PRETAP_RECHECK) preTapCheck(frame, best.cells) else null
                        if (chk != null && chk.first > SETTLE_FRAC) {
                            if (preTapAborts < PRETAP_MAX_ABORT) {
                                preTapAborts++
                                AppLog.w("点击", "点前复核：被点那伙周围画面又变了 ${"%.1f".format(chk.first * 100)}%（复核 ${chk.second}ms），盘面在动，取消这次点击 (${best.point.x},${best.point.y})，重扫")
                                BoardMemory.cancelPick()
                                delay(SLIDE_RETRY_MS); continue
                            }
                            AppLog.w("点击", "点前复核：画面仍在变 ${"%.1f".format(chk.first * 100)}%，已连续取消 $preTapAborts 次，放行")
                        } else if (chk != null && preTapAborts > 0) {
                            AppLog.d("点击", "点前复核：重扫后画面稳定（变化 ${"%.1f".format(chk.first * 100)}%，复核 ${chk.second}ms），放行")
                        }
                        preTapAborts = 0
                        lastTapFrameSig = refSig
                        val n = doTap(best.rule, best.point, maxClicks, clicks, best.score, best.count)
                        if (n < 0) { stop(); return@launch }
                        clicks = n
                        acted = true
                    }
                }
                if (acted) {
                    lastActAt = System.currentTimeMillis()
                    if (overlayHiddenForZero && !BoardOverlay.isShowing()) {
                        overlayHiddenForZero = false
                        withContext(Dispatchers.Main) { BoardOverlay.show(ctx) }
                        AppLog.i("悬浮棋盘", "棋盘上又有怪了，重新打开悬浮棋盘")
                    }
                } else {
                    // 0.90：这一轮没找到怪就先收窗，不等 25 秒停机。滑动/半截帧/点前复核的 continue
                    // 不进这里（怪还在），所以到这里就是整盘真没找到怪。
                    if (!overlayHiddenForZero) {
                        overlayHiddenForZero = true
                        BoardOverlay.hide()
                        AppLog.i("悬浮棋盘", "这一轮没找到怪，先收起悬浮棋盘（Runner 继续等 25 秒确认战斗结束）")
                    }
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
                BoardOverlayState.post(BoardSnapshot(running = false))   // 0.67：悬浮棋盘显示“未运行”
                BoardOverlay.hide()   // 0.70：一局结束自动收回关闭悬浮棋盘
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
        val prevTapAt = lastTapAt
        val tapStart = System.currentTimeMillis()
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
        // 0.48：给“点后整伙落空”诊断：选点→点击耗时、距上次点击多久（只扎堆点击有选点时刻）
        if (cluster != null && pickDecidedAt > 0) BoardMemory.noteTapTiming(tapStart - pickDecidedAt, if (prevTapAt > 0) pickDecidedAt - prevTapAt else -1L)
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

    // ───────── 0.57 邻格补认 ─────────
    /** 总开关：false = 整段关掉，回到 0.56 的行为。 */
    private const val ADJ_PROMOTE = true
    /** 候选格底色与同伙已认出成员底色的色度距离上限。实测（亮帧）同种怪底色散布 ≤0.015，不同种 ≥0.18（最近的蝎子/蛇 0.051），取 0.03。 */
    private const val ADJ_TINT_TOL = 0.03

    private fun latticeBiasOf(name: String): Int {
        val hist = latticeBias[name] ?: return 0
        return if (hist.size >= 10) hist.sorted()[hist.size / 2] else 0
    }

    /** 0.57 邻格补认：某规则 X 已认出一伙，旁边（上下左右）有一格 X 的模板分落在近阈值窗里（Matcher.Near，
     *  即 [阈值−0.08, 阈值)），而且这格底色和 X 已认出成员的底色一致（色度距离 ≤ ADJ_TINT_TOL），
     *  就把它并入那一伙；新并入的格再当邻居，递归。几道闸：
     *   ① 只用 findBoardComponents 已经算好的 near，不额外跑模板匹配；
     *   ② 邻接：必须贴着 X 已认出（或已并入）的格；
     *   ③ 底色：取这一帧里 X 成员自己的底色当参照（不用学习值，压暗/发白帧也不受影响；发白/压暗盖层帧整段跳过）。
     *      格子本身没染色（颜色辅助没开 / 空地）= 不并；
     *   ④ 别的规则在同一格的近阈值分 ≥ X 的 = 不并（第一名不是 X）；
     *   ⑤ 已被任何规则认走的格不动。
     *  并入格的坐标取格中心 + 该规则学到的 y 偏差（和滑动闸门同口径，不会因为近阈值峰位置漂而触发闸门）。 */
    private fun promoteNear(frame: Bitmap, scans: List<RuleScan>): List<RuleScan> {
        if (!ADJ_PROMOTE || scans.none { it.comps.isNotEmpty() && it.near.isNotEmpty() }) return scans
        if (BoardMemory.frameVeiled(frame)) return scans
        val taken = HashSet<Pair<Int, Int>>()
        for (s in scans) for (c in s.comps) for (p in c.cells.ifEmpty { listOf(c.point) }) taken.add(Pair(colOf(p.x), rowOf(p.y)))
        val nearAt = HashMap<Pair<Int, Int>, ArrayList<Pair<String, Double>>>()
        for (s in scans) for (n in s.near) nearAt.getOrPut(Pair(colOf(n.x), rowOf(n.y))) { ArrayList() }.add(Pair(s.rule.name, n.score))
        var total = 0
        val out = scans.map { s ->
            if (s.comps.isEmpty() || s.near.isEmpty()) return@map s
            // 0.57 修正（助手核验 Claude 补丁时改）：下限就是近阈值窗的下沿，不再另取 max(0.56, …)——
            // max(0.56, …) 会让史莱姆的窗变空（史莱姆阈值就是 0.56，near 全 < 0.56），而史莱姆正是漏检最多的类型；
            // 空地由底色闸挡（cellTint 为 null = 不并），分数下限不必再替它把关。
            val floor = effThr(s.rule) - 0.08
            val pts = ArrayList<Point>()
            val scs = ArrayList<Double>()
            val members = HashSet<Pair<Int, Int>>()
            for (c in s.comps) {
                val cs = c.cells.ifEmpty { listOf(c.point) }
                for ((k, p) in cs.withIndex()) {
                    pts.add(p); scs.add(c.scores.getOrNull(k) ?: c.score); members.add(Pair(colOf(p.x), rowOf(p.y)))
                }
            }
            val tints = members.mapNotNull { (c, r) -> BoardMemory.cellTint(frame, c, r) }
            if (tints.isEmpty()) return@map s
            val ref = DoubleArray(3) { k -> tints.map { it[k] }.sorted()[tints.size / 2] }
            val cand = HashMap<Pair<Int, Int>, Double>()
            for (n in s.near) {
                val cell = Pair(colOf(n.x), rowOf(n.y))
                if (n.score < floor || cell in taken || cell.second < -1 || cell.second > 6) continue
                if (nearAt[cell].orEmpty().any { it.first != s.rule.name && it.second >= n.score }) continue
                if (n.score > (cand[cell] ?: 0.0)) cand[cell] = n.score
            }
            if (cand.isEmpty()) return@map s
            val tintDistOf = HashMap<Pair<Int, Int>, Double>()   // 每格只取一次底色；没染色 = 无穷大
            val added = ArrayList<Triple<Pair<Int, Int>, Double, Double>>()   // (格, 模板分, 底色距离)
            var grew = true
            while (grew) {
                grew = false
                for ((cell, sc) in cand.entries.toList()) {
                    if (cell in members) continue
                    if (members.none { Math.abs(it.first - cell.first) + Math.abs(it.second - cell.second) == 1 }) continue
                    val d = tintDistOf.getOrPut(cell) {
                        val t = BoardMemory.cellTint(frame, cell.first, cell.second)
                        if (t == null) Double.MAX_VALUE else BoardMemory.tintDist(t, ref)
                    }
                    if (d > ADJ_TINT_TOL) continue
                    members.add(cell); added.add(Triple(cell, sc, d)); cand.remove(cell); grew = true
                }
            }
            if (added.isEmpty()) return@map s
            for ((cell, sc, d) in added) {
                val base = BoardMemory.cellCenter(cell.first, cell.second)
                pts.add(Point(base.x, base.y + latticeBiasOf(s.rule.name))); scs.add(sc)
                AppLog.w("匹配", "邻格补认：「${s.rule.name.removePrefix("守卫-")}」第 ${cell.first + 1} 列第 ${cell.second} 排（模板分 ${"%.3f".format(sc)} < 阈值 ${"%.2f".format(effThr(s.rule))}，贴着同类已认出的怪、底色距离 ${"%.3f".format(d)} ≤ $ADJ_TINT_TOL）并入伙")
            }
            total += added.size
            s.copy(comps = Matcher.regroup(pts, scs))
        }
        if (total > 0) BoardMemory.noteAdjPromoted(total)
        return out
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

    /** 棋盘签名（去临界识别：分低于阈值+0.05 的不进签名，防临界抖动）。供“连续一字不差”停机判断复用（点后签名段用）。 */
    private fun boardSig(all: List<Cand>): String {
        val sigComps = all.filter { it.comp.score >= effThr(it.rule) + 0.05 }
        if (sigComps.isEmpty()) return ""
        return sigComps.sortedWith(compareBy({ it.rule.name }, { it.comp.point.y }, { it.comp.point.x }))
            .joinToString(";") { "${it.rule.name}${it.comp.size}@${it.comp.point.x},${it.comp.point.y}" }
    }

    /** 0.80：格子级棋盘签名（只比每格什么怪，不比精确坐标与分数）。0.78 的 boardSig 直接用 comp.point 原始匹配坐标，
     *  同一只怪同格能差 11 像素（79 局第 5 排 2710 vs 2721），冻盘上签名凑不满 5 轮一字不差；格子吸附（格距 240）
     *  对 ±11 像素免疫，冻盘上格子签名稳。供上移等待内冻住判断用，沿用 boardSig 的去临界过滤（分低于阈值+0.05 不进）。 */
    private fun cellSig(all: List<Cand>): String {
        val cells = HashSet<String>()
        for (cand in all) {
            if (cand.comp.score < effThr(cand.rule) + 0.05) continue
            val pts = cand.comp.cells.ifEmpty { listOf(cand.comp.point) }
            for (p in pts) cells.add("${cand.rule.name}@${colOf(p.x)},${rowOf(p.y)}")
        }
        if (cells.isEmpty()) return ""
        return cells.sorted().joinToString(";")
    }

    /** 扎堆规则选点（0.19 期限优先）：
     *  1) 先看“点完它之后，剩下的伙还能不能全在撞墙前清完”（BoardMemory.horizonAfterKill）；余量封顶 HZ_CAP 后再比；
     *  2) 余量相同比只数；再同则 y 小（更靠墙）；再同比相似度。
     *  另外检测“点击没效果”（棋盘连续一字不差）并自动停。 */
    private suspend fun matchClusterRound(ctx: Context, frame: Bitmap, clusterRules: List<Rule>): ClusterPick? {
        val t0 = System.nanoTime()
        // 0.81 Frida 棋盘优先：先读 logcat 里的 BOARD-GRID，有就直接用，跳过模板扫描；读不到回落模板。
        val fridaPairs = FridaBoard.readComps()
        val useFrida = fridaPairs.isNotEmpty()
        val all = ArrayList<Cand>()
        val counts = ArrayList<String>()
        var verified = ""
        var tpls: List<Matcher.Tpl?> = emptyList()
        var scans: List<RuleScan> = emptyList()
        var t1 = t0
        var t2 = t0
        if (useFrida) {
            for ((name, comp) in fridaPairs) {
                val r = clusterRules.firstOrNull { it.name == name }
                    ?: Rule(id = "frida", name = name, type = "image", threshold = 0.0, delayMs = 600, clusterFirst = true)
                all.add(Cand(r, comp))
            }
            val n = all.sumOf { it.comp.size }
            counts.add("Frida $n 只")
            AppLog.d("Frida", "棋盘来自 Frida：$n 只，${all.size} 伙")
        } else {
            val board: Matcher.BoardView? = try { Matcher.prepareBoard(frame) } catch (e: Exception) {
                AppLog.e("匹配", "棋盘裁剪失败，本轮走旧路径", e); null
            }
            tpls = clusterRules.map { if (board != null) getTpl(it) else null }
            t1 = System.nanoTime()
            scans = try {
                coroutineScope {
                    clusterRules.mapIndexed { i, r -> async(Dispatchers.Default) { scanRule(frame, board, r, tpls[i]) } }.awaitAll()
                }
            } finally {
                board?.release()
            }
            if (board != null && scans.all { it.comps.isEmpty() }) {
                // 零命中复核：整轮什么都没找到时用旧路径（全分辨率）再扫一遍，既防快路径漏识别，也量出漏了多少
                val legacy = clusterRules.map { scanRule(frame, null, it, null) }
                val n = legacy.sumOf { s -> s.comps.sumOf { it.size } }
                if (n > 0) AppLog.w("匹配", "零命中复核：快路径整轮 0 只，旧路径（全分辨率）却找到 $n 只——快路径漏识别，请把这行和当时截图发我")
                else verified = "（旧路径复核也是 0）"
                scans = legacy
            }
            scans = resolveConflicts(scans)
            scans = promoteNear(frame, scans)   // 0.57 邻格补认：近阈值格贴着同类已认出的怪、底色一致 → 并入那一伙
            t2 = System.nanoTime()
            for (s in scans) {
                if (s.note != null) { counts.add("${s.rule.name} ${s.note}"); continue }
                if (s.comps.isEmpty()) { counts.add("${s.rule.name} 0只"); continue }
                val b = s.comps.sortedWith(compareByDescending<Matcher.Comp> { it.size }.thenBy { it.point.y }).first()
                counts.add("${s.rule.name} ${b.size}只(y${b.point.y})")
                for (c in s.comps) all.add(Cand(s.rule, c))
            }
        }
        val summary = "本轮比数量：${counts.joinToString(" / ")}"
        if (all.isEmpty()) {
            AppLog.d("匹配", "$summary → 都没找到$verified")
            BoardMemory.onZeroBoard()   // 0.45：整盘全零（清完这一波 / 结算屏），待确认的点击按已发生算
            BoardOverlayState.post(BoardSnapshot(running = true))   // 0.67：悬浮棋盘清盘
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
        // 0.47 半截帧闸门：刚点过，这帧里被点的伙已消、其余怪还在原排、也没有新怪 = 消除动画播完而整盘还没上移。
        // 在这帧上选点，点击落下时盘面刚好上移，会点到下面一排的邻居或空地（35 局 15 次作废 + 一半的点没清掉目标）。
        // 等上移完成再扫；不进签名/预测/选点，也不作废上次点击的记消。
        val staleRaw = if (staleRetries < STALE_MAX_RETRY) BoardMemory.unshiftedMatch(all.map { it.rule.name to it.comp }) else null
        // 0.48：点后等满 SETTLE_TRIES 帧画面一点没变 = 冻住（终局 / 点击没被接收）。半截帧里被点的伙已消，画面一定有变，所以这不是半截帧，不等上移。
        val stale = if (settleUnchanged) null else staleRaw
        if (staleRaw != null && stale == null && staleRetries == 0) {
            AppLog.d("匹配", "点后 ${SETTLE_TRIES} 帧画面都没变：整盘冻住，不是半截帧（原排吻合 ${staleRaw.first}/${staleRaw.second}），跳过上移等待")
            frozenRounds++
            if (frozenRounds >= 2) {
                AppLog.w("匹配", "连续 $frozenRounds 轮棋盘冻住：点击没有触发施法，战斗已结束，自动停止")
                noEffectStop = true
                return null
            }
        }
        if (stale != null) {
            staleRetries++
            staleFlag = true
            if (staleRetries == 1) AppLog.d("匹配", "点后画面还没上移：被点的伙已消，其余怪仍在原排（原排吻合 ${stale.first}/${stale.second}，上移后吻合 ${stale.third}），等整盘上移再扫")
            // 0.80：上移等待里格子也一字不差就计数，满 5 轮（约 2.5 秒）还没动 = 冻住，战斗已结束，不必等满 12 次。
            // 0.78 用 boardSig（原始匹配坐标）失败：79 局同一只怪同格能差 11 像素，签名凑不满 5 轮；格子级 cellSig 对这抖免疫。
            val sSig = cellSig(all)
            if (sSig.isNotEmpty()) {
                if (sSig == staleSig) staleSame++ else { staleSig = sSig; staleSame = 0 }
                if (staleSame >= STALE_FROZEN_ROUNDS) {
                    AppLog.w("匹配", "上移等待中格子连续 ${staleSame + 1} 轮一字不差：盘面冻住，战斗已结束，自动停止")
                    noEffectStop = true
                    return null
                }
            } else staleSame = 0
            return null
        }
        if (staleRetries > 0) AppLog.d("匹配", "上移等待结束：重试 $staleRetries 次" + if (staleRetries >= STALE_MAX_RETRY) "（到上限，按当前帧处理）" else "")
        staleRetries = 0
        staleSig = ""; staleSame = 0
        // 棋盘签名：施法后整盘会上移一行，签名必变；连续一样 = 点击没触发施法。
        // 签名里去掉贴着阈值的临界识别（0.19 实测：蛇 0.71~0.73 对 0.7 阈值，一轮有一轮没，
        // 签名每轮都变、无效果停永远触发不了，才会在结算画面上空点 7 轮）。临界只影响签名，不影响选目标。
        val roundSig = boardSig(all)
        if (roundSig.isNotEmpty()) {
            if (roundSig == lastSig) sameRounds++ else sameRounds = 0
            lastSig = roundSig
            if (sameRounds >= 2) {
                AppLog.w("匹配", "连续 ${sameRounds + 1} 轮棋盘一字不差：点击没有触发施法，战斗可能已结束（或城墙已破），自动停止")
                noEffectStop = true
                return null
            }
        }
        val compsAll = all.map { it.rule.name to it.comp }
        // 0.45：漏数幽灵伙。本帧不是上一轮预测的延续（结算屏 / 新一波）时一律不信漏数。
        val detectedCells = all.flatMap { it.comp.cells.ifEmpty { listOf(it.comp.point) } }
            .map { Pair(colOf(it.x), rowOf(it.y)) }.toSet()
        val liveFrame = BoardMemory.continuesPrediction(compsAll) && !BoardMemory.frameVeiled(frame)   // 0.45b：发白/压暗盖层帧也不算 live
        if (liveFrame) { frozenRounds = 0; staleSig = ""; staleSame = 0 }   // 0.58：活帧说明棋盘在推进，冻住计数清零（0.78：上移等待冻住计数也清零）
        val detectedAtWall = all.any { BoardMemory.minRowOf(it.comp) <= -1 }
        // 0.49：幽灵伙改为决策前就看这一帧的漏数，只认有旁证的（预测里有怪 / 上轮审计在下一排也有，见 confirmedLeakComps）。
        // 有旁证的 -1 排幽灵伙不再因“已识别伙在 -1 排”被剔除：它们是上一帧还认得出的怪，只是这一帧模板失手，不是可能的误报。
        // 36 局 13:51:04：已识别 1 只在 -1 排 + 3 只幽灵在 -1 排 → 点谁都越线，应当点只数最大的伙。
        // 0.77：diagTpls 提前到这里定义（后面 onRound 复用），供 confirmedLeakComps 现算漏数格最佳分。
        val diagTpls = clusterRules.mapIndexedNotNull { i, r -> tpls[i]?.let { r.name to it } }
        val ghosts = if (useFrida) emptyList() else if (liveFrame) BoardMemory.confirmedLeakComps(frame, detectedCells, diagTpls) else emptyList()
        if (ghosts.isNotEmpty()) {
            AppLog.d("匹配", "决策前漏数幽灵伙 ${ghosts.size} 个（底色有怪、模板没检出、有旁证）：" + ghosts.joinToString("；") { (name, c) ->
                name.removePrefix("漏数-").removePrefix("守卫-") + c.cells.joinToString("、", "（", "）") { "第 ${colOf(it.x) + 1} 列第 ${rowOf(it.y)} 排" }
            } + "，进期限余量")
        }
        val evs = all.map { Ev(it, BoardMemory.horizonAfterKill(compsAll + ghosts, it.comp)) }
        val ranked = evs.sortedWith(
            compareByDescending<Ev> { minOf(it.hz, HZ_CAP) }
                .thenByDescending { it.cand.comp.size }
                .thenBy { it.cand.comp.point.y }
                .thenByDescending { it.cand.comp.score }
        )
        // 0.43 本地最优解：删 AI 判点，改调 Solver.pick（纯本地、无网络）。
        // 期限规则首选仍算出来，只用来在日志里和求解器的选择对照。
        var pickEv = ranked.first()
        val solverEval = Solver.pick(compsAll, ghosts)
        if (solverEval != null) {
            val solverEv = evs.first { it.cand.comp === solverEval.comp }
            if (solverEv.cand.comp !== pickEv.cand.comp) {
                val deadlineEval = Solver.eval(compsAll, pickEv.cand.rule.name, pickEv.cand.comp, ghosts)
                AppLog.i("最优解", "最优解改点：期限规则点「${pickEv.cand.rule.name.removePrefix("守卫-")}」${pickEv.cand.comp.size}只（两步${deadlineEval.twoStep}＋合群${deadlineEval.cluster}），改点「${solverEval.ruleName.removePrefix("守卫-")}」${solverEval.comp.size}只（两步${solverEval.twoStep}＋合群${solverEval.cluster}）")
                pickEv = solverEv
            } else {
                AppLog.d("最优解", "最优解和期限规则一致，点「${pickEv.cand.rule.name}」${pickEv.cand.comp.size}只，两步值${solverEval.twoStep}＋合群${solverEval.cluster}")
            }
        }
        // 0.51 落空补点：上轮整伙原位仍在、一格没清（总落空诊断已记下那伙）。这轮若它还在盘上
        // （上移一格后，行号 −1；没上移也认），且已到 0 排及上（再不点就撞墙），优先补点它一次，
        // 不走求解器——那一轮已经白丢了，别再让同一伙白白越线。找不到就当它已散伙，不追。
        var retryFired = false
        val missed = BoardMemory.takeMissedCells()
        if (missed.isNotEmpty()) {
            val missedType = missed.first().type
            val pts = missed.flatMap { listOf(BoardMemory.cellCenter(it.col, it.row - 1), BoardMemory.cellCenter(it.col, it.row)) }.toSet()
            val retryCand = all.firstOrNull { it.rule.name == missedType && it.comp.cells.any { p -> p in pts } }
            if (retryCand != null && BoardMemory.minRowOf(retryCand.comp) <= 0) {
                val retryEv = evs.first { it.cand.comp === retryCand.comp }
                if (retryEv.cand.comp !== pickEv.cand.comp) {
                    AppLog.w("匹配", "落空补点：上轮「${missedType.removePrefix("守卫-")}」${missed.size}只整伙没打中，这轮它还在（第${BoardMemory.minRowOf(retryCand.comp)}排），优先补点它（本来要点「${pickEv.cand.rule.name.removePrefix("守卫-")}」${pickEv.cand.comp.size}只）")
                } else {
                    AppLog.d("匹配", "落空补点：上轮落空的「${missedType.removePrefix("守卫-")}」${missed.size}只这轮正好就是首选，照常点它")
                }
                pickEv = retryEv
                retryFired = true
                BoardMemory.noteRetry()
            } else if (retryCand == null) {
                AppLog.d("匹配", "落空补点取消：上轮落空的「${missedType.removePrefix("守卫-")}」${missed.size}只已不在盘上（散伙/被清），按正常决策")
            } else {
                AppLog.d("匹配", "落空补点暂缓：上轮落空的「${missedType.removePrefix("守卫-")}」${missed.size}只还在第${BoardMemory.minRowOf(retryCand.comp)}排，不够急，按正常决策")
            }
        }
        // 0.44 贴墙漏数代点（按伙）：上一轮漏数映射到当前排后按上下左右相邻成伙，选最大的一伙；
        // 落点取伙内 -1 排最佳分最高的那格（最佳分是 auditWall 逐格诊断的实测分，见 missBestScore；
        // -1 排优先于分数：墙区怪最佳分只有 0.38–0.47，落在空盘噪声带里还常平手，分差说明不了
        // 什么，而 -1 排不点下一施法就撞墙；没分时退回 -1 排最左格）。
        // 触发条件不变：漏数伙非空、且求解器 pick 不在 -1 排时才代点。
        // Comp size 仍记 1：实际清掉几只取决于该格真怪的同种连通伙，伙格数 ≠ 清掉数；
        // 记成伙格数会像 33 局那样把记消吹高（记消 50 对结算 29）。伙格数只写进日志。
        var wallFallback: Point? = null
        var wallFallbackCell: Pair<Int, Int>? = null
        var wallFallbackGroup: List<Pair<Int, Int>> = emptyList()
        var origPickDesc = ""
        // 0.45：只认含 -1 排格的漏数伙（wallMissFallbackGroups）；不连续帧不代点。
        // 守卫改为“已识别的伙里没有 -1 排的”（0.44 是 pick 不在 -1 排）：已识别伙在 -1 排时，漏数伙（-1 排）和它是两伙，
        // 一次施法只能清一伙，点谁另一伙都越线——保留“确定存在”的已识别伙，不拿它换一个可能是误报的漏数格。
        // 注意 0.44 的 pick 守卫在有幽灵伙时会失效（人人 hz=-1，求解器不一定点 -1 排伙），所以改成直接看棋盘。
        // 0.49：代点候选 = 含 -1 排格的幽灵伙（同上，有旁证）；伙内取格、落点逻辑不变。
        // 0.77：低分漏怪不代点——幽灵伙最佳模板分 < LEAK_CONFIRM_SCORE 时当误报，不进代点候选。
        // 伙的最佳分 = 伙内各格现算最佳分的最大值（存在 Comp.scores 里）；没分数据时不 gate，沿用旧口径。
        val wallGhosts = ghosts.filter { BoardMemory.minRowOf(it.second) <= -1 }
        val fbGhosts = wallGhosts.filter { (_, c) ->
            c.scores.isEmpty() || (c.scores.maxOrNull() ?: 0.0) >= BoardMemory.LEAK_CONFIRM_SCORE
        }
        if (wallGhosts.size > fbGhosts.size) {
            AppLog.d("匹配", "贴墙漏数代点：${wallGhosts.size - fbGhosts.size} 个幽灵伙最佳分 < ${BoardMemory.LEAK_CONFIRM_SCORE}，当误报不代点")
        }
        val fbGroups = fbGhosts
            .map { (_, c) -> c.cells.map { Pair(colOf(it.x), rowOf(it.y)) } }
            .sortedByDescending { it.size }
        if (fbGroups.isNotEmpty() && detectedAtWall) {
            // 0.57：只记不改决策——存下“漏数伙 vs 已识别伙”的只数对照，攒证据看漏数伙什么时候会比实点的伙大。
            // 漏数伙只数口径：类型是预测补出来的（漏数-<类型>）= 这么多格是下限；漏数-? = 不同种的格可能连在一起，是上限。
            val ghostCmp = fbGhosts.maxByOrNull { it.second.size }
            val ghostCmpTxt = if (ghostCmp == null) "" else "；[对照·不改决策] 漏数伙最大 ${ghostCmp.second.size} 格（${ghostCmp.first.removePrefix("漏数-").removePrefix("守卫-")}，" +
                (if (ghostCmp.first == "漏数-?") "类型未知=上限" else "有类型=下限") + "），实点 ${pickEv.cand.comp.size} 只"
            AppLog.w("匹配", "贴墙漏数伙 ${fbGroups.size} 个含 -1 排格，但已识别的伙里也有 -1 排的：一次只能清一伙，点谁都有一伙越线，保留已识别的伙（漏数格：${fbGroups.first().joinToString("、") { "第 ${it.first + 1} 列第 ${it.second} 排" }}）$ghostCmpTxt")
        }
        if (fbGroups.isNotEmpty() && !detectedAtWall && !retryFired) {
            origPickDesc = "${pickEv.cand.rule.name.removePrefix("守卫-")}${pickEv.cand.comp.size}只（第${BoardMemory.minRowOf(pickEv.cand.comp)}排，${hzText(pickEv.hz)}）"
            val group = fbGroups.first()
            wallFallbackGroup = group
            val landing = group.sortedWith(
                compareBy<Pair<Int, Int>> { it.second }
                    .thenByDescending { BoardMemory.missBestScore(it.first, it.second) }
                    .thenBy { it.first }
            ).first()
            wallFallbackCell = landing
            wallFallback = BoardMemory.cellCenter(landing.first, landing.second)
            val cell = wallFallback!!
            pickEv = Ev(Cand(WALL_FALLBACK_RULE, Matcher.Comp(cell, 1, 0.0, ys = listOf(cell.y), cells = listOf(cell))), pickEv.hz)
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
        // 0.54 合群：最终点的那伙留下的可合群数（代点时点的不是已识别的伙，不算）。
        val pickCluster = if (wallFallback == null) Solver.eval(compsAll, pick.rule.name, pick.comp, ghosts).cluster else 0
        if (wallFallback != null) {
            AppLog.w("匹配", "$summary → 贴墙漏数代点：第 ${wallFallbackCell!!.first + 1} 列第 ${wallFallbackCell!!.second} 排（${wallFallback!!.x},${wallFallback!!.y}），同伙漏数 ${wallFallbackGroup.size} 格（${wallFallbackGroup.joinToString("、") { "第 ${it.first + 1} 列第 ${it.second} 排" }}）（模板没认出、底色有怪；期限规则本来点「$origPickDesc」）候选前三：$top3")
        } else {
            AppLog.d("匹配", "$summary → 点「${pick.rule.name}」（连成 ${pick.comp.size} 只，其中贴墙 $wallCnt 只，y${pick.comp.point.y}，相似度 ${"%.3f".format(pick.comp.score)}，${hzText(pickEv.hz)}，合群$pickCluster）候选前三：$top3")
        }
        AppLog.d("性能", "本轮识别 ${(t3 - t0) / 1_000_000}ms（截图转换 ${(t1 - t0) / 1_000_000} / 匹配 ${(t2 - t1) / 1_000_000} / 决策 ${(t3 - t2) / 1_000_000}）")
        if (wallFallback == null && bySize.cand.comp !== pick.comp && bySize.cand.comp.size > pick.comp.size) {
            BoardMemory.noteOverride()
            AppLog.w("匹配", "期限优先：只数最大的「${bySize.cand.rule.name.removePrefix("守卫-")}」${bySize.cand.comp.size}只，点完它 ${hzText(bySize.hz)}；改点「${pick.rule.name.removePrefix("守卫-")}」${pick.comp.size}只（${hzText(pickEv.hz)}）")
        }
        rememberRound(evs, pick, frame)
        val near = scans.flatMap { s -> s.near.map { s.rule.name to it } }
        BoardMemory.onRound(compsAll, pick.comp, pick.rule.name, frame, near, diagTpls)?.let { AppLog.d("记忆", it) }
        // 0.67 悬浮迷你棋盘：每轮把认到的棋盘＋要点的伙发给悬浮窗（只显示，不改决策）。
        BoardOverlayState.post(
            BoardSnapshot(
                cells = all.flatMap { cand ->
                    cand.comp.cells.ifEmpty { listOf(cand.comp.point) }.map { p ->
                        BoardCell(colOf(p.x), rowOf(p.y), cand.rule.name)
                    }
                },
                pickCells = pick.comp.cells.ifEmpty { listOf(pick.comp.point) }
                    .map { Pair(colOf(it.x), rowOf(it.y)) }.toSet(),
                pickName = pick.rule.name,
                pickCount = pick.comp.size,
                running = true
            )
        )
        pendingBoardSig = roundSig
        pickDecidedAt = System.currentTimeMillis()
        return ClusterPick(pick.rule, pick.comp.point, pick.comp.score, pick.comp.size, pick.comp.cells.ifEmpty { listOf(pick.comp.point) })
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
