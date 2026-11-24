package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一键守卫城邦（0.18 建，0.20 补打完回挑战页，0.21 补免费没次数改点钻石＋开始挑战防连点，0.25 回退重做，0.34 回退再改，0.36 回退时自裁标题图）：
 *  从挑战页开始自动进场 → 点本次免费/免费（免费点了还在就改点本次免费、再改点钻石×100） → 关玩法介绍 → 点颜色辅助 → 交棒给消消乐跑一局 → 打完回挑战页。
 *  0.34：认结算页不再用 (802,1617) 像素——面板是半透明的，底下棋盘颜色透上来，
 *  (802,1617) 在 00:27 那张结算图上是 #41301e（蓝通道差 24），判不到，所以之前每局
 *  都是棋盘点 0 次＋返回键 0 次＋面板点 0 次。改成认「战斗结束」金字标题图
 *  （tpl_guard_end.png，从 00:27 结算截图裁的），认到就按她说的：手势返回退出（返回键优先）。
 *  安全线：免费优先；只有免费点了弹窗还在（没次数了）才点钻石×100，绝不一上来就点钻石；一局打完就停，不自动续场。
 *  每一步都先在屏幕上找按钮的图片再点（不是死坐标），找不到就等、超时就停并写日志。
 *  用户 10:54–10:56 日志里的取色/框选没标是哪个按钮，对不上号，所以脚本不依赖那几个坐标，
 *  只认她 10:59 发的四张截图里裁出来的按钮图；她日志里 (1277,478) 那个点正好落在颜色辅助按钮里，对上了。
 *  0.52：排行榜弹窗里左「免费」今日剩余次数 0、点了没用，脚本只会等钻石×100（
 *  那弹窗里根本没有钻石按钮），四次循环都卡死在这。补右「本次免费」黄按钮模板
 *  （tpl_guard_free_this.png，从她 00:53 截图裁的，与 tpl_guard_free 同尺寸
 *  363×134）：进场先找本次免费，免费点了还在也先试本次免费，再试钻石。 */
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
        // 0.92：进场阶段不再打开悬浮棋盘——用户 2026-11-24 截图证实，0.91 战斗结束已立即收窗，
        // 但一键守卫一开（进场/挑战页阶段）又把悬浮窗打开、顶着上局的棋盘挂在挑战页上，
        // 看着就像“战斗结束了小窗还在”。悬浮棋盘改由 Runner 开跑时再打开（见 Runner.start），
        // 只在战斗进行中显示，挑战页上永远不出现。
        Toast.makeText(ctx, "一键守卫开始：自动进场→点颜色辅助→开跑", Toast.LENGTH_SHORT).show()
        return true
    }

    fun stop() {
        if (isRunning()) AppLog.i("守卫", "一键守卫已停止")
        job?.cancel(); job = null
        // 0.70：一局结束自动收回关闭悬浮棋盘（Runner 还在跑就不收，棋盘还得显示）
        if (!Runner.isRunning()) BoardOverlay.hide()
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
            val tplFreeThis = assetBitmap(ctx, "tpl_guard_free_this.png")
            val tplDiamond = assetBitmap(ctx, "tpl_guard_diamond.png")
            val tplClose = assetBitmap(ctx, "tpl_guard_close.png")
            val tplAssist = assetBitmap(ctx, "tpl_color_assist.png")
            val tplSlime = assetBitmap(ctx, "tpl_slime.png")
            val tplPotato = assetBitmap(ctx, "tpl_potato.png")
            val tplEnd = assetBitmap(ctx, "tpl_guard_end.png")
            if (tplEnd == null) AppLog.w("守卫", "战斗结束标题图 tpl_guard_end.png 读不到，打完回挑战页会认不到结算屏")
            if (tplStart == null || tplFree == null || tplFreeThis == null || tplDiamond == null || tplClose == null || tplAssist == null) {
                AppLog.e("守卫", "按钮模板缺失，一键守卫停了")
                stop(); return@launch
            }
            AppLog.i("守卫", "一键守卫开始：进场→本次免费/免费（没次数就点钻石×100）→关介绍→点颜色辅助→开跑（只打一局）")
            BoardMemory.beginBattle(ctx)
            var tappedFree = false
            var tappedFreeThis = false
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
                // 0.52 优先：排行榜弹窗右「本次免费」黄按钮（只在弹窗下半区找，
                // 挑战页顶上那个小字「本次免费」不是按钮、尺寸也对不上模板）。
                // 左蓝「免费」今日剩余次数 0 时点了没用，先点右边这个。
                if (!tappedFree) {
                    val ft = Matcher.findImage(frame, tplFreeThis, 0.75, listOf(700, 2050, 550, 250))
                    if (ft != null) {
                        AppLog.i("守卫", "找到本次免费入场，点 (${ft.x},${ft.y})")
                        ClickService.tap(ft.x, ft.y); tappedFree = true; tappedFreeThis = true
                        tappedFreeAt = System.currentTimeMillis()
                        delay(1500); continue
                    }
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
                        // 0.52：免费点了还在，先试右边的本次免费（排行榜弹窗里没有钻石按钮），
                        // 再试钻石×100。
                        if (!tappedFreeThis) {
                            val ft = Matcher.findImage(frame, tplFreeThis, 0.75, listOf(700, 2050, 550, 250))
                            if (ft != null) {
                                AppLog.i("守卫", "免费点了还在，改点本次免费 (${ft.x},${ft.y})")
                                ClickService.tap(ft.x, ft.y); tappedFreeThis = true
                                delay(1500); continue
                            }
                        }
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
                        // 0.36 自裁标题图：第 22 局回退 0/0/0——包里的标题图（00:27 结算截图
                        // 裁的、放大回原生）5 轮都没对上。原因未定（放大模糊、那局结算屏长得
                        // 不一样都有可能），但现成帧就在手里：回挑战页之前先从当前帧按结算
                        // 标题的位置自裁一张（原生坐标 305,823,838×251，00:27 图的标题区），
                        // 存成 settle_title_<时间>.png，onSettle() 里原图对不上就拿它再试——
                        // 同一局同一块冻棋盘，底色都一样，对上来的概率比跨局模板高。裁不到
                        // （帧太小/没画面）就当没这招，原逻辑不变。
                        var autoEnd: Bitmap? = null
                        try {
                            val f0 = ScreenGrabber.grab()
                            if (f0 != null && f0.width >= 1143 && f0.height >= 1074) {
                                autoEnd = Bitmap.createBitmap(f0, 305, 823, 838, 251)
                                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                                val outFile = File(ctx.getExternalFilesDir(null), "settle_title_$ts.png")
                                FileOutputStream(outFile).use { autoEnd!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                AppLog.i("守卫", "已自裁结算标题图：${outFile.name}（打完认结算屏时原图对不上就拿它再试）")
                            } else {
                                AppLog.w("守卫", "自裁结算标题图失败：没拿到够大的帧")
                            }
                        } catch (e: Exception) {
                            AppLog.w("守卫", "自裁结算标题图失败：${e.message?.take(80)}")
                        }
                        // 打完回挑战页（0.34 重做）：0.25 的 (802,1617) 像素判结算页
                        // 是坏的——面板半透明、底下棋盘颜色透上来，(802,1617) 在 00:27
                        // 那张结算图上是 #41301e（蓝通道差 24），判不到，所以之前每局
                        // 都是棋盘点 0 次＋返回键 0 次＋面板点 0 次。改成认「战斗结束」
                        // 金字标题图，认到就按她说的：手势返回退出（返回键优先），再核对
                        // 是不是回挑战页（开始挑战按钮出现才算）；返回键不行再点棋盘空地、
                        // 再按返回键、最后点面板 (802,1617) 兜底。
                        fun isBack(): Boolean {
                            val f = ScreenGrabber.grab()
                            return f != null && Matcher.findImage(f, tplStart, 0.8, listOf(900, 550, 540, 400)) != null
                        }
                        fun onSettle(): Boolean {
                            val f = ScreenGrabber.grab() ?: return false
                            if (tplEnd != null && Matcher.findImage(f, tplEnd, 0.75, null) != null) return true
                            if (autoEnd != null && Matcher.findImage(f, autoEnd, 0.75, null) != null) {
                                AppLog.i("守卫", "认到战斗结束（自裁标题图对上了，包里的原图没对上）")
                                return true
                            }
                            return false
                        }
                        var back = false
                        var boardTaps = 0
                        var backs = 0
                        var retTaps = 0
                        var tries = 0
                        while (isActive && tries < 5) {
                            delay(1500)
                            // 0.98：已回到挑战页也算战斗结束，立马收小窗——之前只在认到结算屏那条分支收，
                            // isBack() 先为真就直接 break，onSettle() 一次都没跑，收窗根本没触发。
                            if (isBack()) { BoardOverlay.hide(); back = true; break }
                            if (!onSettle()) { tries++; continue }
                            // 0.96：认到结算屏就立马收小窗——用户 2026-11-24 要求加个识别，战斗结束悬浮棋盘立马关掉。
                            // 这是第二道触发（第一道是 Runner 空轮 hide），结算屏一出现就收，不等 Runner 那边的状态。
                            // 0.95 的缩 0x0 已在 hide() 里，认到就收等于立马消失。
                            BoardOverlay.hide()
                            AppLog.i("守卫", "认到战斗结束，按返回键退出")
                            ClickService.back(); backs++
                            delay(1200)
                            if (isBack()) { back = true; break }
                            AppLog.i("守卫", "返回键没退出，点棋盘空地 (720,2500) 再试")
                            ClickService.tap(720, 2500); boardTaps++
                            delay(1200)
                            if (isBack()) { back = true; break }
                            AppLog.i("守卫", "还没回，再按一次返回键")
                            ClickService.back(); backs++
                            delay(1200)
                            if (isBack()) { back = true; break }
                            if (retTaps < 2) {
                                AppLog.i("守卫", "还没回，点面板 (802,1617) 兜底")
                                ClickService.tap(802, 1617); retTaps++
                                delay(1200)
                                if (isBack()) { back = true; break }
                            }
                            tries++
                        }
                        AppLog.i("守卫", if (back) "已退回挑战页" else "没回到挑战页（返回键 $backs 次＋棋盘点 $boardTaps 次＋面板点 $retTaps 次都试过了，你看一眼现在是什么画面）")
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
