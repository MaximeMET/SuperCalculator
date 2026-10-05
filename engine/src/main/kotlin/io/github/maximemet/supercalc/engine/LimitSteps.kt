package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 极限的「解决过程」：直接代入 → 不定式判型 → 等价无穷小 / 洛必达 / 取对数 → 结果。
 *
 * 原版没有极限的过程（过程引擎只管方程/不等式），这一份是补的。做法：
 *  - 先把极限点代入，代入得动（连续）就直接写结果；
 *  - 代入得到 0/0、∞/∞ 时先试等价无穷小替换，替换后的极限必须和最终值一致才展示；
 *  - 再退到洛必达法则（最多两次），每次派生出来的极限同样要和最终值对拍；
 *  - 1^∞ 型走取对数改写（和 [LimitFallback] 的改写同一条路）。
 *
 * 对拍用的是数值：只有引擎（或 [LimitFallback]）给得出可信结果时才出过程，
 * 中间结论和最终值对不上的一律不展示——宁可少一步，不给错的。
 */
object LimitSteps {

    /** 洛必达最多连续用两次，再多说明这条路不适合手算展示。 */
    private const val MAX_LHOPITAL = 2

    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): String? = ProcessSteps.toJson(build(engine, formula, originalLatex) ?: emptyList())

    fun build(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): List<ProcessStep>? = try {
        buildInner(engine, formula, originalLatex)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private class LimitInput(val body: IExpr, val variable: String, val point: IExpr) {
        val pointText: String get() = point.toString()
    }

    private enum class Form(val label: String) {
        ZERO_OVER_ZERO("0/0 型"),
        INF_OVER_INF("∞/∞ 型"),
        ONE_POWER_INF("1^∞ 型"),
    }

    private fun buildInner(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val input = parseLimit(engine, formula) ?: return null
        val finalExpr = engine.evaluateOrNull(engine.parseOrNull(formula))
        val finalLatex = finalLatexOf(engine, formula) ?: return null
        // 最终值同样要过一遍数值对拍：Symja 对 `x/sin x` 这类会给出一个错的有限值
        val finalDouble = LimitFallback.resultNumber(engine, formula, finalExpr) ?: return null
        if (!finalDouble.isFinite()) return null

        val bodyTex = engine.toExactLatex(input.body) ?: return null
        val variableTex = engine.toExactLatex(engine.symbol(input.variable)) ?: input.variable
        val pointTex = engine.toExactLatex(input.point) ?: input.pointText
        val original = originalLatex?.takeIf { it.isNotBlank() }?.let { LatexText.editorSafe(it) }
            ?: "\\lim_{$variableTex\\to $pointTex}$bodyTex"

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep("original", "原式", listOf(original))

        // 1. 代入：连续函数直接给值
        val substituted = engine.evaluateOrNull(
            engine.parseOrNull("(${input.body}) /. ${input.variable} -> (${input.pointText})")
        )
        if (substituted != null && evalDouble(substituted)?.isFinite() == true) {
            val valueTex = engine.toExactLatex(substituted) ?: return null
            steps += ProcessStep(
                "substitute",
                "代入",
                listOf(
                    "T:函数在 ${input.variable} = ${pointDisplay(input)} 处连续，直接代入",
                    "\\left.$bodyTex\\right|_{$variableTex=$pointTex}=$valueTex",
                ),
            )
            steps += ProcessStep("result", "计算结果", listOf("= $finalLatex"))
            return steps
        }

        // 2. 不定式判型
        val form = classifyForm(engine, input)
        steps += ProcessStep(
            "substitute",
            "代入",
            listOf("T:直接代入得 ${form?.label ?: "不定式"}，不能直接求值"),
        )

        // 3. 技巧：1^∞ 取对数；其余先等价无穷小，再洛必达
        val technique = when (form) {
            Form.ONE_POWER_INF -> powerLogSteps(engine, input, finalDouble, finalLatex)
            else -> equivalenceSteps(engine, input, finalDouble)
                ?: lHopitalSteps(engine, input, finalDouble)
        }
        if (technique != null) steps += technique

        steps += ProcessStep("result", "计算结果", listOf("= $finalLatex"))
        return steps
    }

    private fun parseLimit(engine: SymjaEngine, formula: String): LimitInput? {
        val parsed = engine.parseOrNull(formula) ?: return null
        if (!parsed.isAST(F.Limit)) return null
        val limit = parsed as IAST
        // 带 Direction 的单侧极限先不做步骤
        if (limit.size != 3) return null
        val rule = limit.arg2() as? IAST ?: return null
        if (!rule.isAST(F.Rule) || rule.size != 3) return null
        if (!rule.arg1().isSymbol) return null
        return LimitInput(limit.arg1(), rule.arg1().toString(), rule.arg2())
    }

    /**
     * 与结果页同源的最终结果：精确通道 + 数值对拍，未求值走 [LimitFallback] 的改写。
     * （Symja 对 `lim x→0 x/sinx` 会给 0 这个错值，见 [LimitFallback.resultLatex]。）
     */
    private fun finalLatexOf(engine: SymjaEngine, formula: String): String? {
        val raw = engine.evaluateAsLatex(formula)
        if (raw.isEmpty()) return null
        return LimitFallback.resultLatex(engine, formula, raw)
    }

    // ---------- 判型 ----------

    private fun classifyForm(engine: SymjaEngine, input: LimitInput): Form? {
        splitQuotient(input.body)?.let { (numerator, denominator) ->
            val numLimit = limitOf(engine, numerator, input)
            val denLimit = limitOf(engine, denominator, input)
            if (numLimit?.isZero == true && denLimit?.isZero == true) return Form.ZERO_OVER_ZERO
            if (isInfinite(numLimit) && isInfinite(denLimit)) return Form.INF_OVER_INF
        }
        val ast = input.body as? IAST
        if (ast != null && ast.isAST(F.Power) && ast.size == 3) {
            val baseLimit = limitOf(engine, ast.arg1(), input)
            val exponentLimit = limitOf(engine, ast.arg2(), input)
            if (baseLimit?.isOne == true && isInfinite(exponentLimit)) return Form.ONE_POWER_INF
        }
        return null
    }

    private fun isInfinite(expr: IExpr?): Boolean =
        expr != null && (expr.isInfinity || expr.isNegativeInfinity || expr.isDirectedInfinity)

    // ---------- 等价无穷小 ----------

    private fun equivalenceSteps(
        engine: SymjaEngine,
        input: LimitInput,
        finalDouble: Double,
    ): List<ProcessStep>? {
        val rewritten = rewriteEquivalents(engine, input) ?: return null
        val value = limitOf(engine, rewritten.first, input) ?: return null
        if (!matchesFinal(engine, value, finalDouble)) return null
        val rewrittenTex = engine.toExactLatex(rewritten.first) ?: return null
        val valueTex = engine.toExactLatex(value) ?: return null
        val bodyTex = engine.toExactLatex(input.body) ?: return null
        val lines = rewritten.second.map { "T:$it" } + listOf(
            "${limitTex(engine, input, bodyTex)}" +
                "=${limitTex(engine, input, rewrittenTex)}=$valueTex",
        )
        return listOf(ProcessStep("equivalentInfinitesimal", "等价无穷小替换", lines))
    }

    /**
     * 把 0 附近的常见等价无穷小换掉：
     * sin/tan/arcsin/arctan(u) ~ u、ln(1+u) ~ u、e^u-1 ~ u、1-cos(u) ~ u²/2。
     *
     * 每条替换都要求内层 u 确实趋于 0（用引擎的极限判断），否则不动。
     */
    private fun rewriteEquivalents(
        engine: SymjaEngine,
        input: LimitInput,
    ): Pair<IExpr, List<String>>? {
        val notes = mutableListOf<String>()
        var changed = false

        fun walk(node: IExpr): IExpr {
            val ast = node as? IAST ?: return node
            replacementFor(engine, input, ast)?.let { (replacement, note) ->
                changed = true
                notes += note
                return replacement
            }
            var any = false
            val args = ArrayList<IExpr>(ast.size - 1)
            for (i in 1 until ast.size) {
                val child = ast.get(i)
                val newChild = walk(child)
                if (newChild !== child) any = true
                args += newChild
            }
            return if (any) F.ast(args.toTypedArray(), ast.head()) else ast
        }

        val rewritten = walk(input.body)
        if (!changed) return null
        return rewritten to notes
    }

    private fun replacementFor(
        engine: SymjaEngine,
        input: LimitInput,
        ast: IAST,
    ): Pair<IExpr, String>? {
        // 规则包：sin/tan/arcsin/arctan/sh/th/arsh(u) ~ u、ln(1+u) ~ u 这类直接替换
        // （内层 u 仍要确实趋于 0，否则不动）
        for (rule in LimitRulePack.rules) {
            val hit = LimitRulePack.apply(engine, ast, rule, input.variable) ?: continue
            val (bound, replacement, text) = hit
            if (tendsToZero(engine, input, bound)) {
                return replacement to note(input, text)
            }
        }

        if (ast.size == 2) {
            val arg = ast.arg1()
            if (ast.isAST(F.Log)) {
                onePlus(arg)?.let { rest ->
                    if (tendsToZero(engine, input, rest)) {
                        return rest to note(input, "ln(1+$rest) ~ $rest")
                    }
                }
            }
        }
        oneMinusCos(ast)?.let { rest ->
            if (tendsToZero(engine, input, rest)) {
                val half = F.eval(F.Divide(F.Power(rest, F.C2), F.C2))
                return half to note(input, "1-cos(${rest}) ~ (${rest})^2/2")
            }
        }
        expMinusOne(ast)?.let { rest ->
            if (tendsToZero(engine, input, rest)) {
                return rest to note(input, "e^(${rest})-1 ~ ${rest}")
            }
        }
        return null
    }

    /** `Log(1+u)` 的 u；不是这个形状返回 null。 */
    private fun onePlus(expr: IExpr): IExpr? {
        val ast = expr as? IAST ?: return null
        if (!ast.isAST(F.Plus)) return null
        val terms = mutableListOf<IExpr>()
        for (i in 1 until ast.size) {
            val term = ast.get(i)
            val child = term as? IAST
            if (child != null && child.isAST(F.Plus)) {
                for (j in 1 until child.size) terms += child.get(j)
            } else {
                terms += term
            }
        }
        val one = terms.firstOrNull { it.isOne } ?: return null
        val rest = terms.filter { it !== one }
        if (rest.isEmpty()) return null
        return if (rest.size == 1) rest.first() else F.Plus(*rest.toTypedArray())
    }

    /** `1-cos(u)` 的 u（也认 `-(cos(u)-1)`）。 */
    private fun oneMinusCos(node: IAST): IExpr? {
        val negated = node.isAST(F.Times) && node.size >= 2 && node.arg1().isMinusOne
        val body: IExpr = if (negated) {
            val rest = (2 until node.size).map { node.get(it) }
            when (rest.size) {
                0 -> return null
                1 -> rest.first()
                else -> F.Times(*rest.toTypedArray())
            }
        } else {
            node
        }
        val plus = body as? IAST ?: return null
        if (!plus.isAST(F.Plus)) return null
        var seenOne = false
        var cosArg: IExpr? = null
        for (i in 1 until plus.size) {
            val term = plus.get(i)
            if (term.isOne) {
                seenOne = true
                continue
            }
            if (negated) {
                val termAst = term as? IAST ?: return null
                if (!termAst.isAST(F.Cos) || termAst.size != 2) return null
                cosArg = termAst.arg1()
            } else {
                val termAst = term as? IAST ?: return null
                if (!termAst.isAST(F.Times) || termAst.size < 2 ||
                    !termAst.arg1().isMinusOne
                ) {
                    return null
                }
                val factors = (2 until termAst.size).map { termAst.get(it) }
                if (factors.isEmpty()) return null
                val inner = if (factors.size == 1) {
                    factors.first()
                } else {
                    F.Times(*factors.toTypedArray())
                }
                val innerAst = inner as? IAST ?: return null
                if (!innerAst.isAST(F.Cos) || innerAst.size != 2) return null
                cosArg = innerAst.arg1()
            }
        }
        if (!seenOne) return null
        return cosArg
    }

    /** `e^u - 1` 的 u（Plus 里有一项是 -1，另一项是 E^u）。 */
    private fun expMinusOne(ast: IAST): IExpr? {
        val plus = ast as? IAST ?: return null
        if (!plus.isAST(F.Plus)) return null
        var minusesOne = false
        var exponent: IExpr? = null
        for (i in 1 until plus.size) {
            val term = plus.get(i)
            if (term.isMinusOne) {
                minusesOne = true
                continue
            }
            val termAst = term as? IAST ?: return null
            if (!termAst.isAST(F.Power) || termAst.size != 3 || !termAst.arg1().isE) return null
            exponent = termAst.arg2()
        }
        if (!minusesOne || exponent == null) return null
        return exponent
    }

    private fun tendsToZero(engine: SymjaEngine, input: LimitInput, expr: IExpr): Boolean =
        limitOf(engine, expr, input)?.isZero == true

    private fun note(input: LimitInput, text: String): String =
        "当 ${input.variable}→${pointDisplay(input)} 时，$text"

    // ---------- 洛必达 ----------

    private fun lHopitalSteps(
        engine: SymjaEngine,
        input: LimitInput,
        finalDouble: Double,
    ): List<ProcessStep>? {
        val steps = mutableListOf<ProcessStep>()
        var current = input.body
        var applications = 0
        while (applications < MAX_LHOPITAL) {
            val parts = splitQuotient(current) ?: break
            val numLimit = limitOf(engine, parts.first, input)
            val denLimit = limitOf(engine, parts.second, input)
            val form = when {
                numLimit?.isZero == true && denLimit?.isZero == true -> Form.ZERO_OVER_ZERO
                isInfinite(numLimit) && isInfinite(denLimit) -> Form.INF_OVER_INF
                else -> break
            }
            val numPrime = derivativeOf(engine, parts.first, input.variable) ?: break
            val denPrime = derivativeOf(engine, parts.second, input.variable) ?: break
            val nextBody = F.Divide(numPrime, denPrime)
            val value = limitOf(engine, nextBody, input) ?: break
            if (!matchesFinal(engine, value, finalDouble)) break
            applications++
            val currentTex = engine.toExactLatex(current) ?: break
            val nextTex = engine.toExactLatex(nextBody) ?: break
            val valueTex = engine.toExactLatex(value) ?: break
            steps += ProcessStep(
                "lhopital",
                "洛必达法则",
                listOf(
                    "T:${form.label}，分子分母分别求导",
                    "${limitTex(engine, input, currentTex)}" +
                        "=${limitTex(engine, input, nextTex)}=$valueTex",
                ),
            )
            current = nextBody
        }
        if (applications == 0) return null
        return steps
    }

    private fun derivativeOf(engine: SymjaEngine, expr: IExpr, variable: String): IExpr? {
        val value = engine.evaluateOrNull(
            engine.parseOrNull("Diff(($expr), $variable)")
        ) ?: return null
        if (value.toString().contains("Derivative(")) return null
        return value
    }

    // ---------- 1^∞ 取对数 ----------

    private fun powerLogSteps(
        engine: SymjaEngine,
        input: LimitInput,
        finalDouble: Double,
        finalLatex: String,
    ): List<ProcessStep>? {
        val ast = input.body as? IAST ?: return null
        if (!ast.isAST(F.Power) || ast.size != 3) return null
        val base = ast.arg1()
        val exponent = ast.arg2()
        val logged = F.Times(exponent, F.Log(base))
        val bodyTex = engine.toExactLatex(input.body) ?: return null
        val loggedTex = engine.toExactLatex(logged) ?: return null

        // 能算出改写后的对数极限时给完整三步；算不出来（∞ 点上的 x·ln(1+1/x) 就是）
        // 就只展示改写本身——这是一条恒等变形，不编造中间值。
        val loggedLimit = limitOf(engine, logged, input)
        if (loggedLimit != null) {
            val value = engine.evaluateOrNull(F.eval(F.Power(F.E, loggedLimit))) ?: return null
            if (!matchesFinal(engine, value, finalDouble)) return null
            val loggedLimitTex = engine.toExactLatex(loggedLimit) ?: return null
            val valueTex = engine.toExactLatex(value) ?: return null
            return listOf(
                ProcessStep(
                    "powerLog",
                    "取对数",
                    listOf(
                        "T:1^∞ 型，取对数改写",
                        "\\ln L=${limitTex(engine, input, loggedTex)}=$loggedLimitTex",
                        "${limitTex(engine, input, bodyTex)}=e^{$loggedLimitTex}=$valueTex",
                    ),
                ),
            )
        }
        return listOf(
            ProcessStep(
                "powerLog",
                "取对数",
                listOf(
                    "T:1^∞ 型，取对数改写",
                    "\\ln L=${limitTex(engine, input, loggedTex)}",
                    "${limitTex(engine, input, bodyTex)}=$finalLatex",
                ),
            ),
        )
    }

    // ---------- 工具 ----------

    /** 表达式在极限点处的（精确）极限；求不出来返回 null。 */
    private fun limitOf(engine: SymjaEngine, expr: IExpr, input: LimitInput): IExpr? {
        val code = "Limit(($expr), ${input.variable} -> ${input.pointText})"
        val value = engine.evaluateOrNull(engine.parseOrNull(code)) ?: return null
        if (value.isIndeterminate) return null
        if (value.toString().contains("Limit(")) return null
        return value
    }

    private fun matchesFinal(engine: SymjaEngine, value: IExpr, finalDouble: Double): Boolean {
        val v = evalDouble(value) ?: return false
        if (!v.isFinite()) return false
        val scale = maxOf(1.0, kotlin.math.abs(v), kotlin.math.abs(finalDouble))
        return kotlin.math.abs(v - finalDouble) <= 1e-8 * scale
    }

    private fun numericValue(engine: SymjaEngine, input: LimitInput): Double? {
        val point = evalDouble(input.point) ?: return null
        return LimitFallback.valueAt(engine, input.body.toString(), input.variable, point)
    }

    private fun pointDisplay(input: LimitInput): String = when {
        input.point.isInfinity -> "∞"
        input.point.isNegativeInfinity -> "-∞"
        else -> input.pointText
    }

    private fun limitTex(engine: SymjaEngine, input: LimitInput, bodyTex: String): String {
        val variableTex = engine.toExactLatex(engine.symbol(input.variable)) ?: input.variable
        val pointTex = engine.toExactLatex(input.point) ?: input.pointText
        return "\\lim_{$variableTex\\to $pointTex}\\left($bodyTex\\right)"
    }

    /** 分母：含负幂因子时把分母拆出来（`sin(x)/x` -> Sin(x) 与 x）。 */
    private fun splitQuotient(body: IExpr): Pair<IExpr, IExpr>? {
        val ast = body as? IAST ?: return null
        val factors = flatTimes(ast) ?: return null
        val inverse = factors.filter { isNegativePower(it) }
        if (inverse.isEmpty()) return null
        val numeratorFactors = factors.filterNot { isNegativePower(it) }
        if (numeratorFactors.isEmpty()) return null
        return timesOf(numeratorFactors) to timesOf(inverse.map { reciprocalBase(it) })
    }

    private fun flatTimes(ast: IAST): List<IExpr>? {
        if (!ast.isAST(F.Times)) return null
        val out = mutableListOf<IExpr>()
        for (i in 1 until ast.size) {
            val child = ast.get(i)
            val childAst = child as? IAST
            if (childAst != null && childAst.isAST(F.Times) && childAst.size >= 2) {
                out.addAll(flatTimes(childAst) ?: return null)
            } else {
                out += child
            }
        }
        return out
    }

    private fun isNegativePower(expr: IExpr): Boolean {
        val ast = expr as? IAST ?: return false
        if (!ast.isAST(F.Power) || ast.size != 3) return false
        val exponent = ast.arg2()
        return exponent.isNumber && exponent.isNegative
    }

    private fun reciprocalBase(expr: IExpr): IExpr {
        val ast = expr as IAST
        val exponent = ast.arg2()
        return if (exponent.isMinusOne) ast.arg1()
        else F.Power(ast.arg1(), F.eval(F.Negate(exponent)))
    }

    private fun timesOf(factors: List<IExpr>): IExpr = when (factors.size) {
        0 -> F.C1
        1 -> factors.first()
        else -> F.Times(*factors.toTypedArray())
    }

    private fun evalDouble(expr: IExpr?): Double? {
        if (expr == null) return null
        return try {
            expr.evalDouble()
        } catch (e: Exception) {
            null
        }
    }
}
