package com.example.colorclicker

/**
 * 0.43 本地最优解求解器（删 AI 判点的替代）：
 * 纯本地计算，无网络、无 Key、无等待。输入本轮所有伙，输出该点的一伙。
 *
 * 选点顺序（安全永远优先于只数）：
 *  1) 余量 min(hz, HZ_CAP=6) 大优先——hz 是点完该伙后其余伙还能撑几轮，-1 = 点完即撞墙；
 *  2) 三步值大优先——两步值（本轮只数 + 下轮最大一伙只数，确定的）+ 合群/3；
 *     合群 = 点完后棋盘上一个缺口能连起的最大伙（两伙只数之和 + 1，见 BoardMemory.clusterPotential），
 *     要一次“对的怪刷进缺口”才兑现（新刷约 10 只/轮、8 种怪，缺口每轮大多被别的怪先占掉），所以只按 1/3 折算；
 *     合群不亏：不成也只是换了个点法顺序（不成时两伙还是各清一次），成了多清一伙还省一次施法；
 *  3) 本轮只数大优先；
 *  4) 最靠墙（minRow 小）优先；
 *  5) 相似度大优先。
 * 深度做到三步：第三步是合群（一个缺口 + 一次对的怪），不是假装知道新刷长在哪——
 * 它只算“缺口存在”，兑现概率按 1/3 折进三步值；更深的步数才是假精度，不做。
 * 两步值用全权重（不是以前影子模式的 0.5）：下一步就是紧接着的一次施法，
 * 下轮最大一伙来自已存活的格子；新刷的怪未知，两边都不算，所以不用折扣。
 */
object Solver {
    data class Eval(
        val ruleName: String,
        val comp: Matcher.Comp,
        val hz: Int,
        val twoStep: Int,
        val nextMax: Int,
        val cluster: Int,
        val ends: Boolean
    ) {
        /** 三步值：两步值（确定的）+ 合群/3（概率折算，见头注释）。 */
        val value: Int get() = twoStep + cluster / 3
    }

    /** 给一伙打分：余量 + 模拟下一步（复用 BoardMemory.simulateNextStats，不另写分组逻辑）+ 合群。 */
    fun eval(compsAll: List<Pair<String, Matcher.Comp>>, ruleName: String, comp: Matcher.Comp,
             ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Eval {
        // 0.45：ghosts = 贴墙漏数伙（模板没认出、底色有怪）。只进期限余量和越线模拟，不是候选、不进只数。
        val hz = BoardMemory.horizonAfterKill(compsAll + ghosts, comp)
        val (ends, nextMax, _) = BoardMemory.simulateNextStats(compsAll, comp, ruleName, ghosts)
        // ends 时 nextMax 已是 0，两步值就是本轮只数；合群在点完即越线时也是 0。
        val cluster = BoardMemory.clusterPotential(compsAll, comp, ruleName)
        return Eval(ruleName, comp, hz, comp.size + nextMax, nextMax, cluster, ends)
    }

    /** 选出本轮该点的一伙；空棋盘返回 null。hz == -1（点完即撞墙）的候选一律排除，
     *  除非全部候选都是 -1（那局反正要结束，选三步值最大的）。 */
    fun pick(compsAll: List<Pair<String, Matcher.Comp>>, ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Eval? {
        if (compsAll.isEmpty()) return null
        val evals = compsAll.map { (name, comp) -> eval(compsAll, name, comp, ghosts) }
        val pool = evals.filter { it.hz != -1 }.ifEmpty { evals }
        return pool.sortedWith(
            compareByDescending<Eval> { minOf(it.hz, BoardMemory.HZ_CAP) }
                .thenByDescending { it.value }
                .thenByDescending { it.comp.size }
                .thenBy { BoardMemory.minRowOf(it.comp) }
                .thenByDescending { it.comp.score }
        ).first()
    }
}
