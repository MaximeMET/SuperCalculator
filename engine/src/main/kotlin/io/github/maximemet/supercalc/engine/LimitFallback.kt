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
     * 极限的最终结果，带数值对拍。
     *
     * Symja 2016 的 `Limit` 规则有**算错**的时候：`Limit(x/Sin(x), x->0)` 直接给 `0`
     * （正确值 1，实测见 [LimitProbeDiagnosticTest]）。这类结果不是"没求值"，而是
     * 一个看起来正常的错数——所以在有限极限点上，只要数值逼近拿得到，就把精确值和
     * 它对比一次：对不上以数值为准（数值通道是"代入小偏移取平均"，在这种点上是可靠的；
     * 反过来精确通道有已知的规则 bug）。数值拿不到（含符号参数、两侧对不上）时保持原样。
     *
     * [exactLatex] 是引擎直接给的 LaTeX（可能是一条没求值的极限式）；返回 null
     * 表示连数值兜底都没有结果，调用方可以回显原式。
     */
    fun resultLatex(engine: SymjaEngine, formula: String, exactLatex: String?): String? = try {
        val limit = parse(engine, formula)
        if (limit == null) {
            // 无穷远点的极限：数值逼近定不了步长（见 [parse] 的注释），只信引擎
            exactLatex
        } else {
            val exact = engine.evaluateOrNull(engine.parseOrNull(formula))
            val numeric = numericValueAt(engine, limit.body, limit.variable, limit.point, limit.direction)
            val exactDouble = finiteDouble(exact)
            when {
                // 发散：保留引擎的 ±∞ 写法，别拿一个巨大的数值结果顶上去
                isInfiniteValue(exact) -> exactLatex
                // 有限精确值和数值吻合（或数值拿不到）：用引擎的排版
                exactDouble != null && (numeric == null || agrees(exactDouble, numeric)) -> exactLatex
                // 有限精确值但和数值对不上：引擎算错了，以数值为准
                exactDouble != null && numeric != null -> LatexText.fromDouble(numeric)
                // 含自由参数的符号结果（`lim (1+ax)^(1/x) = e^a`）：数值通道代不进参数，
                // 但结果已经不含未求值的 Limit，就是引擎给出来的精确答案，直接用。
                isSymbolicAnswer(engine, exact, limit) -> exactLatex
                // 未求值 / 不定式：先试取对数改写，再退纯数值
                else -> {
                    val rewritten = powerRewrite(engine, limit)
                    val rewrittenValue = finiteDouble(rewritten)
                    if (rewritten != null && rewrittenValue != null &&
                        (numeric == null || agrees(rewrittenValue, numeric))
                    ) {
                        engine.toLatex(rewritten) ?: exactLatex
                    } else if (rewritten != null && isSymbolicAnswer(engine, rewritten, limit)) {
                        // 取对数改写得到含参数的符号答案（`e^a`）：直接排版
                        engine.toLatex(rewritten) ?: exactLatex
                    } else {
                        numeric?.let { LatexText.fromDouble(it) } ?: exactLatex
                    }
                }
            }
        }
    } catch (e: Exception) {
        exactLatex
    } catch (e: StackOverflowError) {
        exactLatex
    }

    /**
     * 极限的数值结果（带同样的对拍），给「解决过程」用。
     *
     * 精确值被数值推翻时用数值——`lim x→0 x/sinx` 的过程里 [LimitSteps] 会拿这个值
     * 当作最终答案来校验等价无穷小替换，不修的话整段过程会朝 0 去凑。
     */
    fun resultNumber(engine: SymjaEngine, formula: String, exact: IExpr?): Double? {
        val limit = parse(engine, formula) ?: return finiteDouble(exact)
        if (isInfiniteValue(exact)) return null
        // 结果是含参数的符号答案（`e^a`）：数值通道代不进参数，交给调用方
        // 走符号对拍通道，不能拿"数值拿不到"当有限值用。
        if (isSymbolicAnswer(engine, exact, limit)) return null
        val numeric = numericValueAt(engine, limit.body, limit.variable, limit.point, limit.direction)
        val exactDouble = finiteDouble(exact)
        return when {
            exactDouble != null && (numeric == null || agrees(exactDouble, numeric)) -> exactDouble
            numeric != null -> numeric
            else -> exactDouble
        }
    }

    /**
     * 极限值：先让引擎精确算（不套 `N(...)`），算不出来再用数值逼近；
     * 两边都算得出来但对不上时以数值为准（同 [resultLatex] 的口径）。
     *
     * 绘图页的 y 轴交点、以及「点是否落在曲线上」的判定都走这里。
     */
    fun valueAt(
        engine: SymjaEngine,
        body: String,
        variable: String,
        point: Double,
        direction: Int? = null,
    ): Double? {
        val exact = exactLimit(engine, body, variable, point, direction)
        if (isInfiniteValue(exact)) return null
        val numeric = numericValueAt(engine, body, variable, point, direction)
        val exactDouble = finiteDouble(exact)
        return when {
            exactDouble != null && (numeric == null || agrees(exactDouble, numeric)) -> exactDouble
            numeric != null -> numeric
            else -> exactDouble
        }
    }

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
        val value = engine.evaluateOrNull(engine.parseOrNull(code.toString()))
        if (value != null && value.isNumber) {
            return engine.evaluateOrNull(engine.parseOrNull("E^($value)"))
        }
        // 结果含自由参数（`lim (1/x)·ln(1+ax) = a`）：把参数留着，取 e 的幂再判。
        // 只认「不含数字、不含未求值 Limit」的纯符号式子，避免把半成品当真。
        if (value != null && value.isFree(engine.symbol(limit.variable)) &&
            value.toString().none { it.isDigit() } &&
            !value.toString().contains("Limit(")
        ) {
            val powered = engine.evaluateOrNull(engine.parseOrNull("E^($value)"))
            if (powered != null && powered.isFree(engine.symbol(limit.variable))) {
                return powered
            }
        }
        return null
    }

    /**
     * 底数在极限点两侧里至少有一侧是正的（单侧极限只要那一侧）。
     *
     * 带自由参数时先把参数代掉再判号——`lim (1+ax)^(1/x)` 里 `1+a·0⁺ = 1 > 0`
     * 恒成立，不代参数就永远判不出正负、改写整条走不下去。
     */
    private fun hasPositiveSide(engine: SymjaEngine, base: String, limit: Unevaluated): Boolean {
        val baseExpr = engine.parseOrNull("($base)") ?: return false
        val freeSymbols = mutableListOf<String>()
        freeSymbolsOf(baseExpr, freeSymbols)
        val step = STEP * maxOf(1.0, Math.abs(limit.point))
        for (round in SAMPLE_VALUES.indices) {
            val rules = freeSymbols.mapIndexed { index, name ->
                " /. $name -> ${SAMPLE_VALUES[(index + round) % SAMPLE_VALUES.size]}"
            }.joinToString("")
            val plus = engine.numericValueOf(
                substituted("($base)$rules", limit.variable, limit.point + step),
            )
            val minus = engine.numericValueOf(
                substituted("($base)$rules", limit.variable, limit.point - step),
            )
            val positive = when (limit.direction) {
                null -> (plus != null && plus > 0) || (minus != null && minus > 0)
                else -> if (limit.direction > 0) {
                    minus != null && minus > 0
                } else {
                    plus != null && plus > 0
                }
            }
            if (positive) return true
        }
        return false
    }

    /** 收集表达式里的自由符号名（用于给参数代采样值）。 */
    private fun freeSymbolsOf(expr: IExpr, out: MutableList<String>) {
        if (expr.isSymbol) {
            out += expr.toString()
            return
        }
        val ast = expr as? IAST ?: return
        for (i in 1 until ast.size) freeSymbolsOf(ast.get(i), out)
    }

    /** 给自由参数用的采样值（尽量避开 0/±1 这些常见奇点）。 */
    private val SAMPLE_VALUES = listOf(0.6, 1.4, 2.3, 3.7)

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
     * 引擎给出的精确结果是含自由符号的有限答案，而不是原样回显的未求值极限。
     *
     * 典型场景：`Limit((1+a*x)^(1/x), x->0)` 的 `a` 是参数（固定常数的写法），
     * 数值逼近代不进去，但引擎精确通道能给出 `e^a`——这份结果要照用，不能被
     * 「数值拿不到」拖回未求值的原式。
     */
    private fun isSymbolicAnswer(engine: SymjaEngine, exact: IExpr?, limit: Unevaluated): Boolean {
        if (exact == null) return false
        // Indeterminate 是"没求值"的标记，不是符号答案
        if (exact.isIndeterminate) return false
        // 还含极限变量的表达式（未求值的 Limit 等）不算答案
        if (!exact.isFree(engine.symbol(limit.variable))) return false
        val text = exact.toString()
        if (text.contains("Limit(")) return false
        if (text.contains("Integrate(")) return false
        return text.any { it.isLetter() }
    }

    /** 给 [LimitSteps] 用的同口径判断：精确结果是含自由参数的符号答案。 */
    internal fun symbolicAnswer(engine: SymjaEngine, formula: String, exact: IExpr?): Boolean {
        val limit = parse(engine, formula) ?: return false
        return isSymbolicAnswer(engine, exact, limit)
    }

    /** 引擎给的 ±∞ / 复无穷：这是"发散"的答案，不能用数值结果顶掉。 */
    private fun isInfiniteValue(expr: IExpr?): Boolean =
        expr != null && (expr.isInfinity || expr.isNegativeInfinity || expr.isDirectedInfinity)

    /**
     * 是实数、有限，才算拿到了结果。
     *
     * 注意不能只认 `isNumber`：`E`、`Pi` 这类常数在 Symja 里是符号，不是 INum，
     * 但 `evalDouble()` 能给值（取对数改写的目标就是 `e`）。反过来，符号表达式
     * 和复数求不出有限实数，会被挡掉。
     */
    private fun finiteDouble(expr: IExpr?): Double? {
        if (expr == null) return null
        if (expr.isIndeterminate) return null
        val value = try {
            expr.evalDouble()
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        return if (value.isFinite()) value else null
    }
}
