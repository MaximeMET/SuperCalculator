package io.github.maximemet.supercalc.engine

import org.matheclipse.core.basic.Config
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.TeXUtilities
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.ISymbol
import org.matheclipse.parser.client.SyntaxError
import org.matheclipse.parser.client.ParserConfig
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

        init {
            // 与原版一致的全局开关：
            // 1) 允许 log2、arcsin 这类全小写函数名被识别为内置函数
            // 2) 让 JAS 走单线程，避免 Android 上的线程开销
            ParserConfig.PARSER_USE_LOWERCASE_SYMBOLS = true
            Config.JAS_NO_THREADS = true
            F.initSymbols()
        }
    }

    // ---------- 解析与求值 ----------

    /** 解析失败一律返回 null，不抛异常——这是「输入一半不会崩」的关键。 */
    fun parseOrNull(formula: String): IExpr? = try {
        evalEngine.parse(formula)
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
            if (numeric) evalEngine.setNumericPrecision(EngineSettings.precision.toLong())
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
     * 注意必须用 `F.symbol(name, engine)` 而不是 `F.$s(name)`：
     * 新版 Symja 的符号表按 EvalEngine 实例隔离，解析器造出来的符号属于当前引擎，
     * 用全局工厂拿到的会是另一个实例，`isFree`、`variables` 这类判等会全部失效。
     */
    fun symbol(name: String): ISymbol = F.symbol(name, evalEngine)

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
            SymjaInterpreter(code, stream, evalEngine).eval()
            stream.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- 表达式分类 ----------

    fun isInvalid(expr: IExpr?): Boolean =
        expr == null || expr.isInfinity || expr.isDirectedInfinity ||
            expr.isNegativeInfinity || expr.isIndeterminate

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
