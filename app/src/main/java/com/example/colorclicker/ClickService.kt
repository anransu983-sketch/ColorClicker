package com.example.colorclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

class ClickService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        AppLog.i("服务", "无障碍服务已连接（可执行点击）")
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { AppLog.w("服务", "无障碍服务被中断") }
    override fun onDestroy() {
        instance = null
        AppLog.i("服务", "无障碍服务已断开")
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: ClickService? = null

        /** 模拟一次点击（带轻微坐标抖动和随机按压时长） */
        suspend fun tap(x: Int, y: Int): Boolean {
            val s = instance
            if (s == null) {
                AppLog.e("点击", "点击 ($x,$y) 失败：无障碍服务实例为空")
                return false
            }
            val jx = x + Random.nextInt(-2, 3)
            val jy = y + Random.nextInt(-2, 3)
            val dur = Random.nextLong(40, 90)
            val path = Path().apply { moveTo(jx.toFloat(), jy.toFloat()) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
            return suspendCancellableCoroutine { c ->
                val ok = s.dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        AppLog.d("点击", "手势完成：点击 ($jx,$jy) 按压 ${dur}ms")
                        if (c.isActive) c.resume(true)
                    }
                    override fun onCancelled(d: GestureDescription?) {
                        AppLog.w("点击", "手势被取消：点击 ($jx,$jy)")
                        if (c.isActive) c.resume(false)
                    }
                }, null)
                if (!ok) {
                    AppLog.e("点击", "dispatchGesture 直接返回失败：点击 ($jx,$jy)")
                    if (c.isActive) c.resume(false)
                }
            }
        }
    }
}
