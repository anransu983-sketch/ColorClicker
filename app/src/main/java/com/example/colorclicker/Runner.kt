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
            AppLog.i("服务", "开始运行：${rules.size} 条启用规则 [${rules.joinToString("、") { it.name }}]，循环 ${if (maxClicks > 0) "$maxClicks 次" else "无限"}")
            if (rules.isEmpty()) AppLog.w("服务", "没有启用的规则，不会执行任何点击")
            var frameLogged = false
            var nullLogged = false
            var clicks = 0
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
                for (r in rules) {
                    val p = match(frame, r)
                    if (p == null) {
                        logMiss(r)
                        continue
                    }
                    logHit(r, p)
                    val tx = p.x + r.offsetX
                    val ty = p.y + r.offsetY
                    val ok = ClickService.tap(tx, ty)
                    if (!ok) {
                        AppLog.e("点击", "规则「${r.name}」点击 ($tx,$ty) 失败：无障碍手势被拒或服务断开，停止运行")
                        stop(); return@launch
                    }
                    val wait = (r.delayMs * Random.nextDouble(0.8, 1.2)).toLong()
                    clicks++
                    AppLog.d("点击", "规则「${r.name}」点击 ($tx,$ty) 已发出，点击后等待 ${wait}ms" + if (maxClicks > 0) "（第 $clicks/$maxClicks 次）" else "")
                    delay(wait)
                    if (r.inputText.isNotBlank()) {
                        delay(500)   // 等输入框获得焦点再写字
                        ClickService.inputText(r.inputText)
                    }
                    if (maxClicks > 0 && clicks >= maxClicks) {
                        AppLog.i("服务", "已完成 $maxClicks 次点击，自动停止")
                        stop(); return@launch
                    }
                    acted = true
                    break   // 动作后画面已变，重新截屏再判断
                }
                if (!acted) delay(300)
            }
        }
    }

    private fun logHit(r: Rule, p: Point) {
        val tx = p.x + r.offsetX
        val ty = p.y + r.offsetY
        if (r.type == "color") {
            AppLog.d("匹配", "规则「${r.name}」找色 ${r.colorHex} ±${r.tolerance} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)", tx, ty)
        } else {
            AppLog.d("匹配", "规则「${r.name}」找图 相似度 ${"%.3f".format(Matcher.lastImageScore)} ≥ ${r.threshold} → 命中 (${p.x},${p.y})，点击 ($tx,$ty)", tx, ty)
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
            } else Matcher.findImage(frame, tpl, r.threshold, r.region)
        }
    } catch (e: Exception) {
        AppLog.e("匹配", "规则「${r.name}」匹配异常", e)
        null
    }
}
