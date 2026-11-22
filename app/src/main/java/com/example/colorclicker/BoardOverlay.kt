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
    private val listener: (BoardSnapshot) -> Unit = { snap ->
        headerText?.text = if (!snap.running) "悬浮棋盘 · 未运行"
        else if (snap.pickName.isEmpty()) "悬浮棋盘 · 等待识别"
        else "要点「${snap.pickName.removePrefix("守卫-")}」${snap.pickCount}只"
        boardView?.invalidate()
    }

    fun isShowing() = root != null

    fun show(ctx: Context): Boolean {
        if (root != null) return true
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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 60 }
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
        manager.addView(rootView, params)
        root = rootView; wm = manager; headerText = header; boardView = board
        BoardOverlayState.addListener(listener)
        listener(BoardOverlayState.latest)
        return true
    }

    fun hide() {
        val r = root ?: return
        BoardOverlayState.removeListener(listener)
        try { wm?.removeView(r) } catch (_: Exception) { }
        root = null; wm = null; headerText = null; boardView = null
    }
}
