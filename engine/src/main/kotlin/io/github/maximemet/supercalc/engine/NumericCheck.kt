package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 「两个表达式是不是同一个东西」的数值对拍，步骤引擎共用。
 *
 * 关键点：**自由符号要全部代值**，不只是自变量。`a*x^2`、`sin(a*x)` 这类带参数的
 * 式子里 `a` 是常数，但数值采样时如果只替 `x`，表达式仍然含 `a`、求不出数，
 * 整条步骤就会因为"验不过"被丢掉——这正是"字母参数不能积分/求导/求极限"的根因。
 *
 * 做法和 DerivativeSteps 原来那一份一致：每个自由符号都代采样点，按轮次错开，
 * 至少两轮都算得出来、且两边的值都对得上才算通过；算不出来的点（定义域外、
 * 奇点）直接跳过。
 */
internal object NumericCheck {

    /** 相对误差：两边都按量级放一点余量，幂函数的大值不至于被浮点误差误杀。 */
    private const val TOLERANCE = 1e-6

    fun agrees(
        engine: SymjaEngine,
        a: IExpr,
        b: IExpr,
        points: DoubleArray,
        minSamples: Int = 2,
    ): Boolean = agrees(engine, a, b, points, emptySet(), minSamples)

    /**
     * 和 [agrees] 相同，但 [ignored] 里的符号不参与代值。
     *
     * 极限的符号对拍要跳过极限变量本身：`lim sin(ax)/x` 洛必达后的比值式还含
     * `x`，两边都代采样点值只会在比 `sin(ax)/x` 这个函数，而不是比极限值——
     * 把 `x` 排除、只让参数 `a` 走采样，比的才是「参数取任意值时两边是否恒等」。
     */
    fun agrees(
        engine: SymjaEngine,
        a: IExpr,
        b: IExpr,
        points: DoubleArray,
        ignored: Set<IExpr>,
        minSamples: Int = 2,
    ): Boolean {
        val symbols = linkedSetOf<IExpr>()
        collectSymbols(a, symbols)
        collectSymbols(b, symbols)
        symbols.removeAll(ignored)
        if (symbols.isEmpty()) return true

        var checked = 0
        for (round in points.indices) {
            val substitutions = symbols.mapIndexed { index, symbol ->
                "($symbol -> ${points[(index + round) % points.size]})"
            }
            val aCode = substitutions.fold(a.toString()) { code, rule -> "($code) /. $rule" }
            val bCode = substitutions.fold(b.toString()) { code, rule -> "($code) /. $rule" }
            val va = engine.numericValueOf(aCode) ?: continue
            val vb = engine.numericValueOf(bCode) ?: continue
            checked++
            val tolerance = TOLERANCE * (1.0 + kotlin.math.abs(va) + kotlin.math.abs(vb))
            if (kotlin.math.abs(va - vb) > tolerance) return false
        }
        return checked >= minSamples
    }

    fun collectSymbols(expr: IExpr, out: MutableSet<IExpr>) {
        if (expr.isSymbol) {
            out.add(expr)
            return
        }
        val ast = expr as? IAST ?: return
        for (i in 1 until ast.size) collectSymbols(ast.get(i), out)
    }
}
