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
        // 最终值同样要过一遍数值对拍：Symja 对 `x/sin x` 这类会给出一个错的有限值。
        // 带字母参数时数值通道给不出数（`a` 是自由的），但精确结果可能是含参数的
        // 表达式（`lim (1+ax)^(1/x) = e^a`），这时改用符号对拍通道逐项验证。
        if (finalExpr == null || isInfinite(finalExpr)) return null
        val finalDouble = LimitFallback.resultNumber(engine, formula, finalExpr)
        val resolvedSymbolicAnswer =
            LimitFallback.symbolicAnswer(engine, formula, finalExpr) ||
                !finalLatex.contains("\\lim_")
        if (finalDouble == null && !resolvedSymbolicAnswer) {
            return null
        }

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
        val substitutedValue = evalDouble(substituted)
        if (substitutedValue != null && substitutedValue.isFinite()) {
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
            Form.ONE_POWER_INF -> powerLogSteps(engine, input, finalExpr, finalDouble, finalLatex)
            else -> symbolicEvaluatedStep(engine, input, finalExpr)
                ?: equivalenceSteps(engine, input, finalExpr, finalDouble)
                ?: lHopitalSteps(engine, input, finalExpr, finalDouble)
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

    /**
     * 带参数的题：0/0 或 ∞/∞ 且引擎直接给出了含参数的精确结果
     * （`lim sin(ax)/x = a`、`lim x/(x+a) = 0` 这类）。
     *
     * 这类题走不了数值对拍通道（参数是自由的），但分子分母分别求导后的比式
     * 能用符号对拍验到同一个结果，就展示一步洛必达——学生看到的是
     * `cos(ax)·a` 这种能心算的中间式，而不只是答案。
     */
    private fun symbolicEvaluatedStep(
        engine: SymjaEngine,
        input: LimitInput,
        finalExpr: IExpr,
    ): List<ProcessStep>? {
        val finalDouble = evalDouble(finalExpr)
        if (finalDouble != null && finalDouble.isFinite()) return null
        if (!finalExpr.isFree(engine.symbol(input.variable))) return null
        val parts = splitQuotient(input.body) ?: return null
        val numLimit = limitOf(engine, parts.first, input) ?: return null
        val denLimit = limitOf(engine, parts.second, input) ?: return null
        val form = when {
            numLimit.isZero && denLimit.isZero -> Form.ZERO_OVER_ZERO
            isInfinite(numLimit) && isInfinite(denLimit) -> Form.INF_OVER_INF
            else -> return null
        }
        val numPrime = derivativeOf(engine, parts.first, input.variable) ?: return null
        val denPrime = derivativeOf(engine, parts.second, input.variable) ?: return null
        val nextBody = F.eval(F.Divide(numPrime, denPrime))
        // 换元/求导后的比值式还要在极限点上取极限，才能和最终结果比；
        // 直接对表达式做数值对拍会把"函数值"当成"极限值"（`sin(ax)/x` 对不上 `a`）。
        if (!nextBodyLimitAgrees(engine, input, nextBody, finalExpr)) return null
        val currentTex = engine.toExactLatex(input.body) ?: return null
        val nextTex = engine.toExactLatex(nextBody) ?: return null
        return listOf(
            ProcessStep(
                "lhopital",
                "洛必达法则",
                listOf(
                    "T:${form.label}，分子分母分别求导",
                    "${limitTex(engine, input, currentTex)}=${limitTex(engine, input, nextTex)}",
                ),
            ),
        )
    }

    /**
     * 洛必达比值式在极限点的极限，和引擎给出的（含参数）精确结果是不是一回事。
     * 参数是自由的，数值通道代不进去——先给参数代采样值、再对 `x` 取极限，
     * 逐轮比较两边算出来的数。
     */
    private fun nextBodyLimitAgrees(
        engine: SymjaEngine,
        input: LimitInput,
        nextBody: IExpr,
        finalExpr: IExpr,
    ): Boolean {
        val variable = engine.symbol(input.variable)
        val symbols = linkedSetOf<IExpr>()
        NumericCheck.collectSymbols(nextBody, symbols)
        NumericCheck.collectSymbols(finalExpr, symbols)
        symbols.remove(variable)
        var checked = 0
        for (round in SYMBOL_POINTS.indices) {
            val nextCode = symbolSubstituted(
                nextBody.toString(), symbols.toList(), round,
            ) ?: continue
            val finalCode = symbolSubstituted(
                finalExpr.toString(), symbols.toList(), round,
            ) ?: continue
            val point = pointForLimit(engine, input) ?: continue
            val left = LimitFallback.valueAt(engine, nextCode, input.variable, point)
            val right = engine.numericValueOf(finalCode)
            if (left == null || right == null) continue
            checked++
            val scale = maxOf(1.0, kotlin.math.abs(left), kotlin.math.abs(right))
            if (kotlin.math.abs(left - right) > 1e-6 * scale) return false
        }
        return checked > 0
    }

    /**
     * 极限点的数值：实数点直接用；±∞ 用远远超出其余参数的大数近似
     * （对 `x→∞` 的有理式足够，且 [NumericCheck] 的采样点都在 (0, 4) 内）。
     */
    private fun pointForLimit(engine: SymjaEngine, input: LimitInput): Double? {
        val point = evalDouble(input.point)
        if (point != null && point.isFinite()) return point
        return when {
            input.point.isInfinity -> 1e6
            input.point.isNegativeInfinity -> -1e6
            else -> null
        }
    }

    /** [LimitFallback] 数值兜底也要用同一个极限点口径（±∞ 用大数近似）。 */
    internal fun pointForLimit(engine: SymjaEngine, limit: LimitFallback.Unevaluated): Double? = when {
        limit.point.isFinite() -> limit.point
        limit.point > 0 -> 1e6
        else -> -1e6
    }

    /** 给符号按轮次代采样值；拿不到轮次（点不够）返回 null。 */
    private fun symbolSubstituted(
        code: String,
        symbols: List<IExpr>,
        round: Int,
    ): String? {
        if (symbols.isEmpty()) return code
        var out = code
        for ((index, symbol) in symbols.withIndex()) {
            val point = SYMBOL_POINTS[(index + round) % SYMBOL_POINTS.size]
            out = "($out) /. $symbol -> $point"
        }
        return out
    }

    private fun equivalenceSteps(
        engine: SymjaEngine,
        input: LimitInput,
        finalExpr: IExpr,
        finalDouble: Double?,
    ): List<ProcessStep>? {
        val rewritten = rewriteEquivalents(engine, input) ?: return null
        val value = limitOf(engine, rewritten.first, input) ?: return null
        if (!matchesFinal(engine, input, value, finalExpr, finalDouble)) return null
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
        finalExpr: IExpr,
        finalDouble: Double?,
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
            if (!matchesFinal(engine, input, value, finalExpr, finalDouble)) break
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
        finalExpr: IExpr,
        finalDouble: Double?,
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
            if (!matchesFinal(engine, input, value, finalExpr, finalDouble)) return null
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

    /**
     * 中间结论和最终结果是不是同一个东西。
     *
     * 纯数值时走原来的浮点对拍；结果含自由参数（`a`、`k` 这类）时改走
     * [NumericCheck] 的符号对拍——逐轮给所有自由符号代采样值，两边算出来对得上
     * 才放行。`limit (1+ax)^(1/x) = e^a` 这种题目的等价无穷小/洛必达分支
     * 以前在数值通道拿不到数就整段放弃，现在能验得过。
     */
    private fun matchesFinal(
        engine: SymjaEngine,
        input: LimitInput,
        value: IExpr,
        finalExpr: IExpr,
        finalDouble: Double?,
    ): Boolean {
        val v = evalDouble(value)
        if (v != null && v.isFinite() && finalDouble != null) {
            val scale = maxOf(1.0, kotlin.math.abs(v), kotlin.math.abs(finalDouble))
            return kotlin.math.abs(v - finalDouble) <= 1e-8 * scale
        }
        if (finalDouble != null) return false
        // 两边都还有极限变量时不能代值硬比（比的是函数值、不是极限值）——
        // 交给 [symbolicEvaluatedStep] 那条通道处理。
        if (!value.isFree(engine.symbol(input.variable))) return false
        return NumericCheck.agrees(engine, value, finalExpr, SYMBOL_POINTS)
    }

    /** 符号对拍的采样点：避开 0/±1 这些常见奇点。 */
    private val SYMBOL_POINTS = doubleArrayOf(0.6, 0.3, 1.4, 2.3, 3.7)

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
            val value = expr.evalDouble()
            if (value.isFinite()) value else null
        } catch (e: Throwable) {
            null
        }
    }
}
