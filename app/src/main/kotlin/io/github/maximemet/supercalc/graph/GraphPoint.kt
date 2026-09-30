package io.github.maximemet.supercalc.graph

/**
 * 图上的一个交点：数学坐标 + 点开以后显示在气泡里的文字。
 *
 * 参考实现给每个交点建了一个 `IntersectionView`（小圆点，可点），
 * 点的附加文字长这样：`"\n1与y轴交点"`、`"\n-- 1与2交点"`、`"\n1的最大值"`。
 */
data class GraphPoint(val x: Double, val y: Double, val label: String)
