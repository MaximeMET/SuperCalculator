package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 极限的数值兜底。
 *
 * Symja 2016 版的 Limit 规则只覆盖一部分形状：`lim x→∞ (1+1/x)^x` 能给出 `e`，
 * 但同一个重要极限写成 `lim x→0 (1+x)^(1/x)` 就原样返回、不求值（实测见
 * work/logs/limitprobe.txt），结果页会把没求值的极限式当结果回显——用户报的
 * 「重要极限算不出来」就是它。
 *
 * 兜底分两级：
 *  1. 取对数改写 `lim f^g = e^(lim g·ln f)`：改写后是 Symja 有规则的 0/0 形状，
 *     能拿到精确值（`lim x→0 (1+x)^(1/x)` 直接给 `e`）；
 *  2. 纯数值逼近：在极限点两侧各取一个很小的偏移做对称平均。
 *
 * 数值结果同时是验证：改写出来的精确值必须和数值结果对得上才采用。发散、
 * 跳跃、无穷一律返回 null——界面宁可回显原式，也不编一个假数。
 */
object LimitFallback {

    /** 数值偏移量。1e-5 时 `(1+x)^(1/x)` 的对称平均误差约 1e-10。 */
    const val STEP = 1e-5

    /** 两侧结果的相对差超过它就认为极限不存在（或至少不敢用数值兜底）。 */
    const val TOLERANCE = 1e-4

    /** 精确结果与数值结果的相对差上限。 */
    private const val AGREEMENT = 1e-6

    /**
     * 解析出来的未求值极限 `Limit(body, variable -> point)`。
     *
     * [direction] 沿用 Mathematica 约定：1 = 从左侧逼近，-1 = 从右侧，null = 两侧。
     * Symja 的实现与之一致（实测 `Limit(1/x,x->0,Direction->1)` 给 -∞、
     * `Direction->-1` 给 +∞）。
     */
    data class Unevaluated(
        val body: String,
        val variable: String,
        val point: Double,
        val direction: Int?,
    )

    /**
     * 把 `Limit(...)` 拆成 [Unevaluated]；不是「有限极限点 + 规则形式」就返回 null。
     *
     * 极限点是 ±∞ 的式子不做兜底：数值逼近在无穷远没法用一个步长定得可靠。
     * （Symja 对无穷远那批规则本来也够用，`lim x→∞ (1+1/x)^x` 直接就是 `e`。）
     */
    fun parse(engine: SymjaEngine, formula: String): Unevaluated? {
        val expr = engine.parseOrNull(formula) ?: return null
        if (!expr.isAST(F.Limit)) return null
        val limit = expr as IAST
        if (limit.size < 3) return null
        val rule = limit.arg2()
        if (!rule.isAST(F.Rule)) return null
        val args = rule as IAST
        if (args.size != 3) return null
        val point = finiteDouble(args.arg2()) ?: return null
        return Unevaluated(
            body = limit.arg1().toString(),
            variable = args.arg1().toString(),
            point = point,
            direction = directionOf(limit),
        )
    }

    /**
     * 未求值的极限式 → 结果 LaTeX；实在算不出来返回 null。
     */
    fun evaluateUnevaluated(engine: SymjaEngine, formula: String): String? {
        val limit = parse(engine, formula) ?: return null
        val numeric = numericValueAt(engine, limit.body, limit.variable, limit.point, limit.direction)
        val rewritten = powerRewrite(engine, limit)
        val rewrittenValue = finiteDouble(rewritten)
        if (rewritten != null && rewrittenValue != null &&
            (numeric == null || agrees(rewrittenValue, numeric))
        ) {
            engine.toLatex(rewritten)?.let { return it }
        }
        return numeric?.let { LatexText.fromDouble(it) }
    }

    /**
     * 极限值：先让引擎精确算（不套 `N(...)`），算不出来再用数值逼近。
     *
     * 绘图页的 y 轴交点、以及「点是否落在曲线上」的判定都走这里。
     */
    fun valueAt(
        engine: SymjaEngine,
        body: String,
        variable: String,
        point: Double,
        direction: Int? = null,
    ): Double? = finiteDouble(exactLimit(engine, body, variable, point, direction))
        ?: numericValueAt(engine, body, variable, point, direction)

    /**
     * 精确极限。
     *
     * 这里**不能**套 `N(...)`：数值模式会把 `x->0` 变成 `x->0.0`，极限规则就匹配
     * 不上了（实测 `Limit(sin(x)/x,x->0.0)` 直接是 NaN）。
     */
    private fun exactLimit(
        engine: SymjaEngine,
        body: String,
        variable: String,
        point: Double,
        direction: Int? = null,
    ): IExpr? {
        val code = StringBuilder("Limit(($body), $variable -> ${pointText(point)}")
        if (direction != null) code.append(", Direction -> ").append(direction)
        code.append(")")
        return engine.evaluateOrNull(engine.parseOrNull(code.toString()))
    }

