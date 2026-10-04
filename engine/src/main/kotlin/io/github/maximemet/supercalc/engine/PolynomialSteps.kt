package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 「多项式展开 / 多项式分解」的解决过程。
 *
 * 原版的过程引擎只覆盖方程，这两份是补的，思路和[DerivativeSteps]一致：
 *  - 展开：分配律逐项相乘（幂先拆成乘法）→ 合并同类项；
 *  - 分解：提公因式 → 公式法（形状识别走 `rules/polynomials.json` 规则包：
 *    平方差 / 完全平方 / 立方和 / 立方差 / 十字相乘）→ 结果。
 *
 * 分解的每一步都往**引擎自己的 Factor 输出**上收：`Factor(2x^2-8)` 在 2016 版
 * Symja 里给的是 `(2x-4)(x+2)`（数字系数留在括号里），结果页顶部也是这一串，
 * 过程里就不自作主张换成 `2(x-2)(x+2)`——学生看到的最后一行必须和上面的结果一致。
 * 中间行与原式、结果行与 `Factor` 输出都做恒等校验，验不过不出过程。
 */
object PolynomialSteps {

    /** 分配律展开后最多列几项；再多就只给「合并同类项」一步，免得一行放不下。 */
    private const val MAX_DISTRIBUTED_TERMS = 12

    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
        method: Method,
    ): String? = ProcessSteps.toJson(build(engine, formula, originalLatex, method) ?: emptyList())

    fun build(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
        method: Method,
    ): List<ProcessStep>? = try {
        when (method) {
            Method.Expand -> expandSteps(engine, formula, originalLatex)
            Method.Decompose -> factorSteps(engine, formula, originalLatex)
            else -> null
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    // ---------- 展开 ----------

    private fun expandSteps(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val expr = engine.parseOrNull(formula) ?: return null
        val x = engine.unknownSymbol()
        if (expr.isFree(x) || !expr.isPolynomial(x)) return null
        val factors = expandableFactors(expr) ?: return null
        val expanded = engine.expand(formula) ?: return null
        if (!sameExpr(engine, expanded, expr)) return null
        val expandedTex = engine.toExactLatex(expanded) ?: return null

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep("original", "原式", listOf(shownLatex(engine, originalLatex, expr)))

        val hasSumFactor = factors.any { it.isPlus() }
        if (hasSumFactor) {
            distributedLatex(engine, factors, x)?.let { line ->
                steps += ProcessStep("distribute", "分配律", listOf("=$line"))
            }
        }

        val collectLabel = if (hasSumFactor) "合并同类项" else "化简"
        steps += ProcessStep("collect", collectLabel, listOf("=$expandedTex"))
        steps += ProcessStep("result", "计算结果", listOf("= $expandedTex"))
        return steps
    }

    /**
     * 能按分配律摊开的因式表。
     *
     * `(x+1)(x+2)` -> 两个因式；`(x+1)^3` 先看成三个 `(x+1)` 相乘。
     * 不是乘积/幂（例如已经是展开式的和）返回 null——没有可展开的东西，就不出过程。
     */
    private fun expandableFactors(expr: IExpr): List<IExpr>? {
        val factors = when {
            expr.isTimes() -> argsOf(expr as IAST)
            expr.isPower() -> {
                val ast = expr as IAST
                if (ast.size != 3) return null
                val base = ast.arg1()
                val exponent = ast.arg2()
                if (!base.isPlus() || !exponent.isInteger || exponent.isNegative()) return null
                val count = exponent.toString().toIntOrNull() ?: return null
                if (count < 2 || count > 4) return null
                List(count) { base }
            }
            else -> return null
        }
        return factors.takeIf { it.size >= 2 }
    }

    /**
     * 分配律那一行的 LaTeX，自己拼。
     *
     * 不能走 `toExactLatex`：TeX 通道会先求值，未合并的和式会被打散
     * （`1*1*2+1*1*x+...` 出来是一串没有加号的乘积）。
     * 这里逐项取因式本体排版，`1` 省略、负号提到项首，再按次数从高到低排。
     */
    private fun distributedLatex(engine: SymjaEngine, factors: List<IExpr>, x: IExpr): String? {
        var combos: List<List<IExpr>> = listOf(emptyList())
        for (factor in factors) {
            val terms = if (factor.isPlus()) argsOf(factor as IAST) else listOf(factor)
            if (terms.isEmpty()) return null
            val ordered = terms.sortedByDescending { degreeOf(engine, it, x) }
            combos = combos.flatMap { acc -> ordered.map { acc + it } }
            if (combos.size > MAX_DISTRIBUTED_TERMS) return null
        }
        val parts = combos.map { productLatex(engine, it) ?: return null }
        // 负项自带负号，接在前面时直接连写，避免出现 `+-x` 这种
        val line = parts.reduce { acc, part ->
            if (part.startsWith("-")) acc + part else "$acc+$part"
        }
        return line.takeIf { it.length <= 200 }
    }

    private fun degreeOf(engine: SymjaEngine, term: IExpr, x: IExpr): Int =
        engine.evaluateOrNull(engine.parseOrNull("Exponent(($term), $x)"))
            ?.toString()?.toIntOrNull() ?: 0

    /** 单个乘积项的 LaTeX：`x·x`、`2·x`、`-x·y`，系数 1 省略。 */
    private fun productLatex(engine: SymjaEngine, factors: List<IExpr>): String? {
        var negative = false
        val parts = mutableListOf<String>()
        for (factor in factors) {
            var tex = engine.toExactLatex(factor)?.trim() ?: return null
            if (tex.startsWith("-")) {
                negative = !negative
                tex = tex.substring(1).trim()
            }
            if (tex == "1" || tex.isEmpty()) continue
            parts += tex
        }
        val body = if (parts.isEmpty()) "1" else parts.joinToString("\\cdot ")
        if (!negative) return body
        return if (parts.isEmpty()) "-1" else "-$body"
    }

    private fun argsOf(ast: IAST): List<IExpr> = (1 until ast.size).map { ast.get(it) }

    // ---------- 分解 ----------

    private fun factorSteps(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val expr = engine.parseOrNull(formula) ?: return null
        val x = engine.unknownSymbol()
        if (expr.isFree(x) || !expr.isPolynomial(x)) return null
        val factored = engine.decompose(formula) ?: return null
        if (!sameExpr(engine, factored, expr)) return null
        // 真分解出来才会是乘积/幂；`Factor` 原样返还（只是重排）时不出过程
        if (!factored.isTimes() && !factored.isPower()) return null
        val factoredTex = engine.toExactLatex(factored) ?: return null

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep("original", "原式", listOf(shownLatex(engine, originalLatex, expr)))

        val coefficients = coefficientsOf(engine, expr, x)
        val minPower = coefficients?.indexOfFirst { !it.isZero } ?: 0
        val inner = if (minPower > 0) quotientOf(engine, expr, x, minPower) else expr

        var extracted: IExpr? = null
        if (minPower > 0 && inner != null) {
            val innerTex = engine.toExactLatex(inner)
            if (innerTex != null) {
                val body = if (inner.isPlus()) "\\left($innerTex\\right)" else innerTex
                val power = if (minPower == 1) "x" else "x^{$minPower}"
                steps += ProcessStep("commonFactor", "提公因式", listOf("=$power\\cdot $body"))
                extracted = engine.parseOrNull("($x)^$minPower")?.let { F.Times(it, inner) }
            }
        }

        // 公式描述的是提完公因式后括号里那一份（x³-x → 看 x²-1），但结果行仍旧
        // 用整体 Factor 输出，保证最后一行和结果页顶部一致。
        val techniqueExpr = (if (minPower > 0) inner else expr) ?: expr
        val techniqueFactored = if (minPower > 0 && inner != null) {
            engine.decompose(inner.toString()) ?: factored
        } else {
            factored
        }
        val rule = PolynomialRulePack.technique(engine, techniqueExpr, techniqueFactored, x)
        val label = rule?.label ?: "因式分解"
        val lineAlreadyFactored = extracted != null && sameFactors(extracted, factored)
        if (!lineAlreadyFactored) {
            val lines = mutableListOf<String>()
            rule?.formula?.let { lines += "T:$it" }
            lines += "=$factoredTex"
            steps += ProcessStep("formula", label, lines)
        }

        steps += ProcessStep("result", "计算结果", listOf("= $factoredTex"))
        return steps
    }

    /** 升幂排列的系数表；非多项式返回 null。 */
    private fun coefficientsOf(engine: SymjaEngine, expr: IExpr, x: IExpr): List<IExpr>? {
        val value = engine.evaluateOrNull(engine.parseOrNull("CoefficientList(($expr), $x)"))
            ?: return null
        val ast = value as? IAST ?: return null
        val coefficients = argsOf(ast)
        return coefficients.ifEmpty { null }
    }

    private fun quotientOf(engine: SymjaEngine, expr: IExpr, x: IExpr, power: Int): IExpr? =
        engine.evaluateOrNull(engine.parseOrNull("PolynomialQuotient($expr, $x^$power, $x)"))

    /** 因式重集比较：`x²(1+x)` 与 `(1+x)x²` 视为同一份分解结果。 */
    private fun sameFactors(a: IExpr, b: IExpr): Boolean = factorsOf(a) == factorsOf(b)

    private fun factorsOf(expr: IExpr): List<String> =
        (if (expr.isTimes()) argsOf(expr as IAST) else listOf(expr))
            .map { it.toString() }
            .sorted()

    // ---------- 工具 ----------

    /** 原式行：优先用编辑器给的 LaTeX（保真），没有就引擎排版。 */
    private fun shownLatex(engine: SymjaEngine, originalLatex: String?, expr: IExpr): String =
        originalLatex?.takeIf { it.isNotBlank() }?.let { LatexText.editorSafe(it) }
            ?: engine.toExactLatex(expr)
            ?: expr.toString()

    /**
     * 两个表达式是否恒等：先看求值后的差，再用 Expand 展开差来判断
     * （`(x+2)(x+3)-(x²+5x+6)` 不展开不会自动归零）。
     */
    private fun sameExpr(engine: SymjaEngine, a: IExpr, b: IExpr): Boolean {
        if (F.eval(F.Subtract(a, b)).isZero) return true
        val expanded = engine.evaluateOrNull(engine.parseOrNull("Expand(($a)-($b))")) ?: return false
        return expanded.isZero
    }
}
