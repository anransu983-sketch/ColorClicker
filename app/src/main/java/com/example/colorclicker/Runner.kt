package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.random.Random

object Runner {
    private var job: Job? = null
    private val tplCache = HashMap<String, Bitmap?>()
    /** 连续多少轮全不命中就自动停止 */
    private const val AUTO_STOP_MISS_ROUNDS = 10

    fun isRunning() = job?.isActive == true

    /** 扎堆比数量的结果：规则、点、该规则的相似度、能消只数 */
    private data class ClusterPick(val rule: Rule, val point: Point, val score: Double, val count: Int)

    /** 棋盘第 0 排（贴墙）中心 y 与行距。第 0 排的怪下一次施法就会撞墙，第 1 排的还能再等一次 */
    private const val ROW0_Y = 1511
    private const val ROW_STEP = 240
    private fun rowOf(y: Int) = Math.round((y - ROW0_Y).toDouble() / ROW_STEP).toInt().coerceAtLeast(0)

    /** 价值 = 只数 + W0×贴墙排只数 + W1×第二排只数。W0/W1 是要用对照实验调的两个数 */
    private const val W0 = 3.0
    private const val W1 = 0.5
    private fun value(c: Matcher.Comp): Double {
        val ys = if (c.ys.isEmpty()) listOf(c.point.y) else c.ys
        return c.size + W0 * ys.count { rowOf(it) == 0 } + W1 * ys.count { rowOf(it) == 1 }
    }

    /** 点击没效果的检测：连续几轮棋盘签名一字不差就停（日志里 14–17 轮就是这样） */
    private var lastSig = ""
    private var sameRounds = 0
    @Volatile private var noEffectStop = false

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
        // 首次运行时按当前方向建截屏画面（只建一次；方向变了需重启截屏）
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

