package com.example.colorclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.26 AI 判点（Gemini 免费版）：
 * 每轮把棋盘图（裁成 y≥1100 的棋盘区、缩到宽 720、画上列号/行号）+ 候选清单发给
 * Gemini（Google AI Studio 免费额度），模型只回 JSON：{"pick": 候选编号, "unknown": {col,row,desc} 或 null}。
 * 任何失败（没网、超时、429、格式错、没填 key）都返回 null，调用方退回期限规则，
 * 绝不因为 AI 掉线就卡死。Key 只存在本机 SharedPreferences，不上传别处。
 * 注意：免费额度下发给 Google 的内容可能被用于训练——这里只发游戏棋盘图，没有隐私信息。
 * 模型（0.27）：默认 gemini-3.5-flash-lite（免费 15 次/分钟、每天 500 次，一局约 30 次，
 * 够打十几局）；gemini-3.8-flash 更强但每天只有 20 次（约一局），在主界面模型名里改。
 * 教训：gemini-2.5-flash 已对新用户下线（2026-11-15 第 10 局实测全轮 404），所以模型名做成
 * 可配的，下次 Google 再换名不用重新发版。
 * 0.28（第 11 局教训）：①模型把已知的蛇/树怪又报成未知怪，prompt 写明已知只有 6 种，
 * 只有长得完全不一样的才算未知；②模型有一次选了"点完即越线"的候选（那局随即结束），
 * prompt 加硬规则 + Runner 里加兜底（AI 选越线、期限规则有不越线的就改回期限规则）；
 * ③模型报未知怪时把带列号/行号的棋盘图存成 ai_unknown_时间.jpg，事后能按格子裁模板。
 */
object AiJudge {
    private const val PREFS = "ai_judge"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_ENABLED = "ai_enabled"
    private const val KEY_MODEL = "gemini_model"
    /** 默认模型：免费 15 次/分钟、每天 500 次；3.8-flash 每天只有 20 次，不适合每轮都判 */
    private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private fun endpoint(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    private const val TIMEOUT_MS = 30_000
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** 两次判点至少隔这么久，免得 429：lite 15 次/分钟→4 秒；flash 5 次/分钟→12 秒 */
    private fun minGapMs(model: String) = if (model.contains("lite", ignoreCase = true)) 4_000L else 12_000L
    private var lastJudgeAt = 0L

    fun getApiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun getModel(ctx: Context): String {
        val m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, "") ?: ""
        return if (m.isBlank()) DEFAULT_MODEL else m.trim()
    }

