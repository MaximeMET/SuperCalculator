package io.github.maximemet.supercalc.engine

import org.matheclipse.core.convert.AST2Expr
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.eval.EvalUtilities
import org.matheclipse.core.form.output.OutputFormFactory
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.parser.client.Parser
import org.matheclipse.parser.client.SyntaxError
import org.matheclipse.parser.client.ast.ASTNode
import java.io.OutputStream
import java.io.PrintStream

/**
 * 把「一段 Symja 代码」求值成文本。
 *
 * 这是取 `N(...)`、`TexForm(...)` 这类结果输出的通道：
 * 表达式先被包装成函数调用，求值后再用 OutputForm 打印成字符串。
 *
 * 解析策略与参考实现一致——先用宽松语法，失败再退回严格语法配合方括号形式，
 * 这样用户输入 `log2(8)` 和 `log2[8]` 都能算。
 */
class SymjaInterpreter(
    private val codeString: String,
    out: OutputStream,
    private val engine: EvalEngine,
) {

    private val outStream: PrintStream =
        if (out is PrintStream) out else PrintStream(out, false, Charsets.UTF_8.name())

    private val utilities = EvalUtilities(engine, false, true)
    private val relaxedParser = Parser(true)
    private val strictParser = Parser()
    init {
        engine.setOutPrintStream(outStream)
    }

    /** 求值并可选择在外面再包一层函数，返回 OutputForm 文本。 */
    fun interpret(function: String = ""): String {
        val body = if (function.isEmpty()) codeString else "$function($codeString)"
        val node = parseOrNull(body) ?: return ""

        val buf = StringBuilder()
        try {
            val expr: IExpr = AST2Expr.CONST_LC.convert(node)
            val result = utilities.evaluate(expr)
            OutputFormFactory.get(true).convert(buf, result)
        } catch (e: RuntimeException) {
            appendError(buf, e.cause?.takeIf { it is org.matheclipse.parser.client.math.MathException } ?: e)
        } catch (e: Exception) {
            appendError(buf, e)
        }
        return buf.toString()
    }

    /** 求值并写入输出流（原版 `eval` 的行为）。 */
    fun eval(function: String = "") {
        outStream.print(interpret(function))
    }

    private fun parseOrNull(text: String): ASTNode? {
        try {
            return relaxedParser.parse(text)
        } catch (e: SyntaxError) {
            // 宽松语法失败时退回严格语法，并把函数调用写成 f[...] 形式
            return try {
                strictParser.parse(text.replaceFirst("(", "[").let { swapClosingBracket(it) })
            } catch (e2: Exception) {
                outStream.println(e2.message)
                null
            }
        }
    }

    private fun swapClosingBracket(text: String): String {
        val idx = text.lastIndexOf(')')
        return if (idx >= 0) text.substring(0, idx) + "]" + text.substring(idx + 1) else text
    }

    private fun appendError(buf: StringBuilder, e: Throwable) {
        val msg = e.message
        buf.append("\nError: ").append(msg ?: e.javaClass.simpleName)
    }
}