    private fun start(ctx: Context, maxClicks: Int) {
        job = CoroutineScope(Dispatchers.Default).launch {
            val rules = RuleStore.load(ctx).filter { it.enabled }
            val clusterRules = rules.filter { it.type != "color" && it.clusterFirst }
            val normalRules = rules - clusterRules.toSet()
            AppLog.i("服务", "开始运行：${rules.size} 条启用规则 [${rules.joinToString("、") { it.name }}]，循环 ${if (maxClicks > 0) "$maxClicks 次" else "无限"}" + if (clusterRules.isNotEmpty()) "（扎堆规则每轮比数量再点）" else "")
            if (rules.isEmpty()) AppLog.w("服务", "没有启用的规则，不会执行任何点击")
            lastSig = ""; sameRounds = 0; noEffectStop = false
            BoardMemory.beginBattle(ctx)
            var frameLogged = false
            var nullLogged = false
            var clicks = 0
            var missRounds = 0   // 连续全不命中的轮数
            try {
            while (isActive) {
                val frame = ScreenGrabber.grab()
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
                var acted = false
                // 1. 普通规则按顺序，先命中先点（保持老行为）
                for (r in normalRules) {
                    val p = match(frame, r)
                    if (p == null) {
                        logMiss(r)
                        continue
                    }
                    val n = doTap(r, p, maxClicks, clicks)
                    if (n < 0) { stop(); return@launch }
                    clicks = n
                    acted = true
                    break   // 动作后画面已变，重新截屏再判断
                }
                // 2. 没普通规则命中时，扎堆规则全部比数量再点
                if (!acted && clusterRules.isNotEmpty()) {
                    val best = matchClusterRound(frame, clusterRules)
                    if (noEffectStop) { stop(); return@launch }
                    if (best != null) {
                        val n = doTap(best.rule, best.point, maxClicks, clicks, best.score, best.count)
                        if (n < 0) { stop(); return@launch }
                        clicks = n
                        acted = true
                    }
                }
                if (acted) {
                    missRounds = 0
                } else {
                    missRounds++
                    if (missRounds >= AUTO_STOP_MISS_ROUNDS) {
                        AppLog.i("服务", "连续 $AUTO_STOP_MISS_ROUNDS 轮全不命中，自动停止")
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

    /** 点击一条规则：记日志、点、等、写字、记次数；返回新次数，-1 表示应停止（手势失败或到循环上限）。
     *  score/cluster 由调用方在匹配当刻抓取传入（比数量那轮会改写 Matcher 的共享值，事后读就串了）。 */
    private suspend fun doTap(r: Rule, p: Point, maxClicks: Int, clicks: Int, score: Double? = null, cluster: Int? = null): Int {
        logHit(r, p, score, cluster)
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        val ok = ClickService.tap(tx, ty)
        if (!ok) {
            AppLog.e("点击", "规则「${r.name}」点击 ($tx,$ty) 失败：无障碍手势被拒或服务断开，停止运行")
            return -1
        }
        BoardMemory.noteTap(cluster ?: 1)
        val wait = (r.delayMs * Random.nextDouble(0.8, 1.2)).toLong()
        val n = clicks + 1
        AppLog.d("点击", "规则「${r.name}」点击 ($tx,$ty) 已发出，点击后等待 ${wait}ms" + if (maxClicks > 0) "（第 $n/$maxClicks 次）" else "")
        delay(wait)
        if (r.inputText.isNotBlank()) {
            delay(500)   // 等输入框获得焦点再写字
            ClickService.inputText(r.inputText)
        }
        if (maxClicks > 0 && n >= maxClicks) {
            AppLog.i("服务", "已完成 $maxClicks 次点击，自动停止")
            return -1
        }
        return n
    }

    /** 扎堆规则比价值：每条都扫一遍，按“连成一伙”算 价值 = 只数 + 3×贴墙排只数 + 0.5×第二排只数，取最高的一伙；同分取更靠近城墙的，再同分比相似度。另外检测“点击没效果”（棋盘连续一字不差）并自动停。 */
    private fun matchClusterRound(frame: Bitmap, clusterRules: List<Rule>): ClusterPick? {
        data class Cand(val rule: Rule, val comp: Matcher.Comp)
        val all = ArrayList<Cand>()
        val counts = ArrayList<String>()
        for (r in clusterRules) {
            val tpl = tplCache.getOrPut(r.templatePath) { BitmapFactory.decodeFile(r.templatePath) }
            if (tpl == null) {
                AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                counts.add("${r.name} 模板缺失")
                continue
            }
            val comps = try { Matcher.findImageComponents(frame, tpl, r.threshold, r.region) } catch (e: Exception) {
                AppLog.e("匹配", "规则「${r.name}」匹配异常", e); emptyList()
            }
            if (comps.isEmpty()) {
                counts.add("${r.name} 0只")
                continue
            }
            val best = comps.sortedWith(compareByDescending<Matcher.Comp> { it.size }.thenBy { it.point.y }).first()
            counts.add("${r.name} ${best.size}只(y${best.point.y})")
            for (c in comps) all.add(Cand(r, c))
        }
        val summary = "本轮比数量：${counts.joinToString(" / ")}"
        if (all.isEmpty()) {
            AppLog.d("匹配", "$summary → 都没找到")
            return null
        }
        // 棋盘签名：每一伙的规则/只数/位置。施法后整盘会上移一行，签名必变；连续一样 = 点击没触发施法
        val sig = all.sortedWith(compareBy({ it.rule.name }, { it.comp.point.y }, { it.comp.point.x }))
            .joinToString(";") { "${it.rule.name}${it.comp.size}@${it.comp.point.x},${it.comp.point.y}" }
        if (sig == lastSig) sameRounds++ else sameRounds = 0
        lastSig = sig
        if (sameRounds >= 2) {
            AppLog.w("匹配", "连续 ${sameRounds + 1} 轮棋盘一字不差：点击没有触发施法，战斗可能已结束（或城墙已破），自动停止")
            noEffectStop = true
            return null
        }
        val ranked = all.sortedWith(
            compareByDescending<Cand> { value(it.comp) }
                .thenBy { it.comp.point.y }
                .thenByDescending { it.comp.score }
        )
        val pick = ranked.first()
        val top3 = ranked.take(3).joinToString(" | ") {
            "${it.rule.name.removePrefix("守卫-")}${it.comp.size}只 第${rowOf(it.comp.point.y)}排 价值${"%.1f".format(value(it.comp))}"
        }
        val wallCnt = (pick.comp.ys.ifEmpty { listOf(pick.comp.point.y) }).count { rowOf(it) == 0 }
        AppLog.d("匹配", "$summary → 点「${pick.rule.name}」（价值 ${"%.1f".format(value(pick.comp))}，连成 ${pick.comp.size} 只，其中贴墙 $wallCnt 只，y${pick.comp.point.y}，相似度 ${"%.3f".format(pick.comp.score)}）候选前三：$top3")
        BoardMemory.onRound(all.map { it.rule.name to it.comp }, pick.comp, pick.rule.name)?.let { AppLog.d("记忆", it) }
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
            val tpl = tplCache.getOrPut(r.templatePath) { BitmapFactory.decodeFile(r.templatePath) }
            if (tpl == null) {
                AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                null
            } else if (r.clusterFirst) {
                // 单条扎堆规则按顺序检测时，点它最大那伙最上面那只
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
