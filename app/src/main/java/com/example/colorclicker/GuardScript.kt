package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import kotlinx.coroutines.*

/** 一键守卫城邦（0.18 建，0.20 补打完回挑战页，0.21 补免费没次数改点钻石＋开始挑战防连点，0.25 回退重做）：从挑战页开始自动进场 → 点免费（今日免费次数用完、点了还在就改点钻石×100） → 关玩法介绍 → 点颜色辅助 → 交棒给消消乐跑一局 → 打完回挑战页（棋盘点＋返回键＋面板点轮着试，面板点 (802,1617) 在 19:21/19:56 两次都失败）。
 *  安全线：免费优先；只有免费点了弹窗还在（没次数了）才点钻石×100，绝不一上来就点钻石；一局打完就停，不自动续场。
 *  每一步都先在屏幕上找按钮的图片再点（不是死坐标），找不到就等、超时就停并写日志。
 *  用户 10:54–10:56 日志里的取色/框选没标是哪个按钮，对不上号，所以脚本不依赖那几个坐标，
 *  只认她 10:59 发的四张截图里裁出来的按钮图；她日志里 (1277,478) 那个点正好落在颜色辅助按钮里，对上了。
 *  钻石按钮图是她 2026-11-15 19:07 发的截图裁的（今日剩余次数：0 那张），中心约 (948,2190)。 */
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
            val tplDiamond = assetBitmap(ctx, "tpl_guard_diamond.png")
            val tplClose = assetBitmap(ctx, "tpl_guard_close.png")
            val tplAssist = assetBitmap(ctx, "tpl_color_assist.png")
            val tplSlime = assetBitmap(ctx, "tpl_slime.png")
            val tplPotato = assetBitmap(ctx, "tpl_potato.png")
            if (tplStart == null || tplFree == null || tplDiamond == null || tplClose == null || tplAssist == null) {
                AppLog.e("守卫", "按钮模板缺失，一键守卫停了")
                stop(); return@launch
            }
            AppLog.i("守卫", "一键守卫开始：进场→免费（没次数就点钻石×100）→关介绍→点颜色辅助→开跑（只打一局）")
            BoardMemory.beginBattle(ctx)
            var tappedFree = false
            var tappedFreeAt = 0L
            var tappedDiamond = false
            var diamondWarned = false
            var tappedAssist = false
            var tappedStartAt = 0L
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
                        tappedFreeAt = System.currentTimeMillis()
                        delay(1500); continue
                    }
                }
                // 1b. 免费点了还在 = 今日免费次数用完了（0.21）：2.5 秒后免费按钮还在屏幕上，
                // 说明刚才那下没进场，改点钻石×100（只点一次）；进场成功的话免费按钮早没了，不会走到这。
                if (tappedFree && !tappedDiamond && System.currentTimeMillis() - tappedFreeAt > 2500) {
                    val stillFree = Matcher.findImage(frame, tplFree, 0.8, null)
                    if (stillFree != null) {
                        val d = Matcher.findImage(frame, tplDiamond, 0.8, listOf(700, 2050, 550, 250))
                        if (d != null) {
                            AppLog.i("守卫", "免费点了还在（今日免费次数用完了），改点钻石×100 (${d.x},${d.y})")
                            ClickService.tap(d.x, d.y); tappedDiamond = true
                            delay(1500); continue
                        } else if (!diamondWarned) {
                            AppLog.w("守卫", "免费点了还在，但没找到钻石按钮，再等等")
                            diamondWarned = true
                        }
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
                // 4. 挑战页的开始挑战（只在守卫城邦那行找；0.21：点过一次后 5 秒内不重
                // 点——19:02 那次入场弹窗加载慢，1.5 秒一轮连点了 5 次开始挑战）。
                if (!tappedFree && !tappedAssist) {
                    val s = Matcher.findImage(frame, tplStart, 0.8, listOf(900, 550, 540, 400))
                    if (s != null) {
                        val now = System.currentTimeMillis()
                        if (tappedStartAt == 0L || now - tappedStartAt > 5000) {
                            AppLog.i("守卫", if (tappedStartAt == 0L) "找到守卫城邦开始挑战，点 (${s.x},${s.y})" else "开始挑战还在，隔 5 秒再点一次 (${s.x},${s.y})")
                            ClickService.tap(s.x, s.y)
                            tappedStartAt = now
                            delay(1500); continue
                        }
                    }
                }
                // 5. 辅助点过了 → 交棒给消消乐
                if (tappedAssist) {
                    if (Runner.startBattle(ctx)) {
                        AppLog.i("守卫", "已开跑，守卫脚本交棒给消消乐，等本局打完")
                        while (isActive && Runner.isRunning()) delay(1000)
                        // 打完回挑战页（0.25 重做）：结算页没有按钮，(802,1617) 点面板
                        // 19:21/19:56 两次都失败——面板点不算数。改成三招轮着试：
                        // ① 点变暗棋盘的空地 (720,2500)（结果页常点任意处关闭）；
                        // ② 系统返回键；③ 再点一次 (802,1617) 面板兜底。
                        // 每招后都重新核对是不是回来了（开始挑战按钮出现才算）；只有
                        // 面板棕色 #473106 还在 (802,1617) 才认定仍在结算页、不在就不乱点。
                        fun isBack(): Boolean {
                            val f = ScreenGrabber.grab()
                            return f != null && Matcher.findImage(f, tplStart, 0.8, listOf(900, 550, 540, 400)) != null
                        }
                        fun onSettle(): Boolean {
                            val f = ScreenGrabber.grab() ?: return false
                            if (f.width <= 802 || f.height <= 1617) return false
                            val c = f.getPixel(802, 1617)
                            val dr = Math.abs(((c shr 16) and 255) - 0x47)
                            val dg = Math.abs(((c shr 8) and 255) - 0x31)
                            val db = Math.abs((c and 255) - 0x06)
                            return maxOf(dr, dg, db) <= 12
                        }
                        var back = false
                        var boardTaps = 0
                        var backs = 0
                        var retTaps = 0
                        var tries = 0
                        while (isActive && tries < 5) {
                            delay(1500)
                            if (isBack()) { back = true; break }
                            if (!onSettle()) { tries++; continue }
                            AppLog.i("守卫", "还在结算页，点棋盘 (720,2500) 试着关闭")
                            ClickService.tap(720, 2500); boardTaps++
                            delay(1200)
                            if (isBack()) { back = true; break }
                            AppLog.i("守卫", "还没回，改按返回键")
                            ClickService.back(); backs++
                            delay(1200)
                            if (isBack()) { back = true; break }
                            if (retTaps < 2) {
                                AppLog.i("守卫", "还没回，再点一次面板 (802,1617)")
                                ClickService.tap(802, 1617); retTaps++
                                delay(1200)
                                if (isBack()) { back = true; break }
                            }
                            tries++
                        }
                        AppLog.i("守卫", if (back) "已退回挑战页" else "没回到挑战页（棋盘点 $boardTaps 次＋返回键 $backs 次＋面板点 $retTaps 次都试过了，你看一眼现在是什么画面）")
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
                    AppLog.w("守卫", "60 秒没找到开始挑战/免费/颜色辅助，停了（可能不在挑战页）")
                    BoardMemory.endBattle(ctx)
                    stop(); return@launch
                }
                delay(1000)
            }
        }
    }
}
