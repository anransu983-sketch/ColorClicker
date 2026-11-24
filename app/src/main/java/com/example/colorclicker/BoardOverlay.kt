package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 悬浮迷你棋盘（0.67）：仿 PC 辅助 3.0 的棋盘窗——实时显示 bot 认到的棋盘，红叉标出这轮要点的伙。
 *  只显示、不改决策；Runner 每轮发一次快照。注意：悬浮窗会出现在截屏里，默认放在左上角墙上（棋盘区域 y<1100 之外），
 *  别拖到棋盘上，否则 bot 会把小窗当成怪、识别全乱。 */
data class BoardCell(val col: Int, val row: Int, val type: String)

data class BoardSnapshot(
    val cells: List<BoardCell> = emptyList(),
    val pickCells: Set<Pair<Int, Int>> = emptySet(),
    val pickName: String = "",
    val pickCount: Int = 0,
    val running: Boolean = false,
)

/** Runner → 悬浮窗的单向快照通道（最新一条为准）。 */
object BoardOverlayState {
    @Volatile var latest: BoardSnapshot = BoardSnapshot()
    private val listeners = mutableSetOf<(BoardSnapshot) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    fun post(snap: BoardSnapshot) {
        latest = snap
        val copy = synchronized(listeners) { listeners.toList() }
        if (copy.isEmpty()) return
        mainHandler.post { copy.forEach { it(snap) } }
    }
    fun addListener(l: (BoardSnapshot) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (BoardSnapshot) -> Unit) { synchronized(listeners) { listeners.remove(l) } }
}

private fun boardColorOf(type: String): Int = when {
    "土豆" in type -> 0xFFA9713A.toInt()
    "史莱姆" in type -> 0xFF3E7CB1.toInt()
    "蝎子" in type -> 0xFFD97B29.toInt()
    "树" in type -> 0xFF4E7A3A.toInt()
    "蛇" in type -> 0xFF7A5AC8.toInt()
    "花仙" in type -> 0xFF7AC74F.toInt()
    "水母" in type -> 0xFF2FA8A0.toInt()
    "野猪" in type -> 0xFF8A4A3A.toInt()
    "蘑菇" in type -> 0xFFAED581.toInt()
    else -> 0xFF888888.toInt()
}

private fun boardLabelOf(type: String): String = when {
    "土豆" in type -> "土"
    "史莱姆" in type -> "史"
    "蝎子" in type -> "蝎"
    "树" in type -> "树"
    "蛇" in type -> "蛇"
    "花仙" in type -> "花"
    "水母" in type -> "水"
    "野猪" in type -> "野"
    "蘑菇" in type -> "菇"
    else -> "？"
}

