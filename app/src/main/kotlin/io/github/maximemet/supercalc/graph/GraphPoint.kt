package io.github.maximemet.supercalc.graph

/**
 * 图上的一个交点：数学坐标 + 点开以后显示在气泡里的文字。
 *
 * 参考实现给每个交点建了一个 `IntersectionView`（小圆点，可点），
 * 点的附加文字长这样：`"\n1与y轴交点"`、`"\n-- 1与2交点"`、`"\n1的最大值"`。
 *
 * [valid] 对应参考实现的 `IntersectionView.valid`：点开以后如果发现这个点根本不在
 * 任何曲线上（拖动/缩放后残留的老点），原版会把点设成「无效」——**圆点不再画**，
 * 也点不动了。我们照做。
 *
 * [owner] 是「哪条函数的点」：图例里点掉那条函数时，这个点跟着消失。
 * 固定点和 x 轴交点归自己那条函数；函数之间的交点归**编号大的那条**
 * （参考实现把两函数交点存进 `intersectCalculed[j][i]`（j<i）这个桶，
 * 隐藏函数 i 时整列被藏掉，隐藏 j 时不会——这里如实照抄）。
 */
data class GraphPoint(
    val x: Double,
    val y: Double,
    val label: String,
    var valid: Boolean = true,
    val owner: Int = -1,
)
