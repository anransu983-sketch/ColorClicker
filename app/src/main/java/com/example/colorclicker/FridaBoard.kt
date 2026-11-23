package com.example.colorclicker

import android.graphics.Point

/** Frida 棋盘读取（0.81）：从 logcat 读 BOARD-GRID，不再靠模板颜色认怪。
 *  BOARD-GRID 由 Frida Gadget 脚本每 1 秒打一次（棋盘位置稳定时才打），格式 7 行（row 0=贴墙）×6 列，字母：
 *  B=史莱姆 M=土豆怪 S=蝎子 T=树怪 F=蛇 Y=水母 P=野猪 U=蘑菇怪 H=花仙（花仙出了再补，见 BOARD-UNKNOWN）
 *  '.'=空格 '?'=未知类型（跳过，等 BOARD-UNKNOWN 校准）。
 *  读不到（没给 READ_LOGS 权限 / Frida 没跑 / 棋盘还没出）返回空列表，Runner 回落模板扫描。
 *  权限（一次）：su -M -c "pm grant com.example.colorclicker android.permission.READ_LOGS"。
 *  直接读不到时再试 su（需 KernelSU 给 ColorClicker 授权）。 */
object FridaBoard {
    private val LETTER_RULE = mapOf(
        'B' to "守卫-史莱姆",
        'M' to "守卫-土豆怪",
        'S' to "守卫-蝎子",
        'T' to "守卫-树怪",
        'F' to "守卫-蛇",
        'Y' to "守卫-水母",
        'P' to "守卫-野猪",
        'U' to "守卫-蘑菇怪",
        'H' to "守卫-花仙"
    )

    /** 读最新一条 BOARD-GRID 并转成 (规则名, Comp)；读不到/格式不对/全空返回空列表。 */
    fun readComps(): List<Pair<String, Matcher.Comp>> {
        val raw = readLatestGrid() ?: return emptyList()
        val grid = parseGrid(raw) ?: return emptyList()
        return toComps(grid)
    }

    /** 读最新一条 BOARD-GRID 的原始串（7 行以 / 分隔），读不到返回 null。 */
    fun readLatestGrid(): String? {
        val out = runLogcat() ?: return null
        var last: String? = null
        for (line in out.lines()) {
            val i = line.indexOf("BOARD-GRID ")
            if (i >= 0) last = line.substring(i + "BOARD-GRID ".length).trim()
        }
        return last
    }

    private fun runLogcat(): String? {
        // 先直接读（已 pm grant READ_LOGS 时不用 root）
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "BOARD"))
            val s = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (s.contains("BOARD-GRID")) return s
        } catch (e: Exception) {
        }
        // 再试 su（KernelSU 已给 ColorClicker root 时）
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-M", "-c", "logcat -d -s BOARD"))
            val s = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (s.contains("BOARD-GRID")) return s
        } catch (e: Exception) {
        }
        return null
    }

    /** 解析 BOARD-GRID 串为 7x6 字符格（row 0=贴墙）；格式不对返回 null。 */
    fun parseGrid(s: String): Array<CharArray>? {
        val rows = s.split("/")
        if (rows.size != 7) return null
        val g = Array(7) { CharArray(6) }
        for (r in 0..6) {
            if (rows[r].length != 6) return null
            for (c in 0..5) g[r][c] = rows[r][c]
        }
        return g
    }

    /** 把字符格转成 Solver 要的 (规则名, Comp) 列表：同种上下左右连通成伙。
     *  未知 '?' 与空格跳过。Comp.point 取伙里最靠墙（row 最小）再最左那格的中心；cells/ys 全列出来；
     *  score 全 1.0（Frida 给的是实测类型，不是模板分）。 */
    fun toComps(grid: Array<CharArray>): List<Pair<String, Matcher.Comp>> {
        val seen = Array(7) { BooleanArray(6) }
        val out = ArrayList<Pair<String, Matcher.Comp>>()
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        for (r in 0..6) for (c in 0..5) {
            val ch = grid[r][c]
            val rule = LETTER_RULE[ch] ?: continue
            if (seen[r][c]) continue
            val cells = ArrayList<Pair<Int, Int>>()
            val q = ArrayDeque<Pair<Int, Int>>()
            q.add(Pair(r, c)); seen[r][c] = true
            while (q.isNotEmpty()) {
                val (cr, cc) = q.removeFirst()
                cells.add(Pair(cr, cc))
                for (d in dirs) {
                    val nr = cr + d[0]; val nc = cc + d[1]
                    if (nr in 0..6 && nc in 0..5 && !seen[nr][nc] && grid[nr][nc] == ch) {
                        seen[nr][nc] = true; q.add(Pair(nr, nc))
                    }
                }
            }
            val sorted = cells.sortedWith(compareBy({ it.first }, { it.second }))
            val (pr, pc) = sorted.first()
            val pts = cells.map { (rr, cc) -> BoardMemory.cellCenter(cc, rr) }
            out.add(rule to Matcher.Comp(
                point = BoardMemory.cellCenter(pc, pr),
                size = cells.size,
                score = 1.0,
                ys = pts.map { it.y },
                cells = pts,
                scores = List(cells.size) { 1.0 }
            ))
        }
        return out
    }
}
