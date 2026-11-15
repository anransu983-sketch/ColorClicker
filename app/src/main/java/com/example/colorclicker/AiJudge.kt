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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.26 AI 判点（Gemini 免费版）：
 * 每轮把棋盘图（裁成 y≥1100 的棋盘区、缩到宽 720、画上列号/行号）+ 候选清单发给
 * Gemini 2.5 Flash（Google AI Studio 免费额度：约 10 次/分钟，每天几百到一千多次），
 * 模型只回 JSON：{"pick": 候选编号, "unknown": {col,row,desc} 或 null}。
 * 任何失败（没网、超时、429、格式错、没填 key）都返回 null，调用方退回期限规则，
 * 绝不因为 AI 掉线就卡死。Key 只存在本机 SharedPreferences，不上传别处。
 * 注意：免费额度下发给 Google 的内容可能被用于训练——这里只发游戏棋盘图，没有隐私信息。
 */
object AiJudge {
    private const val PREFS = "ai_judge"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_ENABLED = "ai_enabled"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val TIMEOUT_MS = 30_000
    private const val CONNECT_TIMEOUT_MS = 10_000
    /** 免费额度约 10 次/分钟：两次判点至少隔这么久，免得 429 */
    private const val MIN_GAP_MS = 6_000L
    private var lastJudgeAt = 0L

    fun getApiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key.trim()).apply()
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
            规则：优先保住城墙（第0/-1排的怪最急），再比只数多。只回 JSON，不要别的字：
            {"pick": <候选编号，0起；没有能点的回 -1>, "unknown": {"col": <列号0-5>, "row": <排号0-6>, "desc": "<看到的不在候选里的怪的样子，如淡蓝水母一只大紫眼睛>"} 或 "unknown": null}
            只有当你确实在图上看到一种候选里没有的怪才填 unknown；不确定就填 null。
        """.trimIndent()
    }

    /** 发一轮判点。成功返回 Verdict；任何失败返回 null（调用方退回期限规则）。 */
    suspend fun judge(ctx: Context, frame: Bitmap, cands: List<Candidate>): Verdict? {
        val key = getApiKey(ctx)
        if (key.isBlank() || cands.isEmpty()) return null
        // 免费额度 10 次/分钟：节流，避免 429
        val gap = System.currentTimeMillis() - lastJudgeAt
        if (gap < MIN_GAP_MS) delay(MIN_GAP_MS - gap)
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
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
