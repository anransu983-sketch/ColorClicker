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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 全新悬浮棋盘（1.04）：删掉 BoardOverlay.kt 重写。
 *
 * 旧板的问题不在窗口本身（1.02 的单窗＋GONE 经日志和 dump 证实不留孤儿），
 * 在显隐的控制权散在六处（Runner 开跑/空轮/停机、GuardScript 退场、手动开关各调一次 show/hide），
 * 跟屏幕实际状态不同步：末点后屏 5 秒就回挑战页，Runner 点后等上移拖约 10 秒才扫到零轮收窗，
 * 这段时间小窗就挂在挑战页上（用户 2026-11-24 11:37:53/11:38:47/11:39:55 三张截图 EXIF 实证）。
 * 1.03 只在 GuardScript 里又加了一处 hide，还是“多处调显隐”的老路子，用户要求删旧做新。
 *
 * 新设计只有一处能决定显隐：FloatBoard 内部的看屏协程。
 * 战斗中每 500ms 抓一帧看屏幕——挑战页开始按钮或结算标题一出现就收窗，
 * 否则（还在游戏棋盘上）就显示。Runner 只管发快照（内容），再也不调显隐；
 * GuardScript、手动开关也不调显隐。显隐 = 当前屏幕状态的函数，每半秒重算一次，
 * 不存在“某处忘了调 hide”这回事。
 * 窗口本身仍只建一次、GONE 隐藏、服务退出才摘除（这套窗体不留孤儿，已证实）。 */
data class FloatCell(val col: Int, val row: Int, val type: String)

data class FloatSnapshot(
    val cells: List<FloatCell> = emptyList(),
    val pickCells: Set<Pair<Int, Int>> = emptySet(),
    val pickName: String = "",
    val pickCount: Int = 0,
    val running: Boolean = false,
)

/** Runner → 悬浮窗的单向快照通道（最新一条为准，只传内容，不管显隐）。 */
object FloatBoardState {
    @Volatile var latest: FloatSnapshot = FloatSnapshot()
    private val listeners = mutableSetOf<(FloatSnapshot) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    fun post(snap: FloatSnapshot) {
        latest = snap
        val copy = synchronized(listeners) { listeners.toList() }
        if (copy.isEmpty()) return
        mainHandler.post { copy.forEach { it(snap) } }
    }
    fun addListener(l: (FloatSnapshot) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (FloatSnapshot) -> Unit) { synchronized(listeners) { listeners.remove(l) } }
}