/** 迷你棋盘：6 列 × 8 排（-1..6 排），彩格＋首字＋红叉标目标伙；-1 排与 0 排之间画一道墙线。 */
class BoardView(ctx: Context) : View(ctx) {
    private val cellDp = 32f
    private val cellPx get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, cellDp, resources.displayMetrics)
    private val rows = -1..6
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9800.toInt()
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tplCache = HashMap<String, Bitmap?>()

    /** 怪的模板图（assets 里 9 张都有）：按类型取，解一次后缓存；读不到返回 null（画首字兜底）。 */
    private fun tplFor(type: String): Bitmap? {
        val asset = when {
            "土豆" in type -> "tpl_potato.png"
            "史莱姆" in type -> "tpl_slime.png"
            "蝎子" in type -> "tpl_scorpion.png"
            "树" in type -> "tpl_tree.png"
            "蛇" in type -> "tpl_snake.png"
            "花仙" in type -> "tpl_fairy.png"
            "水母" in type -> "tpl_jellyfish.png"
            "野猪" in type -> "tpl_boar.png"
            "蘑菇" in type -> "tpl_mushroom.png"
            else -> return null
        }
        return tplCache.getOrPut(asset) {
            try { context.assets.open(asset).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = cellPx.toInt()
        setMeasuredDimension(c * 6, c * 8)
    }

    override fun onDraw(canvas: Canvas) {
        val snap = BoardOverlayState.latest
        val c = cellPx
        val byCell = HashMap<Pair<Int, Int>, BoardCell>()
        for (cell in snap.cells) {
            if (cell.row in rows && cell.col in 0..5) byCell[Pair(cell.col, cell.row)] = cell
        }
        textPaint.textSize = c * 0.42f
        for (r in rows) {
            for (col in 0..5) {
                val left = col * c
                val top = (r - rows.first) * c
                val cell = byCell[Pair(col, r)]
                if (cell != null) {
                    var color = boardColorOf(cell.type)
                    if (r == -1) {
                        // -1 排在墙后，压暗 30%
                        color = Color.rgb(
                            (Color.red(color) * 0.7f).toInt(),
                            (Color.green(color) * 0.7f).toInt(),
                            (Color.blue(color) * 0.7f).toInt()
                        )
                    }
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.color = color
                    canvas.drawRoundRect(RectF(left + 2f, top + 2f, left + c - 2f, top + c - 2f), 8f, 8f, fillPaint)
                    // 0.68：有模板就画怪的图（按原比例缩进格里、彩框留边），没模板才退回首字
                    val tpl = tplFor(cell.type)
                    if (tpl != null) {
                        val inset = c * 0.08f
                        val availW = c - inset * 2
                        val availH = c - inset * 2
                        val scale = minOf(availW / tpl.width, availH / tpl.height)
                        val dw = tpl.width * scale
                        val dh = tpl.height * scale
                        val dst = RectF(left + (c - dw) / 2, top + (c - dh) / 2, left + (c - dw) / 2 + dw, top + (c - dh) / 2 + dh)
                        canvas.drawBitmap(tpl, null, dst, bitmapPaint)
                    } else {
                        canvas.drawText(boardLabelOf(cell.type), left + c / 2, top + c / 2 + textPaint.textSize * 0.35f, textPaint)
                    }
                } else {
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.color = 0xFF2A2118.toInt()
                    canvas.drawRoundRect(RectF(left + 2f, top + 2f, left + c - 2f, top + c - 2f), 8f, 8f, fillPaint)
                }
                if (Pair(col, r) in snap.pickCells) {
                    xPaint.strokeWidth = c * 0.12f
                    val pad = c * 0.18f
                    canvas.drawLine(left + pad, top + pad, left + c - pad, top + c - pad, xPaint)
                    canvas.drawLine(left + c - pad, top + pad, left + pad, top + c - pad, xPaint)
                }
            }
        }
        // 墙线：-1 排与 0 排之间
        wallPaint.strokeWidth = c * 0.08f
        val wallY = (0 - rows.first) * c
        canvas.drawLine(0f, wallY, width.toFloat(), wallY, wallPaint)
        if (!snap.running) {
            textPaint.textSize = c * 0.5f
            canvas.drawText("未运行", width / 2f, height / 2f, textPaint)
        }
    }
}

/** 悬浮窗管理：显示/隐藏迷你棋盘（WindowManager 悬浮窗，复用 CaptureService 工具栏那套参数）。 */
object BoardOverlay {
    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var headerText: TextView? = null
    private var boardView: BoardView? = null
    // 0.81：hide() 失败时不再丢引用——removeViewImmediate/removeView 抛异常或静默失败后，
    // 旧 view 仍挂在屏幕上、root 却已置空，下次 show() 会另建一个新 view，旧的永远收不回去
    // （用户 2026-11-24 实证：结束后小窗时不时留在挑战页上）。失败时保留引用，靠重试收回。
    // 0.83：0.81 只防了抛异常，没防静默失败——removeViewImmediate 不抛异常也可能没摘掉 view
    // （用户 2026-11-24 07:56 两张截图实证：日志写“已移除”，小窗仍在挑战页上）。
    // 改成以 isAttachedToWindow 为准：摘完还贴着就是没摘掉，保留引用重试。
    private var hideFailed = false
    private var hideRetries = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    // 0.84：登记所有建过的悬浮窗 view，hide() 成功时顺带扫掉已脱离引用的孤儿——
    // 0.83 只能防新孤儿，已经孤儿化的旧 view（root 早被旧版置空）摸不到，
    // 只能靠启动新局/结束时扫一遍全量登记表才能清掉（用户 2026-11-24 08:09 截图：
    // 0.83 下小窗仍挂在挑战页上，hide() 日志却写“已移除”——旧孤儿与新 view 不是同一个）。
    private val allViews = mutableListOf<View>()

    /** 扫掉登记表里所有“不是当前 root、还贴在窗口上”的 view（孤儿），顺带清掉已脱离的登记。 */
    private fun sweepStaleViews(manager: WindowManager) {
        var swept = 0
        for (v in allViews.toList()) {
            if (v == root) continue
            if (!v.isAttachedToWindow) { allViews.remove(v); continue }
            try {
                manager.removeViewImmediate(v)
                if (!v.isAttachedToWindow) { allViews.remove(v); swept++ }
            } catch (_: Exception) {
                // 摘不掉就留着，下次再扫
            }
        }
        if (swept > 0) AppLog.w("悬浮棋盘", "sweep 清掉 $swept 个孤儿 view")
    }

    /** 0.85：直接读 WindowManagerGlobal.mViews（反射），看看到底哪些 view 还贴在窗口上——
     * 0.84 的登记表扫不到的东西，这个能看见。用户 2026-11-24 实证：97 局跑在 0.84 上，
     * hide() 写“已移除”且 root 已空，小窗仍挂在挑战页上——登记表里没有的 view 只有这能查到。 */
    private fun dumpWmViews(tag: String) {
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wmg = wmgClass.getDeclaredMethod("getInstance").invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = mViewsField.get(wmg) as ArrayList<View>
            val sb = StringBuilder("WM dump [$tag]：本进程共 ${views.size} 个悬浮窗 view")
            for ((i, v) in views.withIndex()) {
                sb.append("\n  #").append(i).append(" ").append(v.javaClass.name)
                    .append(" attached=").append(v.isAttachedToWindow)
                    .append(" vis=").append(v.visibility)
                    .append(" isRoot=").append(v == root)
                    .append(" inAll=").append(allViews.contains(v))
            }
            AppLog.w("悬浮棋盘", sb.toString())
        } catch (e: Exception) {
            AppLog.e("悬浮棋盘", "WM dump 失败：${e.message}")
        }
    }
    private val listener: (BoardSnapshot) -> Unit = { snap ->
        headerText?.text = if (!snap.running) "悬浮棋盘 · 未运行"
        else if (snap.pickName.isEmpty()) "悬浮棋盘 · 等待识别"
        else "要点「${snap.pickName.removePrefix("守卫-")}」${snap.pickCount}只"
        boardView?.invalidate()
    }

    fun isShowing() = root != null

    fun show(ctx: Context): Boolean {
        val cur = root
        if (cur != null) {
            // 0.83：root 非空但 view 已不在窗口上（上次 hide() 静默失败后 root 被置空又重建之类），
            // 引用是死的，清掉重建；否则 show() 返回 true 却什么都没显示。
            if (!cur.isAttachedToWindow) {
                AppLog.w("悬浮棋盘", "show() 发现 root 已脱离窗口，清掉引用重建")
                root = null; wm = null; headerText = null; boardView = null
                hideFailed = false; hideRetries = 0
            } else if (hideFailed) {
                // 0.81：上次 hide() 失败留下的孤儿 view 仍在屏幕上，先把它收掉再建新的，
                // 否则新旧两个 view 叠在一起、旧的永远收不回去。
                AppLog.w("悬浮棋盘", "show() 发现上次 hide() 失败留下的 view，先强制收回")
                hide()
                if (root != null) return false
            } else {
                return true
            }
        }
        if (!Settings.canDrawOverlays(ctx)) return false
        val appCtx = ctx.applicationContext
        val manager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val header = TextView(appCtx).apply {
            text = "悬浮棋盘 · 未运行"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 10, 16, 10)
        }
        val close = Button(appCtx).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hide() }
        }
        val headRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val board = BoardView(appCtx)
        val rootView = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC1A1512.toInt())
            addView(headRow)
            addView(board)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        // 0.94：默认放顶部居中——用户 2026-11-24：左上角（x=20）挡住左上角分数了。
        // 原来每局 show() 都重置回 x=20，拖走也没用，下局又挡回来。改居中后默认不挡分数，
        // 仍可按标题拖动（拖动逻辑不变，x 变成相对中心点的偏移）。
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = 60 }
        // 拖动：按住标题文字拖整个窗（关闭按钮仍可点）
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = params.x; sy = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > 10 || abs(e.rawY - dy) > 10) moved = true
                    if (moved) {
                        params.x = sx + (e.rawX - dx).toInt()
                        params.y = sy + (e.rawY - dy).toInt()
                        manager.updateViewLayout(rootView, params)
                    }
                }
            }
            true
        }
        // 0.84：建新窗前先扫一遍登记表，把旧版留下的孤儿清掉（否则新旧叠在一起）。
        sweepStaleViews(manager)
        manager.addView(rootView, params)
        root = rootView; wm = manager; headerText = header; boardView = board
        allViews.add(rootView)
        AppLog.d("悬浮棋盘", "show() v0.85 已建新窗")
        dumpWmViews("show后")
        BoardOverlayState.addListener(listener)
        listener(BoardOverlayState.latest)
        return true
    }

    fun hide() {
        // 0.74：0.70 的 removeView 在 Runner.stop()/finally/GuardScript.stop() 三处调用后小窗仍留着（63 局实证），
        // 疑静默失败；改 removeViewImmediate 立即移除并记日志，下局看日志定性。
        // 0.81：removeViewImmediate/removeView 失败时不再丢引用——失败就保留 root/wm，记 hideFailed，
        // 下次 show()/hide() 再试；成功才置空。否则 view 孤儿化、永远收不回去。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AppLog.d("悬浮棋盘", "hide() 后台线程，已转主线程")
            mainHandler.post { hide() }
            return
        }
        val r = root
        if (r == null) {
            AppLog.d("悬浮棋盘", "hide() 已是空，没什么可收")
            // 0.85：root 已空但用户说小窗还在——直接查 WindowManager 里到底还贴着什么。
            dumpWmViews("hide时root已空")
            return
        }
        BoardOverlayState.removeListener(listener)
        // 0.93：先把 view 藏起来再摘——用户 111 局截图证实，hide() 日志说摘掉了、WM dump 也说
        // 只有 2 个 view，但结算屏上小窗还挂着满盘。removeViewImmediate 的 isAttachedToWindow
        // 可能是假成功、或 Surface 残留让最后一帧继续显示。先 GONE（画不出东西）再摘，双保险。
        r.visibility = View.GONE
        // 0.95：摘之前先把窗口缩成 0x0＋透明——112 局证实 removeViewImmediate＋GONE＋800ms 复查
        // 全套都说干净了，小窗还在挑战页上。缩窗不依赖摘除：就算 WindowManager 静默失败没摘掉，
        // 0x0 的窗口在屏幕上也是 0 像素、画不出东西，等于立马收掉，不用等。
        // 0.97 修：不用 LayoutParams(lp) 拷贝构造（该构造在编译期解析不到），直接改现成的 lp 再 update。
        try {
            val lp = r.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                lp.width = 0
                lp.height = 0
                wm?.updateViewLayout(r, lp)
                AppLog.d("悬浮棋盘", "hide() 已把窗口缩成 0x0（立马收）")
            } else {
                AppLog.w("悬浮棋盘", "hide() 缩窗失败：layoutParams 不是 WindowManager.LayoutParams")
            }
        } catch (e: Exception) {
            AppLog.w("悬浮棋盘", "hide() 缩窗失败：${e.message}")
        }
        r.alpha = 0f
        var removed = false
        try {
            wm?.removeViewImmediate(r)
            // 0.83：removeViewImmediate 不抛异常也可能没摘掉（静默失败），以 isAttachedToWindow 为准。
            if (!r.isAttachedToWindow) {
                AppLog.d("悬浮棋盘", "hide() 已移除（removeViewImmediate）")
                removed = true
            } else {
                AppLog.w("悬浮棋盘", "hide() removeViewImmediate 未抛异常但 view 还贴着，改 removeView")
                try {
                    wm?.removeView(r)
                    if (!r.isAttachedToWindow) {
                        AppLog.d("悬浮棋盘", "hide() 已移除（removeView 兜底）")
                        removed = true
                    } else {
                        AppLog.e("悬浮棋盘", "hide() removeView 后 view 还贴着，保留引用等重试")
                    }
                } catch (e2: Exception) {
                    AppLog.e("悬浮棋盘", "hide() removeView 也失败：${e2.message}，保留引用等下次重试")
                }
            }
        } catch (e: Exception) {
            AppLog.w("悬浮棋盘", "hide() removeViewImmediate 失败：${e.message}，改 removeView")
            try {
                wm?.removeView(r)
                if (!r.isAttachedToWindow) {
                    AppLog.d("悬浮棋盘", "hide() 已移除（removeView 兜底）")
                    removed = true
                } else {
                    AppLog.e("悬浮棋盘", "hide() removeView 后 view 还贴着，保留引用等重试")
                }
            } catch (e2: Exception) {
                AppLog.e("悬浮棋盘", "hide() removeView 也失败：${e2.message}，保留引用等下次重试")
            }
        }
        if (removed) {
            // 0.84：顺带扫掉登记表里的孤儿（旧版留下的），免得“已移除”了小窗还在。
            wm?.let { sweepStaleViews(it) }
            // 0.95：把刚摘的这个也从登记表里清掉（以前 sweep 会跳过 root，它一直赖在表里）。
            synchronized(allViews) { allViews.remove(r) }
            // 0.93：800ms 后再复查一遍——hide() 的“已移除”在 111 局被截图证伪了。
            // 复查直接读 WindowManagerGlobal.mViews：小窗还在里面就再摘一次，并记日志定性。
            val wmCaptured = wm
            val viewCaptured = r
            mainHandler.postDelayed({
                try {
                    val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                    val wmg = wmgClass.getDeclaredMethod("getInstance").invoke(null)
                    val mViewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val views = mViewsField.get(wmg) as ArrayList<View>
                    if (views.any { it === viewCaptured }) {
                        AppLog.w("悬浮棋盘", "hide() 800ms 后复查：小窗还在 WindowManager 里，再摘一次")
                        try { wmCaptured?.removeViewImmediate(viewCaptured) } catch (_: Exception) { }
                        if (!viewCaptured.isAttachedToWindow) AppLog.i("悬浮棋盘", "hide() 复查重摘成功")
                        else AppLog.e("悬浮棋盘", "hide() 复查重摘后 view 还贴着（真·摘不掉）")
                    } else {
                        AppLog.d("悬浮棋盘", "hide() 800ms 后复查：小窗已不在，干净")
                    }
                } catch (e: Exception) {
                    AppLog.e("悬浮棋盘", "hide() 复查失败：${e.message}")
                }
            }, 800)
            root = null; wm = null; headerText = null; boardView = null
            hideFailed = false
            hideRetries = 0
            // 0.85：摘完再查一遍 WindowManager，确认真的干净了。
            dumpWmViews("hide摘完后")
            // 0.86：收窗时把棋盘快照清掉——BoardView 是按 BoardOverlayState.latest 画的，
            // 这份快照不随 view 一起消失；不清空的话，下局 show() 会立刻显示上局最后那块板，
            // 在挑战页入口阶段看起来就像“小窗没收回去”（用户 2026-11-24 实证：99–102 局后
            // 三张截图里小窗还挂在挑战页上，内容分别是上局的“等待识别”“野猪2只”“史莱姆6只”；
            // 而 0.85 的 WM dump 已证实 view 本身每次都从 WindowManager 摘干净了——残留的不是
            // view，是没清掉的快照状态）。清掉后下局开局显示“未运行”空盘，等 Runner 发新快照再填。
            BoardOverlayState.post(BoardSnapshot())
            AppLog.d("悬浮棋盘", "hide() 已清掉棋盘快照，下局开局为空盘")
        } else {
            // 移除失败：保留引用，下次 show()/hide() 会重试；同时 500ms 后再试一次，
            // 免得只靠下次调用才收（用户看到的就是“结束了收不回去”）。最多再试 3 次，
            // 再失败就认了（WindowManager 已坏，重试也没用），避免无限刷日志。
            hideFailed = true
            hideRetries++
            if (hideRetries <= 3) {
                mainHandler.postDelayed({ if (root != null) hide() }, 500)
            } else {
                AppLog.e("悬浮棋盘", "hide() 重试 3 次仍失败，放弃（view 可能已孤儿化）")
            }
        }
    }
}
