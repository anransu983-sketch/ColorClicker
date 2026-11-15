package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import kotlinx.coroutines.*

/** 一键守卫城邦（0.18）：从挑战页开始自动进场 → 点免费 → 关玩法介绍 → 点颜色辅助 → 交棒给消消乐跑一局。
 *  安全线：只点免费入场，绝不点钻石 ×100；一局打完就停，不自动续场（免费次数一天只有 2 次）。
 *  每一步都先在屏幕上找按钮的图片再点（不是死坐标），找不到就等、超时就停并写日志。
 *  用户 10:54–10:56 日志里的取色/框选没标是哪个按钮，对不上号，所以脚本不依赖那几个坐标，
 *  只认她 10:59 发的四张截图里裁出来的按钮图；她日志里 (1277,478) 那个点正好落在颜色辅助按钮里，对上了。 */
object GuardScript {
    private var job: Job? = null
    fun isRunning() = job?.isActive == true

    fun toggle(ctx: Context): Boolean {
        if (isRunning()) {
            stop()
            Toast.makeText(ctx, "一键守卫已停", Toast.LENGTH_SHORT).show()
            return false
        }
        if (ClickService.instance == null) {
            AppLog.e("守卫", "启动失败：无障碍服务未连接，请先开启无障碍服务")
            Toast.makeText(ctx, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return false
        }
        if (ScreenGrabber.reader == null) {
            AppLog.e("守卫", "启动失败：还没截到画面，请先回主界面点「3. 授权截屏并启动」")
            Toast.makeText(ctx, "请先点「3. 授权截屏并启动」", Toast.LENGTH_LONG).show()
            return false
        }
        start(ctx.applicationContext)
        Toast.makeText(ctx, "一键守卫开始：自动进场→点颜色辅助→开跑", Toast.LENGTH_SHORT).show()
        return true
    }

    fun stop() {
        if (isRunning()) AppLog.i("守卫", "一键守卫已停止")
        job?.cancel(); job = null
    }

    private fun assetBitmap(ctx: Context, asset: String): Bitmap? {
        return try {
            ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            AppLog.e("守卫", "按钮模板 $asset 读不到", e)
            null
        }
    }

    private fun start(ctx: Context) {
        job = CoroutineScope(Dispatchers.Default).launch {
            val tplStart = assetBitmap(ctx, "tpl_guard_start.png")
            val tplFree = assetBitmap(ctx, "tpl_guard_free.png")
            val tplClose = assetBitmap(ctx, "tpl_guard_close.png")
            val tplAssist = assetBitmap(ctx, "tpl_color_assist.png")
            val tplSlime = assetBitmap(ctx, "tpl_slime.png")
            val tplPotato = assetBitmap(ctx, "tpl_potato.png")
            if (tplStart == null || tplFree == null || tplClose == null || tplAssist == null) {
                AppLog.e("守卫", "按钮模板缺失，一键守卫停了")
                stop(); return@launch
            }
            AppLog.i("守卫", "一键守卫开始：进场→免费→关介绍→点颜色辅助→开跑（只打一局，不点钻石）")
            BoardMemory.beginBattle(ctx)
            var tappedFree = false
            var tappedAssist = false
            val startMs = System.currentTimeMillis()
            var waitedLog = false
            while (isActive) {
                val frame = ScreenGrabber.grab()
                if (frame == null) {
                    delay(300); continue
                }
                // 1. 免费入场（没点过才找）
                if (!tappedFree) {
                    val p = Matcher.findImage(frame, tplFree, 0.8, null)
                    if (p != null) {
                        AppLog.i("守卫", "找到免费入场，点 (${p.x},${p.y})")
                        ClickService.tap(p.x, p.y); tappedFree = true
                        delay(1500); continue
                    }
                }
                // 2. 玩法介绍右上角 X（只在弹窗那片找；免费按钮优先判过，不会误关入场弹窗）
                val cx = Matcher.findImage(frame, tplClose, 0.75, listOf(900, 850, 500, 450))
                if (cx != null) {
                    AppLog.i("守卫", "找到玩法介绍，关掉 (${cx.x},${cx.y})")
                    ClickService.tap(cx.x, cx.y)
                    delay(1200); continue
                }
                // 3. 颜色辅助（没点过才找）
                if (!tappedAssist) {
                    val a = Matcher.findImage(frame, tplAssist, 0.75, listOf(1100, 300, 340, 400))
                    if (a != null) {
                        AppLog.i("守卫", "找到颜色辅助，点 (${a.x},${a.y})")
                        ClickService.tap(a.x, a.y); tappedAssist = true
                        delay(1500)
                        // 安全检查：点完辅助棋盘还在不在（万一弹了面板盖住棋盘，就停，别瞎点）
                        val f2 = ScreenGrabber.grab()
                        val boardAlive = f2 != null &&
                            ((tplSlime != null && Matcher.findImageComponents(f2, tplSlime, 0.8, null).isNotEmpty()) ||
                                (tplPotato != null && Matcher.findImageComponents(f2, tplPotato, 0.75, null).isNotEmpty()))
                        if (!boardAlive) {
                            AppLog.w("守卫", "点完颜色辅助后棋盘上没找到怪，可能是弹了面板盖住了，停了（你看一眼现在是什么画面）")
                            BoardMemory.endBattle(ctx)
                            stop(); return@launch
                        }
                        AppLog.i("守卫", "颜色辅助点完，棋盘还在，准备开跑")
                        continue
                    }
                }
                // 4. 挑战页的开始挑战（只在守卫城邦那行找）
                if (!tappedFree && !tappedAssist) {
                    val s = Matcher.findImage(frame, tplStart, 0.8, listOf(900, 550, 540, 400))
                    if (s != null) {
                        AppLog.i("守卫", "找到守卫城邦开始挑战，点 (${s.x},${s.y})")
                        ClickService.tap(s.x, s.y)
                        delay(1500); continue
                    }
                }
                // 5. 辅助点过了 → 交棒给消消乐
                if (tappedAssist) {
                    if (Runner.startBattle(ctx)) {
                        AppLog.i("守卫", "已开跑，守卫脚本交棒给消消乐，等本局打完")
                        while (isActive && Runner.isRunning()) delay(1000)
                        delay(2000)
                        val back = ScreenGrabber.grab()?.let { Matcher.findImage(it, tplStart, 0.8, listOf(900, 550, 540, 400)) } != null
                        AppLog.i("守卫", if (back) "已退回挑战页" else "本局打完，还在结算/战斗画面（没回到挑战页）")
                        AppLog.i("守卫", "本局打完，一键守卫停了（要再打一局就再点一次）")
                    } else {
                        AppLog.e("守卫", "消消乐没跑起来（可能无障碍断了或没有启用规则），一键守卫停了")
                        BoardMemory.endBattle(ctx)
                    }
                    stop(); return@launch
                }
                // 6. 什么都没找到，等一等，超时停
                if (!waitedLog) {
                    AppLog.i("守卫", "在等画面：挑战页 / 入场弹窗 / 颜色辅助（60 秒内没出现就停）")
                    waitedLog = true
                }
                if (System.currentTimeMillis() - startMs > 60_000) {
                    AppLog.w("守卫", "60 秒没找到开始挑战/免费/颜色辅助，停了（可能不在挑战页，或今天免费次数用完了）")
                    BoardMemory.endBattle(ctx)
                    stop(); return@launch
                }
                delay(1000)
            }
        }
    }
}
