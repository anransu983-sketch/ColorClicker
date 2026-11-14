package com.example.colorclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

class ClickService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: ClickService? = null

        /** 模拟一次点击（带轻微坐标抖动和随机按压时长） */
        suspend fun tap(x: Int, y: Int): Boolean {
            val s = instance ?: return false
            val path = Path().apply {
                moveTo((x + Random.nextInt(-2, 3)).toFloat(), (y + Random.nextInt(-2, 3)).toFloat())
            }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, Random.nextLong(40, 90)))
                .build()
            return suspendCancellableCoroutine { c ->
                val ok = s.dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                    override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
                }, null)
                if (!ok && c.isActive) c.resume(false)
            }
        }
    }
}
