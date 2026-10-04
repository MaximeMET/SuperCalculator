package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 纯算式的解题步骤：通分、合并、约分。
 *
 * 原版没有这一类「解决过程」（它的过程引擎只管解方程、方程组、不等式），
 * 这一份是按同样的问题意识自己补的：`1/2+1/3` 到底怎么变成 `5/6`。
 *
 * 覆盖范围：
 *  - 若干分数相加减：通分 → 合并 → 约分；
 *  - 分数相乘除：相乘 → 约分；
 *  - 其它纯算式：只给「原式 → 结果」（按钮仍然常驻，行为可预期）。
 *
 * 实现上有两个坑，都是当时踩出来的：
 *  1. Symja 的 TeX 通道会**求值**：`1/2+1/3+1/6` 拿去排版会变成 `1/6+5/6`，
 *     所以「原式」这一行用编辑器给的 LaTeX，分数式子自己拼，不走 TeX 通道；
 *  2. 求值会**就地改** AST（我们给 EvalEngine 打的降幂补丁会重排 Plus），
 *     所以结构分析在求值之前做完，分析用的树和求值用的树分开解析。
 */
object ArithmeticSteps {

    private val UNKNOWN_SYMBOLS = listOf("x", "y", "z")

    /**
     * 生成步骤；不是纯算式、或者算不出来时返回 null。
     *
     * [originalLatex] 是编辑器里那份 LaTeX（用户实际敲的样子），
     * 拿不到时退回表达式的输入形式。
     */
    fun build(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): List<ProcessStep>? {
        val analysis = engine.parseOrNull(formula) ?: return null
        if (!isPureArithmetic(engine, analysis)) return null

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep(
            "original",
            "原式",
            listOf(originalLatex?.takeIf { it.isNotBlank() }?.let { LatexText.editorSafe(it) } ?: formula),
        )

        // 结构分析必须在求值之前：求值会就地重排 / 折叠 AST
        val sumSteps = fractionSumSteps(engine, analysis)
        if (sumSteps != null) {
            steps += sumSteps
        } else {
            val productSteps = fractionProductSteps(engine, analysis, formula)
            if (productSteps != null) {
                steps += productSteps
            } else {
                evalSteps(engine, analysis)?.let { steps += it }
            }
        }

        // 求值用另一棵解析树，别动上面那棵
        val evaluated = engine.evaluateOrNull(engine.parseOrNull(formula)) ?: return null
        if (engine.isInvalid(evaluated)) return null
        val resultTex = engine.toExactLatex(evaluated) ?: return null
        if (steps.last().lines.lastOrNull() != "= $resultTex") {
            steps += ProcessStep("result", "计算结果", listOf("= $resultTex"))
        }
        return steps
    }

    /** 表达式是不是纯算式（不含 x/y/z，也不是等式/不等式）。 */
    private fun isPureArithmetic(engine: SymjaEngine, expr: IExpr): Boolean {
        if (!expr.isFree(F.Equal) || !expr.isFree(F.Less) || !expr.isFree(F.Greater) ||
            !expr.isFree(F.LessEqual) || !expr.isFree(F.GreaterEqual)
        ) {
            return false
        }
        if (!expr.isFree(F.Limit) || !expr.isFree(F.NIntegrate)) return false
        return UNKNOWN_SYMBOLS.all { engine.isFreeOf(expr, it) }
    }

    // ---------- 加减：通分、合并、约分 ----------

