package com.example.colorclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        /** 按一次系统返回键（用于关结算页这类没有按钮的画面） */
        fun back(): Boolean {
            val s = instance ?: return false
            return try {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                AppLog.e("点击", "返回键失败", e)
                false
            }
        }

        /** 往当前可输入的框里写文字（用前先点一下输入框获得焦点）：返回是否成功。
         *  注意：游戏自绘的输入框可能不暴露给无障碍，找不到框时会记日志说明。 */
        fun inputText(text: String): Boolean {
            val s = instance
            if (s == null) {
                AppLog.e("点击", "输入失败：无障碍服务实例为空")
                return false
            }
            return try {
                val root = s.rootInActiveWindow
                if (root == null) {
                    AppLog.e("点击", "输入失败：拿不到当前窗口")
                    return false
                }
                val node = findEditable(root)
                if (node == null) {
                    AppLog.e("点击", "输入失败：没找到可输入的框（请先点一下输入框；这游戏的输入框可能不暴露给系统）")
                    root.recycle()
                    return false
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok) AppLog.i("点击", "已输入文字「$text」")
                else AppLog.e("点击", "输入失败：系统拒绝设置文字")
                node.recycle()
                if (node !== root) root.recycle()
                ok
            } catch (e: Exception) {
                AppLog.e("点击", "输入异常", e)
                false
            }
        }

        /** 迭代找第一个可编辑节点（找到的节点由调用方 recycle，root 永不回收） */
        private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                if (n.isEditable) {
                    while (stack.isNotEmpty()) stack.removeFirst().recycle()
                    return n
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.add(it) }
                }
                if (n !== root) n.recycle()
            }
            return null
        }
    }
}
