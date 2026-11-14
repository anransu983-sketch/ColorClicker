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
            Toast.makeText(ctx, "请先开启无障碍服务", Toast.LENGTH_SHORT).show(); return false
        }
        start(ctx.applicationContext); return true
    }

    fun stop() { job?.cancel(); job = null }

    private fun start(ctx: Context) {
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val frame = ScreenGrabber.grab()
                val rules = RuleStore.load(ctx).filter { it.enabled }
                var acted = false
                if (frame != null) {
                    for (r in rules) {
                        val p = match(frame, r) ?: continue
                        if (!ClickService.tap(p.x + r.offsetX, p.y + r.offsetY)) { stop(); return@launch }
                        delay((r.delayMs * Random.nextDouble(0.8, 1.2)).toLong())
                        acted = true
                        break   // 动作后画面已变，重新截屏再判断
                    }
                }
                if (!acted) delay(300)
            }
        }
    }

    private fun match(frame: Bitmap, r: Rule): Point? = try {
        if (r.type == "color") Matcher.findColor(frame, r.colorHex, r.tolerance, r.region)
        else {
            val tpl = tplCache.getOrPut(r.templatePath) { BitmapFactory.decodeFile(r.templatePath) }
            if (tpl == null) null else Matcher.findImage(frame, tpl, r.threshold, r.region)
        }
    } catch (e: Exception) { null }
}
