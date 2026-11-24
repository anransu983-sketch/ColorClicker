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
 * 0.60 墙压罚（47 局教训：0.59 首局只活 8 轮）：47 局的死因不是单次错 pick，而是两波新刷
 *  （11+19 只）把盘子灌满、墙区先饱和；0.59 的 hz>=1 滤网只在“顶级两步值候选会必死、另有安全
 *  候选”时才改 pick，47 局里这种情况直到盘子已死才出现（末尾两次 -1 救场各买 1 轮）。
 *  补一刀连续量：simulateNextStats 早就算了下一盘的墙压 threat（点完上移后行<=0 的怪数），
 *  求解器之前把它丢了（`_`）。0.60 在安全子集里按（两步值 - 墙压）排序——留墙区怪越多的候选
 *  越扣分，逼求解器提前把墙区怪清掉，别等墙边才救火（0.79 起翻倍为两步值 - 2×墙压，见 pressureScore）。本地仿真同盘 400 局：0.59（B）7.3 轮/216 分，
 *  0.60（G）8.2 轮/234 分；墙区优先清（F）8.3 轮/233 分、期限优先（D）9.0 轮/242 分。
 *  没上 F/D：F 把墙区清数当主排序键太激进（会拿 1 只墙怪换 7 只中盘怪），D 在真机上分低被 0.43 取代过；
 *  G 是 0.59 的温和延续（安全滤网不动，只在子集里加墙压罚）。仿真不是保证（它也曾预言 0.59 会涨、
 *  47 局却没涨），真凭据看接下来几局的存活轮数。
 * 深度做到两步：新刷的怪未知，更深的步数是假精度，不做；合群只留作并列时的末位排序。
 * 两步值用全权重（不是以前影子模式的 0.5）：下一步就是紧接着的一次施法，
 * 下轮最大一伙来自已存活的格子；新刷的怪未知，两边都不算，所以不用折扣。
 */
object Solver {
    /** 0.82 敢点大伙：撤掉 0.72 的定型延后。
     *  0.72 的定型过滤让求解器只在“已定型”（不碰最下排）的伙里挑，把还长着的大伙一让再让，
     *  88 局实证：期限规则指着土豆 11 只、蝎子 19 只，求解器却改去点史莱姆 1 只、土豆 1 只，
     *  场均只清 3.7 只、盘净增 2.3 只/次，十几二十次灌满即死。
     *  仿真 19 策略×400 局：去定型（K）19.2 轮/529 分 vs 有定型（M）14.4 轮/377 分，是最大的单杠杆。
     *  仿真刷怪均匀随机、复现不了真机单种怪占盘，定型在真机的价值可能被低估——但用户 2026-11-24
     *  拍板“改呀”，先撤掉看真机。安全子集（hz>=1）与墙压×2（pressureScore）不动，只撤定型。 */

    data class Eval(
        val ruleName: String,
        val comp: Matcher.Comp,
        val hz: Int,
        val twoStep: Int,
        val nextMax: Int,
        val cluster: Int,
        val ends: Boolean,
        val threat: Int
    ) {
        /** 0.57：值 = 两步值（确定的）。合群不再进值，只在并列时当末位排序（见头注释）。 */
        val value: Int get() = twoStep
        /** 0.60 墙压分 = 两步值 - 下一盘墙压。0.75 起墙压按排加权（-1 排怪记 2、0 排记 1，见 BoardMemory.simulateNextStats），
         *  留墙区怪越多越扣分，-1 排的怪扣得最狠。
         *  0.79 墙压翻倍：两步值 - 2×下一盘墙压。仿真 19 策略×400 局全场最好（20.7 轮/577 分），
         *  真机 0.75（墙压×1）已是各版最好（17.5 施法/68.5 只），翻倍是仿真最强的单杠杆。 */
        val pressureScore: Int get() = twoStep - 2 * threat
    }

    /** 给一伙打分：余量 + 模拟下一步（复用 BoardMemory.simulateNextStats，不另写分组逻辑）+ 合群。 */
    fun eval(compsAll: List<Pair<String, Matcher.Comp>>, ruleName: String, comp: Matcher.Comp,
             ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Eval {
        // 0.45：ghosts = 贴墙漏数伙（模板没认出、底色有怪）。只进期限余量和越线模拟，不是候选、不进只数。
        val hz = BoardMemory.horizonAfterKill(compsAll + ghosts, comp)
        // 0.60：threat = 下一盘墙压（点完上移后行<=0 的怪数），之前被丢掉（`_`），现在进墙压分。
        val (ends, nextMax, threat) = BoardMemory.simulateNextStats(compsAll, comp, ruleName, ghosts)
        // ends 时 nextMax 已是 0，两步值就是本轮只数；合群在点完即越线时也是 0。
        val cluster = BoardMemory.clusterPotential(compsAll, comp, ruleName)
        return Eval(ruleName, comp, hz, comp.size + nextMax, nextMax, cluster, ends, threat)
    }

    /** 选出本轮该点的一伙；空棋盘返回 null。
     *  0.59 生存优先：先取 hz >= 1 的安全子集（点完后至少 2 轮内不强制撞墙），再在其中排序；
     *  安全子集不存在时回落旧口径（hz != -1，仍无则全量）。
     *  0.60：子集内按墙压分（两步值 - 下一盘墙压）→ 只数 → 靠墙 → 合群 → 相似度排。
     *  0.79 撤墙边止损、回 0.75 结构：0.76 的 urgent 过滤真机四局（72–75）场均 14.5 施法/55.3 只，
     *  低于 0.75 四局（67–70）的 17.5 施法/68.5 只，仿真也说 N<M（13.6<14.4）——逼着点墙边小伙
     *  反而把大伙让死。撤掉后安全子集里直接走 0.72 定型延后（0.75 原样），墙压改翻倍（见 pressureScore）。
     *  hz == -1（点完即撞墙）的候选只在全部候选都是 -1 时才入选。 */
    fun pick(compsAll: List<Pair<String, Matcher.Comp>>, ghosts: List<Pair<String, Matcher.Comp>> = emptyList()): Eval? {
        if (compsAll.isEmpty()) return null
        val evals = compsAll.map { (name, comp) -> eval(compsAll, name, comp, ghosts) }
        val safe = evals.filter { it.hz >= 1 }
        val pool = when {
            safe.isNotEmpty() -> safe
            else -> evals.filter { it.hz != -1 }.ifEmpty { evals }
        }
        // 0.88 回到 0.85 的墙压分优先：0.86 的只数优先真机三局（103–105）场均
        // 12.33 施法/42.33 只/3.44 只/次，低于 0.85 的 99–102 四局（16.75/62.75/3.74）。
        // 改点确实在点大伙（3>2、5>2、6>1），但局更短：只数优先不管墙压，墙区更快
        // 饱和，终局复盘全是“点完即越线”。墙压分（两步 - 2×墙压）同时看确定的只数
        // 与下一盘的墙压，是目前真机最好的排序。安全子集（hz>=1）不动。
        return pool.sortedWith(
            compareByDescending<Eval> { it.pressureScore }
                .thenByDescending { it.comp.size }
                .thenBy { BoardMemory.minRowOf(it.comp) }
                .thenByDescending { it.cluster }
                .thenByDescending { it.comp.score }
        ).first()
    }
}
