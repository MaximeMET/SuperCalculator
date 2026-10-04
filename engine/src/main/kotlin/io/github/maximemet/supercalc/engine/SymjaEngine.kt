package io.github.maximemet.supercalc.engine

import org.matheclipse.core.basic.Config
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.TeXUtilities
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.ISymbol
import org.matheclipse.parser.client.SyntaxError
import java.io.ByteArrayOutputStream
import java.io.StringWriter

/**
 * 符号计算内核。
 *
 * 封装了 6 个基础算子（积分、求导、展开、因式分解、求解、数值化）
 * 以及两条输出通道：
 *  - [toLatex]   直接对表达式做 TeX 转换（WebView 结果区用）
 *  - [evaluateRaw] 走 `TexForm(...)` 求值（方法按钮用）
 *
 * 注意：这两条通道在原版里是并存的，输出细节略有差异，不能合并。
 */
class SymjaEngine {

    val evalEngine: EvalEngine = EvalEngine(true)

    private val texUtilities = TeXUtilities(evalEngine, true)

    companion object {
        /** 小于这个量级的数直接当 0 处理，避免出现 `1.0E-17` 这种噪声。 */
        private val DELTA: IExpr by lazy { F.num("1E-10") }

        /**
         * 编辑器 ∞ 键的 symja 输出（原版 MathQuill 的 symjaTemplate 就是小写 `infty`），
         * 而 Symja 只认 `Infinity`：不换掉的话 `Limit(1/x,x->infty)` 里的 infty
         * 会被当成一个普通符号，极限永远算不出来（积分上界同理）。
         */
        private val INFINITY_SYMBOL = Regex("""\binfty\b""")

        /** 把编辑器风格的符号换成 Symja 认的写法。 */
        fun normalizeFormula(text: String): String = INFINITY_SYMBOL.replace(text, "Infinity")

        init {
            // 与原版一致的全局开关：
            // 1) 允许 log2、arcsin 这类全小写函数名被识别为内置函数
            // 2) 让 JAS 走单线程，避免 Android 上的线程开销
            Config.PARSER_USE_LOWERCASE_SYMBOLS = true
            Config.JAS_NO_THREADS = true
            F.initSymbols(null, null, true)
        }
    }

    // ---------- 解析与求值 ----------

    /** 解析失败一律返回 null，不抛异常——这是「输入一半不会崩」的关键。 */
    fun parseOrNull(formula: String): IExpr? = try {
        evalEngine.parse(normalizeFormula(formula))
    } catch (e: SyntaxError) {
        null
    } catch (e: StackOverflowError) {
        null
    } catch (e: Exception) {
        null
    }

    /** 求值，[numeric] 为 true 时走高精度数值模式。 */
    fun evaluateOrNull(formula: IExpr?, numeric: Boolean = false): IExpr? {
        if (formula == null) return null
        return try {
            evalEngine.setNumericMode(numeric)
            if (numeric) evalEngine.setNumericPrecision(EngineSettings.precision)
            evalEngine.evaluate(formula)
        } catch (e: StackOverflowError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 取符号。
     *
     * 2016 版的符号表是全局的，`F.$s(name)` 拿到的就是解析器用的那个实例。
     * （新版 Symja 改成了按 EvalEngine 隔离，必须用 `F.symbol(name, engine)`，
     * 这也是不能随便升版本的原因之一。）
     */
    fun symbol(name: String): ISymbol = F.`$s`(name)

    /** 未知数符号。 */
    fun unknownSymbol(): ISymbol = symbol(EngineSettings.unknown)

    /** 表达式是否不含某个符号。 */
    fun isFreeOf(expr: IExpr, name: String): Boolean = expr.isFree(symbol(name))

    // ---------- 六个基础算子 ----------

    fun integrate(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Integrate.buildFormula(formula, EngineSettings.unknown)))

    fun diff(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Derivative.buildFormula(formula, EngineSettings.unknown)))

