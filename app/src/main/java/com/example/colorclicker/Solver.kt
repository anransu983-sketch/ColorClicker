package com.example.colorclicker

/**
 * 0.43 本地最优解求解器（删 AI 判点的替代）：
 * 纯本地计算，无网络、无 Key、无等待。输入本轮所有伙，输出该点的一伙。
 *
 * 选点顺序（0.56 起只数优先，余量只作必死过滤；0.57 起合群降为并列时的末位排序）：
 *  1) 两步值大优先——本轮只数 + 下轮最大一伙只数（确定的）；
 *  2) 本轮只数大优先；
 *  3) 最靠墙（minRow 小）优先；
 *  4) 合群大优先——合群 = 点完后棋盘上一个缺口能连起的最大伙（两伙只数之和 + 1，见 BoardMemory.clusterPotential），
 *     只在 1~3 全平时才看，所以它再也不能让一次点击放弃确定的只数；
 *     0.55 起只算两边的伙还能活两轮（点完后 minRow ≥ 1）的桥，墙边的桥不等了；
 *  5) 相似度大优先。
 * 0.57 为什么把合群从“三步值”里拿出来（0.54/0.55 的 /3 折算作废）：
 *  - 42~44 局合群改点 7 次、兑现 0 次；“合群不亏”这句话被证伪——改点是用确定的只数换一个要等“对的怪”才兑现的桥。
 *  - 桥真兑现时的增量不是两伙只数之和 + 1：不搭桥两伙也各清一次，搭桥只多 1 只、省 1 次施法（约 3 只），
 *    约 4 只；兑现概率按 8 种怪、缺口活 1~2 轮算约 1/8~1/4，期望增量约 0.5~1 只。/3 折算给了 4 分，高估约 4~8 倍。
 *  - 改除数救不了：44 局 03:21:38 的 两步13＋合群12 对 两步13＋合群0，除数 ≤12 时蛇 3 只照样赢土豆 10 只
 *    （/6 得 15 对 13，/8 得 14 对 13）；03:21:40（12＋13 对 12＋4）、03:21:30（10＋10 对 10＋5）同理。只有不让合群进值才全部翻回大伙。
 * 余量 hz 不再参与排序：只用来排除 hz == -1（点完即撞墙）的候选，除非全部候选都是 -1。
 * 0.56 之前余量排第一，单只离墙远、余量高会赢过墙边的大伙——那就是“打着打着只打单个怪”的根因。
 * 改后大伙优先；代价是局可能短一点（每下清得多但总下数少了），总分涨不涨得真机验。
 * 深度做到两步：新刷的怪未知，更深的步数是假精度，不做；合群只留作并列时的末位排序。
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
        /** 0.57：值 = 两步值（确定的）。合群不再进值，只在并列时当末位排序（见头注释）。 */
        val value: Int get() = twoStep
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
     *  除非全部候选都是 -1（那局反正要结束，选两步值最大的）。
     *  0.56 起余量只作过滤、不参与排序：可点的里面按两步值 → 只数 → 靠墙 → 合群 → 相似度排，
     *  大伙优先，单只不再靠余量高赢过大伙。 */
    fun pick(compsAll: List<Pair<String, Matcher.Comp>>, ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Eval? {
        if (compsAll.isEmpty()) return null
        val evals = compsAll.map { (name, comp) -> eval(compsAll, name, comp, ghosts) }
        val pool = evals.filter { it.hz != -1 }.ifEmpty { evals }
        return pool.sortedWith(
            compareByDescending<Eval> { it.value }
                .thenByDescending { it.comp.size }
                .thenBy { BoardMemory.minRowOf(it.comp) }
                .thenByDescending { it.cluster }
                .thenByDescending { it.comp.score }
        ).first()
    }
}