    /**
     * 两侧（或 Direction 指定的单侧）的数值逼近；两头对不上就返回 null。
     */
    private fun numericValueAt(
        engine: SymjaEngine,
        body: String,
        variable: String,
        point: Double,
        direction: Int? = null,
    ): Double? {
        val step = STEP * maxOf(1.0, Math.abs(point))
        val plus = engine.numericValueOf(substituted(body, variable, point + step))
        val minus = engine.numericValueOf(substituted(body, variable, point - step))
        if (direction != null) return if (direction > 0) minus else plus
        if (plus == null || minus == null) return null
        val scale = maxOf(1.0, Math.abs(plus), Math.abs(minus))
        if (Math.abs(plus - minus) > TOLERANCE * scale) return null
        return (plus + minus) / 2.0
    }

    /**
     * `f(x)^g(x)` 型极限的取对数改写：`lim f^g = e^(lim g·ln f)`。
     *
     * 只在「底数在极限点某一侧为正」时才改写——否则 Log 跑到复数上，结果没意义。
     */
    private fun powerRewrite(engine: SymjaEngine, limit: Unevaluated): IExpr? {
        val expr = engine.parseOrNull("(${limit.body})") ?: return null
        if (!expr.isAST(F.Power)) return null
        val power = expr as IAST
        if (power.size != 3) return null
        val base = power.arg1().toString()
        val exponent = power.arg2().toString()
        if (!hasPositiveSide(engine, base, limit)) return null

        val code = StringBuilder("Limit(($exponent)*Log($base), ")
            .append(limit.variable).append(" -> ").append(pointText(limit.point))
        if (limit.direction != null) code.append(", Direction -> ").append(limit.direction)
        code.append(")")
        val value = engine.evaluateOrNull(engine.parseOrNull(code.toString())) ?: return null
        if (!value.isNumber) return null
        return engine.evaluateOrNull(engine.parseOrNull("E^($value)"))
    }

    /** 底数在极限点两侧里至少有一侧是正的（单侧极限只要那一侧）。 */
    private fun hasPositiveSide(engine: SymjaEngine, base: String, limit: Unevaluated): Boolean {
        val step = STEP * maxOf(1.0, Math.abs(limit.point))
        val plus = engine.numericValueOf(substituted(base, limit.variable, limit.point + step))
        val minus = engine.numericValueOf(substituted(base, limit.variable, limit.point - step))
        return when (limit.direction) {
            null -> (plus != null && plus > 0) || (minus != null && minus > 0)
            else -> if (limit.direction > 0) minus != null && minus > 0 else plus != null && plus > 0
        }
    }

    /** `(body) /. x -> value`，统一走数值引擎。 */
    private fun substituted(body: String, variable: String, value: Double): String =
        "($body) /. $variable -> $value"

    /**
     * 极限点的写法：整数不能带小数点。
     *
     * `Limit(...,x->0)` 和 `Limit(...,x->0.0)` 在 Symja 里是两条不同的路，
     * 后者会因为规则匹配不上直接给 NaN。
     */
    private fun pointText(point: Double): String =
        if (point == Math.floor(point) && Math.abs(point) < 1e15) {
            point.toLong().toString()
        } else {
            point.toString()
        }

    private fun directionOf(limit: IAST): Int? {
        for (i in 3 until limit.size) {
            val arg = limit.get(i)
            if (!arg.isAST(F.Rule)) continue
            val rule = arg as IAST
            if (rule.size != 3 || rule.arg1().toString() != "Direction") continue
            val value = finiteDouble(rule.arg2()) ?: continue
            return if (value < 0) -1 else 1
        }
        return null
    }

    private fun agrees(a: Double, b: Double): Boolean =
        Math.abs(a - b) <= AGREEMENT * maxOf(1.0, Math.abs(a), Math.abs(b))

    /**
     * 是实数、有限，才算拿到了结果。
     *
     * 注意不能只认 `isNumber`：`E`、`Pi` 这类常数在 Symja 里是符号，不是 INum，
     * 但 `evalDouble()` 能给值（取对数改写的目标就是 `e`）。反过来，符号表达式
     * 和复数求不出有限实数，会被挡掉。
     */
    private fun finiteDouble(expr: IExpr?): Double? {
        if (expr == null) return null
        val value = try {
            expr.evalDouble()
        } catch (e: Exception) {
            return null
        }
        return if (value.isFinite()) value else null
    }
}