    fun expand(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Expand.buildFormula(formula, EngineSettings.unknown)))

    fun decompose(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Decompose.buildFormula(formula, EngineSettings.unknown)))

    fun solve(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Solve.buildFormula(formula, EngineSettings.unknown)))

    fun num(formula: String): IExpr? =
        evaluateOrNull(parseOrNull(Method.Numeric.buildFormula(formula, EngineSettings.unknown)))

    // ---------- 输出通道 ----------

    /** 表达式 -> LaTeX，附带精度处理与噪声归零。 */
    fun toLatex(rawExpr: IExpr?): String? {
        if (rawExpr == null) return null
        var expr = rawExpr
        if (expr.isNumber && expr.abs().isLEOrdered(DELTA)) {
            expr = F.num(0.0)
        }
        val writer = StringWriter()
        texUtilities.toTeX(expr, writer)
        val latex = writer.toString()
        val fixed = LatexText.toFixPoint(latex, EngineSettings.precision)
        return LatexText.withoutScientificNotation(fixed)
    }

    /**
     * 表达式 -> LaTeX，科研排版用的「原样」通道：不做噪声归零，也不做小数截断。
     *
     * 结果区要的是「算出来的数」，`1.0E-17` 当然要归零；但解题步骤里显示的是
     * 方程系数这类精确值，`0` 不能变成 `0.0`，否则「代入 a=1，b=0，c=-2」会写成
     * `b=0.0`，在步骤里很扎眼。
     */
    fun toExactLatex(rawExpr: IExpr?): String? {
        if (rawExpr == null) return null
        val writer = StringWriter()
        texUtilities.toTeX(rawExpr, writer)
        return LatexText.withoutScientificNotation(writer.toString())
    }

    /**
     * 走 `TexForm(...)` 通道求值，返回已经去掉引号、修好精度与科学计数法的 LaTeX。
     * 这是方法按钮（积分/求导/…）显示结果时走的路径。
     */
    fun evaluateAsLatex(symjaFormula: String): String {
        val raw = evaluateRaw(MethodConsts.SYMJA_LATEX.format(symjaFormula))
        val cleaned = LatexText.withoutScientificNotation(
            LatexText.toFixPoint(LatexText.replaceQuotes(raw), EngineSettings.precision)
        )
        return cleaned.replace("infty", "\\infty")
    }

    /** 直接在同一个引擎里求值一段 Symja 代码并取回文本输出。 */
    fun evaluateRaw(code: String): String {
        val stream = ByteArrayOutputStream()
        return try {
            SymjaInterpreter(normalizeFormula(code), stream, evalEngine).eval()
            // 原版跑在 Android 上，换行一律是 \n；桌面 JVM 的 PrintStream 会给 \r\n，
            // 不拉齐的话差分测试会在「除零提示」这类多行输出上误报。
            stream.toString(Charsets.UTF_8.name()).replace("\r\n", "\n")
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- 表达式分类 ----------

    fun isInvalid(expr: IExpr?): Boolean =
        expr == null || expr.isInfinity || expr.isDirectedInfinity ||
            expr.isNegativeInfinity || expr.isIndeterminate

    // ---------- 给不等式求解器用的小工具 ----------

    /** 求值一段 Symja 代码，返回结果的 input form；算不出来返回 null。 */
    fun stringOf(code: String): String? =
        evaluateOrNull(parseOrNull(code))?.toString()

    /** 求 `expr == 0` 的全部根；解不出或含参数解时返回空表。 */
    fun solveZeros(expr: String, unknown: String): List<IExpr>? {
        val solved = evaluateOrNull(parseOrNull("Solve($expr==0,$unknown)")) ?: return emptyList()
        if (!solved.isAST(F.List)) return null
        val roots = mutableListOf<IExpr>()
        val outer = solved as IAST
        for (i in 1 until outer.size) {
            val item = outer.get(i)
            if (!item.isAST(F.List)) return null
            val inner = item as IAST
            for (j in 1 until inner.size) {
                val rule = inner.get(j)
                if (!rule.isAST(F.Rule)) return null
                val ast = rule as IAST
                if (ast.size != 3) return null
                // 带参数的解（比如 x == a）没法拿来划分区间，放弃
                if (!isFreeOf(ast.arg2(), unknown)) return null
                roots.add(ast.arg2())
            }
        }
        return roots
    }

    /**
     * 求一段表达式的数值；不是有限的数就返回 null。
     *
     * 注意要把无穷排掉：`1/0.0` 会得到 `Infinity`，而 `"Infinity".toDoubleOrNull()`
     * 是能成功的（正无穷），拿它去判号会把断点误判成解的一部分。
     */
    fun numericValueOf(code: String): Double? {
        val value = evaluateOrNull(parseOrNull(code), numeric = true) ?: return null
        if (!value.isNumber) return null
        val d = value.toString().toDoubleOrNull() ?: return null
        return if (d.isFinite()) d else null
    }

    /** 把 `x = value` 代进 f，返回数值；代不进去（比如落在断点上）返回 null。 */
    fun signAt(f: String, unknown: String, value: Double): Double? =
        numericValueOf("($f) /. $unknown -> $value")

    fun isEqOrUneq(expr: IExpr?): Boolean {
        if (expr == null) return false
        return expr.isAST(F.Equal) || expr.isAST(F.Less) || expr.isAST(F.Greater) ||
            expr.isAST(F.GreaterEqual) || expr.isAST(F.LessEqual)
    }

    fun hasUneq(expr: IExpr?): Boolean {
        if (expr == null) return false
        return !(expr.isFree(F.Less) && expr.isFree(F.Greater) &&
            expr.isFree(F.GreaterEqual) && expr.isFree(F.LessEqual))
    }
}