    fun setModel(ctx: Context, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 候选伙（Runner 传进来，已按期限排好序；index 是它在清单里的编号） */
    data class Candidate(
        val index: Int,
        val name: String,
        val size: Int,
        val row: Int,
        val horizon: String,
        val x: Int,
        val y: Int
    )

    /** 模型在图上看到的不在候选清单里的怪（列/排号按图上标的格子） */
    data class Unknown(val col: Int, val row: Int, val desc: String)

    data class Verdict(val pick: Int, val unknown: Unknown?, val raw: String)

    /** 棋盘列中心 x（实测）与排中心 y */
    private val COL_X = intArrayOf(117, 357, 598, 838, 1077, 1317)
    private val ROW_Y = intArrayOf(1511, 1751, 1991, 2231, 2471, 2711, 2951)

    /** 格子中心（原生坐标） */
    fun cellCenter(col: Int, row: Int) = android.graphics.Point(COL_X[col], ROW_Y[row])

    /** 把整帧裁成棋盘区、缩到宽 720，并在图上画列号（顶）/行号（左），模型按格子报位置 */
    fun labeledBoard(frame: Bitmap): Bitmap {
        val top = 1100.coerceAtMost(frame.height - 2)
        val crop = Bitmap.createBitmap(frame, 0, top, frame.width, frame.height - top)
        val w = 720
        val h = (crop.height * w / crop.width.toDouble()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(crop, w, h, true)
        if (crop !== frame) crop.recycle()
        val c = Canvas(small)
        val sx = w / 1440.0
        val sy = h / (frame.height - top).toDouble()
        val paint = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
        val stroke = Paint().apply {
            color = Color.BLACK; textSize = 34f; isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = 5f
        }
        for (col in 0..5) {
            val x = (COL_X[col] * sx).toFloat()
            c.drawText("$col", x - 12f, 40f, stroke)
            c.drawText("$col", x - 12f, 40f, paint)
        }
        for (row in 0..6) {
            val y = ((ROW_Y[row] - top) * sy).toFloat()
            c.drawText("$row", 8f, y + 12f, stroke)
            c.drawText("$row", 8f, y + 12f, paint)
        }
        return small
    }

    private fun buildPrompt(cands: List<Candidate>): String {
        val list = cands.joinToString("\n") { c ->
            "${c.index}. ${c.name}：连成 ${c.size} 只，最上面那只在第 ${c.row} 排（0=贴墙排，-1=贴墙后一格），点完它 ${c.horizon}"
        }
        return """
            这是手机游戏《放置军团》守卫城邦的棋盘。6列（列号0-5写在图顶）×7排（排号0-6写在图左），怪每被点一次全体上移一排，怪占领城墙（排-1再上移一格）战斗结束。点一只怪会同时消掉上下左右连着的同种怪。
            候选（已由程序按只数和危险度排好序，只从里面选）：
            $list
            已知怪只有 6 种：土豆怪、史莱姆、蝎子、树怪、蛇、花仙——候选里没列出的怪才算未知。
            规则：1）绝不能选点完它会越线（余量写着"点完即越线"）的候选，除非所有候选都写着点完即越线；2）先比余量大，再比只数多；3）余量和只数都一样时选位置靠上的。只回 JSON，不要别的字：
            {"pick": <候选编号，0起；没有能点的回 -1>, "unknown": {"col": <列号0-5>, "row": <排号0-6>, "desc": "<看到的不在候选里的怪的样子，如淡蓝水母一只大紫眼睛>"} 或 "unknown": null}
            只有当你确实在图上看到一种和上面 6 种长得完全不一样的怪才填 unknown；只是候选里已有的怪换个说法不算未知，不确定就填 null。
        """.trimIndent()
    }

    /** 模型报未知怪时，把带列号/行号的棋盘图存一份，事后能按格子裁模板。 */
    private fun saveUnknownShot(ctx: Context, frame: Bitmap, unknown: Unknown) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val file = File(ctx.getExternalFilesDir(null), "ai_unknown_$ts.jpg")
            val img = labeledBoard(frame)
            FileOutputStream(file).use { img.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!img.isRecycled) img.recycle()
            AppLog.i("AI判点", "未知怪截图已存：${file.name}（${unknown.desc}，第 ${unknown.col} 列第 ${unknown.row} 排）")
        } catch (e: Exception) {
            AppLog.w("AI判点", "未知怪截图保存失败：${e.message?.take(80)}")
        }
    }

    /** 发一轮判点。成功返回 Verdict；任何失败返回 null（调用方退回期限规则）。 */
    suspend fun judge(ctx: Context, frame: Bitmap, cands: List<Candidate>): Verdict? {
        val key = getApiKey(ctx)
        val model = getModel(ctx)
        if (key.isBlank() || cands.isEmpty()) return null
        // 按模型节流，避免 429
        val gap = System.currentTimeMillis() - lastJudgeAt
        val minGap = minGapMs(model)
        if (gap < minGap) delay(minGap - gap)
        lastJudgeAt = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val img = labeledBoard(frame)
                val baos = ByteArrayOutputStream()
                img.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                if (!img.isRecycled) img.recycle()
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray()
                            .put(JSONObject().put("text", buildPrompt(cands)))
                            .put(JSONObject().put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            })))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("response_mime_type", "application/json")
                        put("temperature", 0.2)
                        put("maxOutputTokens", 400)
                    })
                }
                val conn = (URL(endpoint(model)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                    AppLog.w("AI判点", "Gemini 返回 $code${if (err != null) "：$err" else ""}，退回期限规则")
                    conn.disconnect()
                    return@withContext null
                }
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val text = JSONObject(resp)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")
                val v = JSONObject(text)
                val pick = v.optInt("pick", -1)
                val unknown = if (v.isNull("unknown")) null else v.optJSONObject("unknown")?.let {
                    Unknown(it.optInt("col", -1), it.optInt("row", -1), it.optString("desc", ""))
                }?.takeIf { it.col in 0..5 && it.row in 0..6 }
                if (unknown != null) saveUnknownShot(ctx, frame, unknown)
                if (pick != -1 && pick !in cands.indices && unknown == null) {
                    AppLog.w("AI判点", "模型回的 pick=$pick 不在候选里，退回期限规则")
                    return@withContext null
                }
                Verdict(pick, unknown, text.take(300))
            } catch (e: Exception) {
                AppLog.w("AI判点", "调用失败（${e.javaClass.simpleName}：${e.message?.take(120)}），退回期限规则")
                null
            }
        }
    }
}
