package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IExpr

/**
 * 一次计算会话：持有当前表达式，负责「输入 -> 提示结果 -> 给出可用按钮 -> 执行按钮」。
 *
 * 对应参考实现里的 SymjaManager（单例）。这里做成普通类，方便测试里并行开多份。
 */
class CalculationSession(private val engine: SymjaEngine = SymjaEngine()) {

    private val advisor = MethodAdvisor(engine)

    /** 当前光标所在行的 Symja 表达式。 */
    var formula: String = ""
        private set

    /** 当前表达式的 LaTeX 形式（编辑器里的显示）。 */
    var latex: String = ""
        private set

    /** 换行符之前的那些行（方程组/不等式组用）。 */
    var lastFormula: String = ""
        private set

    private var lastPreview: String = ""

    fun setFormula(formula: String, latex: String) {
        this.formula = formula
        this.latex = latex
        this.lastFormula = ""
        this.lastPreview = ""
    }

    fun clear() = setFormula("", "")

    /** 当前表达式适合显示哪些运算按钮。 */
    fun availableMethods(needCalc: Boolean = true): List<Method> =
        advisor.advise(formula, lastFormula, needCalc)

    /**
     * 执行一个运算动作，返回可渲染的 LaTeX。
     *
     * 空白或引擎算不出来时返回 null，由界面决定怎么提示。
     */
    fun evaluate(method: Method): String? {
        if (formula.isEmpty()) return null
        val symjaFormula = method.buildFormula(formula, EngineSettings.unknown)
        val raw = engine.evaluateAsLatex(symjaFormula)
        if (raw.isEmpty()) return null
        return formatOutput(method, raw)
    }

    /** 直接在 WebView 结果区用的表达式求值（走 TexForm 通道）。 */
    fun evaluateCurrentAsLatex(): String = engine.evaluateAsLatex(formula)

    /**
     * 输入停顿时的自动结果预览。
     *
     * 只有**不含任何未知数**的常量表达式才会给预览——含未知数的式子交给方法按钮，
     * 这样界面不会一边打字一边闪现半成品结果。
     */
    fun autoResult(): String {
        if (formula.isEmpty()) return ""
        if (formula.contains("**")) return ""

        val expr = engine.parseOrNull(formula) ?: return ""
        if (!isConstantExpression(expr)) return ""

        // 纯数字、含积分符号的表达式：只回一个分隔符，表示"没有额外提示"
        if ((expr.isNumber && !expr.isFraction) ||
            !expr.isFree(F.NIntegrate) || !expr.isFree(F.Integrate)
        ) {
            return MethodConsts.DIVIDER
        }

        val resultExpr = engine.evaluateOrNull(expr) ?: return ""
        if (engine.isInvalid(resultExpr)) return ""

        val numeric = runCatching {
            engine.evaluateAsLatex(MethodConsts.numeric(formula, EngineSettings.precision))
        }
            .getOrDefault("")

        var exact = ""
        val exactLatex = engine.evaluateAsLatex(formula)
        if (isFinal(exactLatex)) {
            exact = "= $exactLatex"
        } else if (numeric.isEmpty()) {
            return ""
        }

        // 结果是纯数：给出小数近似
        if (resultExpr.isNumber && !resultExpr.isFraction) {
            val dms = degreeMinuteSecond(expr, numeric)
            lastPreview = dms + MethodConsts.DIVIDER + exact
            return lastPreview
        }

        // 等式/不等式，或仍然含未知数：只给精确形式
        if (engine.isEqOrUneq(expr) || !resultExpr.isFree(engine.unknownSymbol())) {
            lastPreview = exact
            return lastPreview
        }

        var preview = exact
        val dms = degreeMinuteSecond(expr, numeric)
        if (dms.isNotEmpty()) preview += dms

        var combined = preview + MethodConsts.DIVIDER
        if (isFinal(numeric)) combined += "= $numeric"
        lastPreview = combined
        return lastPreview
    }

    /**
     * 结果有效性判定（参考实现的黑名单）。
     *
     * 含错误文本、未展开的积分／极限、或 `**` 的结果都不算最终结果。
     */
    fun isFinal(result: String): Boolean {
        if (result.isEmpty()) return false
        val p = result.indexOf("rror")
        if (p > 0 && (result[p - 1] == 'E' || result[p - 1] == 'e')) return false
        if (result.contains("**")) return false
        if (result.contains("\\int ")) return false
        if (result.contains("\\lim_")) return false
        return result.indexOf("\\text{") < 0
    }

    // ---------- 内部工具 ----------

    private fun isConstantExpression(expr: IExpr): Boolean {
        val symbols = listOf(
            EngineSettings.unknown, "x", "y", "z", "a", "b", "c", "h", "k", "p"
        )
        return symbols.all { engine.isFreeOf(expr, it) }
    }

    /**
     * 反三角函数的数值结果额外给出度分秒。
     *
     * 参考实现只在 `arc…` 或 `DegreeMinuteSecond` 出现时才这么做。
     */
    private fun degreeMinuteSecond(expr: IExpr, numeric: String): String {
        val needsDms = formula.contains("arc") ||
            (formula.contains("DegreeMinuteSecond") &&
                !formula.contains("sin") && !formula.contains("cos") && !formula.contains("tan"))
        if (!needsDms) return ""

        val degrees = numeric.toDoubleOrNull() ?: return ""
        val total = degrees * 57.29577951308232
        val biased = if (total > 0) total + 1.0E-8 else total - 1.0E-8
        val d = biased.toInt()
        val minutesTotal = Math.abs(biased - d) * 60.0
        val m = minutesTotal.toInt()
        val s = String.format("%.0f", (minutesTotal - m) * 60.0)
        return "= {$d}^\\circ{$m}^\\prime{$s}^\\pprime"
    }

    /**
     * 各运算动作的结果收尾处理。
     *
     * 注意 `\\infty` 的双反斜杠是刻意保留的：`evaluateAsLatex` 会把 `infty`
     * 补成 `\\infty`，这里再收回成单反斜杠，两步是一对。
     */
    private fun formatOutput(method: Method, output: String): String {
        var cleaned = output
            .replace("Indeterminate expression", "")
            .replace("\"", "")
            .replace("\\\\infty", "\\infty")

        when (method) {
            Method.Integrate -> cleaned += " + C"
            Method.Solve, Method.Solve2, Method.SolveIneq, Method.SolveIneq2 -> {
                cleaned = cleaned.replace("\\to", "=")
                if (cleaned == "\\{\\}") cleaned = "无解"
                else if (cleaned.endsWith(",p\\}")) {
                    cleaned = cleaned.dropLast(3) + "等无数解\\}"
                }
            }
            Method.DInte -> cleaned = cleaned
                .replace("NIntegrate", "DIntegrate")
                .replace("infty", "infinity")
            else -> Unit
        }
        return cleaned
    }
}