private fun floatColorOf(type: String): Int = when {
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

private fun floatLabelOf(type: String): String = when {
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

/** 迷你棋盘：6 列 × 8 排（-1..6 排），彩格＋怪图/首字＋红叉标目标伙；-1 排与 0 排之间画一道墙线。 */
class FloatBoardView(ctx: Context) : View(ctx) {
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
        val snap = FloatBoardState.latest
        val c = cellPx
        val byCell = HashMap<Pair<Int, Int>, FloatCell>()
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
                    var color = floatColorOf(cell.type)
                    if (r == -1) {
                        color = Color.rgb(
                            (Color.red(color) * 0.7f).toInt(),
                            (Color.green(color) * 0.7f).toInt(),
                            (Color.blue(color) * 0.7f).toInt()
                        )
                    }
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.color = color
                    canvas.drawRoundRect(RectF(left + 2f, top + 2f, left + c - 2f, top + c - 2f), 8f, 8f, fillPaint)
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
                        canvas.drawText(floatLabelOf(cell.type), left + c / 2, top + c / 2 + textPaint.textSize * 0.35f, textPaint)
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
        wallPaint.strokeWidth = c * 0.08f
        val wallY = (0 - rows.first) * c
        canvas.drawLine(0f, wallY, width.toFloat(), wallY, wallPaint)
        if (!snap.running) {
            textPaint.textSize = c * 0.5f
            canvas.drawText("未运行", width / 2f, height / 2f, textPaint)
        }
    }
}

/** 全新悬浮棋盘：窗口只建一次，显隐唯一由内部看屏协程决定。 */
object FloatBoard {
    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var headerText: TextView? = null
    private var boardView: FloatBoardView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchJob: Job? = null
    /** 手动总开关（主页按钮 / 窗上的 ×）：关了看屏协程也不显示，开了才由看屏决定。默认开。 */
    @Volatile private var enabled = true
    private var tplStart: Bitmap? = null
    private var tplEnd: Bitmap? = null
    private var tickCount = 0

    private val listener: (FloatSnapshot) -> Unit = { snap ->
        headerText?.text = if (!snap.running) "悬浮棋盘 · 未运行"
        else if (snap.pickName.isEmpty()) "悬浮棋盘 · 等待识别"
        else "要点「${snap.pickName.removePrefix("守卫-")}」${snap.pickCount}只"
        boardView?.invalidate()
    }

    fun isEnabled() = enabled
    fun isShowing() = root?.visibility == View.VISIBLE

    /** 手动总开关。关 → 立刻收窗；开 → 看屏协程接手（战斗中才显示）。 */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) hideWindow()
    }

    /** 开跑时调：建窗（没建过才建）＋启动看屏协程。任何线程可调，没悬浮窗权限返回 false。 */
    fun startWatching(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        if (tplStart == null) {
            tplStart = try { ctx.assets.open("tpl_guard_start.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
        if (tplEnd == null) {
            tplEnd = try { ctx.assets.open("tpl_guard_end.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
        ensureWindow(ctx)
        watchJob?.cancel()
        tickCount = 0
        watchJob = scope.launch {
            while (isActive) {
                watchTick()
                delay(500)
            }
        }
        return true
    }

    /** 停机时调：停看屏协程＋收窗。 */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        hideWindow()
    }

    /** 服务退出时才真正摘窗。平时不调。 */
    fun destroy() {
        stopWatching()
        val r = root ?: return
        try { wm?.removeViewImmediate(r) } catch (_: Exception) { }
        root = null; wm = null; headerText = null; boardView = null
        FloatBoardState.removeListener(listener)
    }

    /** 看屏一拍：挑战页开始按钮或结算标题出现 → 屏已离开游戏，收窗；否则在游戏里，显示（开关开着才显示）。 */
    private fun watchTick() {
        val f = ScreenGrabber.grab() ?: return
        val start = tplStart
        val back = start != null && Matcher.findImage(f, start, 0.8, listOf(900, 550, 540, 400)) != null
        if (back) {
            hideWindow()
            return
        }
        tickCount++
        // 结算标题全屏匹配贵，每 4 拍（约 2 秒）查一次就够；挑战页按钮每拍都查，收窗的主力是它。
        if (tickCount % 4 == 0) {
            val end = tplEnd
            val settled = end != null && Matcher.findImage(f, end, 0.75, null) != null
            if (settled) {
                hideWindow()
                return
            }
        }
        if (enabled) showWindow()
    }

    private fun showWindow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showWindow() }
            return
        }
        val r = root ?: return
        if (r.visibility != View.VISIBLE) {
            r.visibility = View.VISIBLE
            r.alpha = 1f
        }
    }

    private fun hideWindow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideWindow() }
            return
        }
        val r = root ?: return
        if (r.visibility != View.GONE) r.visibility = View.GONE
    }

    /** 只建一次的唯一棋盘窗（顶部居中 y=60，标题栏可拖动，× 为手动总开关）。 */
    private fun ensureWindow(ctx: Context) {
        if (root != null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ensureWindow(ctx) }
            return
        }
        if (root != null) return
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
            setOnClickListener { setEnabled(false) }
        }
        val headRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val board = FloatBoardView(appCtx)
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
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = 0; y = 60 }
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
        manager.addView(rootView, params)
        root = rootView; wm = manager; headerText = header; boardView = board
        FloatBoardState.addListener(listener)
        listener(FloatBoardState.latest)
        AppLog.d("悬浮棋盘", "新悬浮棋盘已建窗（1.04，显隐只由看屏协程决定）")
    }
}
