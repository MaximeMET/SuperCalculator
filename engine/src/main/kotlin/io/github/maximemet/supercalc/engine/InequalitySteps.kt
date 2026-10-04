package io.github.maximemet.supercalc.engine

/**
 * 不等式的解题步骤：穿线法。
 *
 * 标签直接沿用原版前端 `result.min.js` 里的那份表：
 * 待求解不等式 / 不等式对应的方程 / 穿线法求解验证结果。
 *
 *  1. 待求解不等式
 *  2. 不等式对应的方程：分子零点（等号可能成立的地方）+ 分母零点（断点）
 *  3. 穿线法验证：每个区间取一个点代入判号
 *  4. 解集
 *
 * 不等式组没有现成的原版标签，按同样的节奏拆成：
 * 待求解不等式组 → 逐个求子不等式 → 取交集。
 */
object InequalitySteps {

    fun build(
        engine: SymjaEngine,
        formula: String,
        lastFormula: String,
        method: Method,
        originalLatex: String? = null,
    ): List<ProcessStep>? = try {
        when (method) {
            Method.SolveIneq -> buildForSingle(engine, formula, originalLatex)
            Method.SolveIneq2 -> buildForSystem(engine, formula, lastFormula)
            else -> null
        }
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        lastFormula: String,
        method: Method,
        originalLatex: String? = null,
    ): String? = ProcessSteps.toJson(
        build(engine, formula, lastFormula, method, originalLatex) ?: emptyList()
    )

    // ---------- 一元不等式 ----------

    private fun buildForSingle(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val unknown = InequalitySolver.unknownOf(formula)
        val analysis = InequalitySolver.analyze(engine, formula, unknown) ?: return null

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep(
            "solveUnivaribaleInequality",
            "待求解不等式",
            listOf(
                originalLatex?.takeIf { it.isNotBlank() }
                    ?.let { LatexText.editorSafe(it) }
                    ?: inputLatex(engine, formula),
            ),
        )
        equationLines(engine, analysis, unknown)?.let {
            steps += ProcessStep("solveEquationInequality", "不等式对应的方程", it)
        }
        steps += ProcessStep("checkResult", "穿线法求解验证结果", probeLines(engine, analysis, unknown))
        steps += ProcessStep(
            "result",
            "计算结果",
            listOf(InequalitySolver.render(engine, analysis.branches)),
        )
        return steps
    }

    /** 对应的方程：分子零点 + 分母零点（断点）。 */
    private fun equationLines(
        engine: SymjaEngine,
        analysis: InequalitySolver.Analysis,
        unknown: String,
    ): List<String>? {
        val lines = mutableListOf<String>()
        val numeratorTex = exprLatex(engine, analysis.numerator) ?: return null
        lines += "$numeratorTex = 0"
        rootsLatex(engine, analysis.zeros, unknown)?.let { lines += it }
        if (analysis.denominator != "1" && analysis.poles.isNotEmpty()) {
            val denominatorTex = exprLatex(engine, analysis.denominator) ?: return null
            lines += "T:分母的零点不在定义域内，单独作为断点"
            lines += "$denominatorTex = 0"
            rootsLatex(engine, analysis.poles, unknown)?.let { lines += it }
        }
        return lines
    }

    /** `x_{1} = -1,\quad x_{2} = 1` */
    private fun rootsLatex(engine: SymjaEngine, roots: List<org.matheclipse.core.interfaces.IExpr>, unknown: String): String? {
        if (roots.isEmpty()) return null
        val parts = roots.mapIndexedNotNull { index, root ->
            val tex = engine.toExactLatex(root) ?: return null
            "${unknown}_{${index + 1}} = $tex"
        }
        return parts.joinToString(",\\quad ")
    }

    /** 穿线法：每个区间取一个点代入，看符号。 */
    private fun probeLines(
        engine: SymjaEngine,
        analysis: InequalitySolver.Analysis,
        unknown: String,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (probe in analysis.probes) {
            val pointTex = formatNumber(probe.point)
            val valueTex = exprLatex(
                engine,
                "(${analysis.normalized}) /. $unknown -> ${formatNumber(probe.point)}",
            ) ?: continue
            val comparison = if (probe.value < 0) "< 0" else "> 0"
            lines += "f($pointTex) = $valueTex $comparison"
        }
        return listOf("T:在每个区间取一个点代入 f(x)，判断符号：") + lines
    }

    // ---------- 不等式组 ----------

    private fun buildForSystem(
        engine: SymjaEngine,
        formula: String,
        lastFormula: String,
    ): List<ProcessStep>? {
        val inputs = InequalitySolver.systemInputs(lastFormula, formula)
        if (inputs.size < 2) return null
        val unknown = InequalitySolver.unknownOf(inputs.joinToString(","))
        val branches = InequalitySolver.solveSystem(engine, inputs, unknown) ?: return null

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep(
            "solveEquationInequality",
            "待求解不等式组",
            inputs.map { inputLatex(engine, it) },
        )
        inputs.forEachIndexed { index, input ->
            val lines = mutableListOf(inputLatex(engine, input))
            InequalitySolver.analyze(engine, input, unknown)?.let { analysis ->
                lines += InequalitySolver.render(engine, analysis.branches)
            }
            steps += ProcessStep("subEquation", "求解第 ${index + 1} 个不等式", lines)
        }
        steps += ProcessStep(
            "result",
            "计算结果",
            listOf(InequalitySolver.render(engine, branches)),
        )
        return steps
    }

    // ---------- 小工具 ----------

    private fun inputLatex(engine: SymjaEngine, formula: String): String =
        exprLatex(engine, formula) ?: formula

    private fun exprLatex(engine: SymjaEngine, expr: String): String? {
        val parsed = engine.parseOrNull(expr) ?: return null
        // 先求值再排版：`f(x) /. x -> -2` 这类代换要算完才是数字
        val value = engine.evaluateOrNull(parsed) ?: return null
        return engine.toExactLatex(value)
    }

    /** 取点用的数：整数就写整数，别写成 `-1.0`。 */
    private fun formatNumber(value: Double): String =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