    private fun fractionSumSteps(engine: SymjaEngine, analysis: IExpr): List<ProcessStep>? {
        val ast = analysis as? IAST ?: return null
        if (!ast.isAST(F.Plus)) return null
        // 解析出来的是嵌套的 Plus（`1/2+1/3+1/6` = Plus(1/2, Plus(1/3, 1/6))），先摊平
        val termTexts = flatPlusTerms(ast) ?: return null
        if (termTexts.size < 2) return null
        val fractions = termTexts.map { fractionOf(engine, it) ?: return null }
        // 全是整数就没必要通分
        if (fractions.all { it.second == 1L }) return null
        val lcd = fractions.fold(1L) { acc, f -> lcm(acc, f.second) }
        if (lcd == 1L) return null
        if (fractions.all { it.second == lcd }) return null // 已经同分母

        val scaled = fractions.map { (num, den) -> num * (lcd / den) }
        val converted = termTexts.zip(fractions)
            .filter { (text, f) -> f.second != lcd || text.contains('.') }
            .map { (text, f) ->
                "${termTex(engine, text)} = ${fractionTex(f.first * (lcd / f.second), lcd)}"
            }

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep(
            "commonDenominator",
            "通分",
            converted + "= \\frac{${sumTex(scaled)}}{$lcd}",
        )

        val combined = scaled.sum()
        steps += ProcessStep("combine", "合并", listOf("= ${fractionTex(combined, lcd)}"))

        val divisor = gcd(kotlin.math.abs(combined), lcd)
        if (divisor > 1) {
            steps += ProcessStep(
                "reduce",
                "约分",
                listOf(
                    "T:分子分母同时除以 $divisor",
                    "= ${fractionTex(combined / divisor, lcd / divisor)}",
                ),
            )
        }
        return steps
    }

    /** 把嵌套的 Plus 摊成项文本表；遇到减号包住的子式（`-(1/3+1/6)`）就放弃。 */
    private fun flatPlusTerms(ast: IAST): List<String>? {
        val out = mutableListOf<String>()
        for (i in 1 until ast.size) {
            val child = ast.get(i)
            val childAst = child as? IAST
            if (childAst != null && childAst.isAST(F.Plus)) {
                out.addAll(flatPlusTerms(childAst) ?: return null)
            } else {
                out.add(child.toString().trim())
            }
        }
        return out
    }

    // ---------- 乘除：相乘、约分 ----------

    private fun fractionProductSteps(
        engine: SymjaEngine,
        analysis: IExpr,
        formula: String,
    ): List<ProcessStep>? {
        // 单个分数（`6/8`）在解析阶段就被约掉了，原样得从文本里取
        val fromText = simpleFraction(formula.trim())
        val raw = fromText ?: rawFraction(analysis) ?: return null
        val (num, den) = raw
        if (den == 1L) return null
        val divisor = gcd(kotlin.math.abs(num), den)
        if (divisor <= 1) return null

        val steps = mutableListOf<ProcessStep>()
        val ast = analysis as? IAST
        val isProduct = fromText == null && ast != null && ast.isAST(F.Times) && ast.size >= 3
        if (isProduct) {
            steps += ProcessStep("multiply", "相乘", listOf("= ${fractionTex(num, den)}"))
        }
        steps += ProcessStep(
            "reduce",
            "约分",
            listOf(
                "T:分子分母同时除以 $divisor",
                "= ${fractionTex(num / divisor, den / divisor)}",
            ),
        )
        return steps
    }

    /**
     * 原样的分数结构（不做约分）：只认整数、`a/b` 这种乘幂结构、以及有限小数。
     */
    private fun rawFraction(expr: IExpr): Pair<Long, Long>? {
        val text = expr.toString().trim()
        simpleFraction(text)?.let { return it }
        decimalToFraction(text)?.let { return it }
        if (expr.isInteger) {
            return text.toLongOrNull()?.let { it to 1L }
        }
        val ast = expr as? IAST
        if (ast != null && ast.isAST(F.Times) && ast.size >= 3) {
            var num = 1L
            var den = 1L
            for (i in 1 until ast.size) {
                val child = rawFraction(ast.get(i)) ?: return null
                num *= child.first
                den *= child.second
            }
            return num to den
        }
        if (ast != null && ast.isAST(F.Power) && ast.size == 3) {
            val exponent = ast.arg2()
            if (!exponent.isInteger) return null
            val value = exponent.toString().toLongOrNull() ?: return null
            if (value != -1L) return null
            val base = rawFraction(ast.arg1()) ?: return null
            if (base.first != 1L || base.second != 1L) {
                return base.second to base.first
            }
        }
        return null
    }

