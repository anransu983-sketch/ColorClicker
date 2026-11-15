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
            var frameLogged = false
            var nullLogged = false
            var clicks = 0
            var missRounds = 0   // 连续全不命中的轮数
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
                    if (best != null) {
                        val (r, p) = best
                        val n = doTap(r, p, maxClicks, clicks)
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
        }
    }

    /** 点击一条规则：记日志、点、等、写字、记次数；返回新次数，-1 表示应停止（手势失败或到循环上限） */
    private suspend fun doTap(r: Rule, p: Point, maxClicks: Int, clicks: Int): Int {
        logHit(r, p)
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        val ok = ClickService.tap(tx, ty)
        if (!ok) {
            AppLog.e("点击", "规则「${r.name}」点击 ($tx,$ty) 失败：无障碍手势被拒或服务断开，停止运行")
            return -1
        }
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

    /** 扎堆规则比数量：每条都扫一遍，返回能消最多那只（规则，点）；数量相同比相似度，再相同按规则顺序 */
    private fun matchClusterRound(frame: Bitmap, clusterRules: List<Rule>): Pair<Rule, Point>? {
        var bestRule: Rule? = null
        var bestPoint: Point? = null
        var bestCount = 0
        var bestScore = -1.0
        val counts = ArrayList<String>()
        for (r in clusterRules) {
            val tpl = tplCache.getOrPut(r.templatePath) { BitmapFactory.decodeFile(r.templatePath) }
            if (tpl == null) {
                AppLog.e("匹配", "规则「${r.name}」模板图读不到：${r.templatePath}")
                counts.add("${r.name} 模板缺失")
                continue
            }
            val p = try { Matcher.findImageCluster(frame, tpl, r.threshold, r.region) } catch (e: Exception) {
                AppLog.e("匹配", "规则「${r.name}」匹配异常", e); null
            }
            val c = if (p == null) 0 else Matcher.lastClusterSize
            val s = Matcher.lastImageScore
            counts.add("${r.name} ${c}只")
            if (p != null && (c > bestCount || (c == bestCount && s > bestScore))) {
                bestRule = r; bestPoint = p; bestCount = c; bestScore = s
            }
        }
        val summary = "本轮比数量：${counts.joinToString(" / ")}"
        if (bestRule == null || bestPoint == null) {
            AppLog.d("匹配", "$summary → 都没找到")
            return null
        }
        AppLog.d("匹配", "$summary → 点「${bestRule.name}」（扎堆 $bestCount 只，相似度 ${"%.3f".format(bestScore)}）")
        return Pair(bestRule, bestPoint)
    }

    private fun logHit(r: Rule, p: Point) {
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        if (r.type == "color") {
            AppLog.d("匹配", "规则「${r.name}」找色 ${r.colorHex} ±${r.tolerance} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)", tx, ty)
        } else {
            AppLog.d("匹配", "规则「${r.name}」找图 相似度 ${"%.3f".format(Matcher.lastImageScore)} ≥ ${r.threshold} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)" + if (r.clusterFirst) "（扎堆 ${Matcher.lastClusterSize} 只）" else "", tx, ty)
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
            } else if (r.clusterFirst) Matcher.findImageCluster(frame, tpl, r.threshold, r.region)
            else Matcher.findImage(frame, tpl, r.threshold, r.region)
        }
    } catch (e: Exception) {
        AppLog.e("匹配", "规则「${r.name}」匹配异常", e)
        null
    }
}
