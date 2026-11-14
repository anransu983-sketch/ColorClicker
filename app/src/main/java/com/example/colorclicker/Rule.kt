package com.example.colorclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条规则：识别到目标 -> 点击 -> 等待 */
data class Rule(
    val id: String,
    val name: String,
    val type: String,                 // "color" 找色 | "image" 找图
    val colorHex: String = "#FF0000",
    val tolerance: Int = 16,          // 每个通道允许的误差 0-255
    val templatePath: String = "",
    val threshold: Double = 0.85,     // 找图相似度 0-1
    val region: List<Int>? = null,    // [x, y, w, h]，null = 全屏
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val delayMs: Long = 1000,         // 点击后等待
    val enabled: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type)
        put("colorHex", colorHex); put("tolerance", tolerance)
        put("templatePath", templatePath); put("threshold", threshold)
        put("region", region?.let { JSONArray(it) } ?: JSONObject.NULL)
        put("offsetX", offsetX); put("offsetY", offsetY)
        put("delayMs", delayMs); put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.getString("id"), name = o.getString("name"), type = o.getString("type"),
            colorHex = o.optString("colorHex", "#FF0000"), tolerance = o.optInt("tolerance", 16),
            templatePath = o.optString("templatePath", ""), threshold = o.optDouble("threshold", 0.85),
            region = o.optJSONArray("region")?.let { a -> List(a.length()) { a.getInt(it) } },
            offsetX = o.optInt("offsetX", 0), offsetY = o.optInt("offsetY", 0),
            delayMs = o.optLong("delayMs", 1000), enabled = o.optBoolean("enabled", true),
        )
    }
}

object RuleStore {
    private const val KEY = "rules"
    private fun sp(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(c: Context): List<Rule> {
        val s = sp(c).getString(KEY, null) ?: return emptyList()
        val a = JSONArray(s)
        return List(a.length()) { Rule.fromJson(a.getJSONObject(it)) }
    }

    fun save(c: Context, rules: List<Rule>) {
        val a = JSONArray(); rules.forEach { a.put(it.toJson()) }
        sp(c).edit().putString(KEY, a.toString()).apply()
    }

    // 循环次数：成功点击多少次后自动停止，0 = 无限
    private const val KEY_LOOP = "loop_limit"
    fun getLoopLimit(c: Context): Int = sp(c).getInt(KEY_LOOP, 0)
    fun setLoopLimit(c: Context, n: Int) { sp(c).edit().putInt(KEY_LOOP, n.coerceAtLeast(0)).apply() }
}