    /** 一个项当前的数值——分数（含有限小数化来的）。 */
    private fun fractionOf(engine: SymjaEngine, termText: String): Pair<Long, Long>? {
        simpleFraction(termText)?.let { return it }
        decimalToFraction(termText)?.let { return it }
        val value = engine.evaluateOrNull(engine.parseOrNull(termText)) ?: return null
        val text = value.toString().trim()
        decimalToFraction(text)?.let { return it }
        if (value.isInteger) return text.toLongOrNull()?.let { it to 1L }
        if (!value.isFraction) return null
        val num = engine.stringOf("Numerator($text)")?.toLongOrNull() ?: return null
        val den = engine.stringOf("Denominator($text)")?.toLongOrNull() ?: return null
        return num to den
    }

    // ---------- 小工具 ----------

    /**
     * 没有分数结构的纯算式：把顶层加减/乘除里能先算的子式先算出来，
     * 例如 `2+3*4` -> `3\times 4 = 12` -> 结果。
     */
    private fun evalSteps(engine: SymjaEngine, analysis: IExpr): List<ProcessStep>? {
        val ast = analysis as? IAST ?: return null
        if (!ast.isAST(F.Plus) && !ast.isAST(F.Times)) return null
        if (ast.size < 3) return null
        val lines = mutableListOf<String>()
        for (i in 1 until ast.size) {
            val child = ast.get(i) as? IAST ?: continue
            if (child.size < 3 || child.isAST(F.Power)) continue
            val childTex = engine.toExactLatex(child) ?: continue
            val value = engine.evaluateOrNull(engine.parseOrNull(child.toString())) ?: continue
            val valueTex = engine.toExactLatex(value) ?: continue
            if (childTex != valueTex) lines += "$childTex = $valueTex"
        }
        if (lines.isEmpty()) return null
        return listOf(ProcessStep("evaluate", "逐步计算", lines))
    }

    /** 一个项的 LaTeX：能认成简单分数就自己排，否则请引擎排版（会求值，仅用于显示）。 */
    private fun termTex(engine: SymjaEngine, text: String): String {
        simpleFraction(text)?.let { (num, den) -> return fractionTex(num, den) }
        val value = engine.evaluateOrNull(engine.parseOrNull(text))
        if (value != null && value.isFraction) {
            val num = engine.stringOf("Numerator($value)")?.toLongOrNull()
            val den = engine.stringOf("Denominator($value)")?.toLongOrNull()
            if (num != null && den != null && den != 0L) return fractionTex(num, den)
        }
        return engine.toExactLatex(engine.parseOrNull(text)) ?: text
    }

    /** `a/b` 的 LaTeX；分母是 1 时直接给整数。 */
    private fun fractionTex(num: Long, den: Long): String = when {
        den == 1L -> num.toString()
        num < 0 -> "-\\frac{${-num}}{$den}"
        else -> "\\frac{$num}{$den}"
    }

    /** 分子求和式：`3+2+1` / `5-3`。 */
    private fun sumTex(values: List<Long>): String = buildString {
        values.forEachIndexed { index, value ->
            when {
                index == 0 && value < 0 -> append("-${-value}")
                index == 0 -> append(value)
                value < 0 -> append(" - ${-value}")
                else -> append(" + $value")
            }
        }
    }

    private val SIMPLE_FRACTION = Regex("""(-?\d+)\s*/\s*(\d+)""")

    private fun simpleFraction(text: String): Pair<Long, Long>? {
        val match = SIMPLE_FRACTION.matchEntire(text.trim()) ?: return null
        val num = match.groupValues[1].toLongOrNull() ?: return null
        val den = match.groupValues[2].toLongOrNull() ?: return null
        if (den == 0L) return null
        return num to den
    }

    /** `0.25` -> `1/4`；不是有限小数则返回 null。 */
    private fun decimalToFraction(text: String): Pair<Long, Long>? {
        if (!DECIMAL.matches(text)) return null
        val negative = text.startsWith('-')
        val body = text.removePrefix("-")
        val digits = body.replace(".", "")
        val places = body.length - body.indexOf('.') - 1
        var num = digits.toLongOrNull() ?: return null
        var den = 1L
        repeat(places) { den *= 10 }
        val divisor = gcd(num, den)
        num /= divisor
        den /= divisor
        return (if (negative) -num else num) to den
    }

    private val DECIMAL = Regex("""-?\d+\.\d+""")

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    private fun lcm(a: Long, b: Long): Long =
        if (a == 0L || b == 0L) 0L else kotlin.math.abs(a / gcd(a, b) * b)
}
