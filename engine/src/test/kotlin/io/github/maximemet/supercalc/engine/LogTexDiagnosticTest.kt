package io.github.maximemet.supercalc.engine

import org.matheclipse.core.basic.Config
import org.matheclipse.core.convert.AST2Expr
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.parser.client.Parser
import java.io.File
import kotlin.test.Test

/** 诊断：为什么 Log 的 TeX 转换器没有被调用。 */
class LogTexDiagnosticTest {

    @Test
    fun diagnose() {
        val engine = SymjaEngine()
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')

        line("Config.PARSER_USE_LOWERCASE_SYMBOLS = ${Config.PARSER_USE_LOWERCASE_SYMBOLS}")
        line("AST2Expr.TIMES_STRING                = ${AST2Expr.TIMES_STRING}")
        line("PREDEFINED_SYMBOLS_MAP['log']        = ${AST2Expr.PREDEFINED_SYMBOLS_MAP["log"]}")
        line("PREDEFINED_SYMBOLS_MAP['Log']        = ${AST2Expr.PREDEFINED_SYMBOLS_MAP["Log"]}")

        val converterClass = "org.matheclipse.core.form.tex.reflection.Log"
        line("反射目标类是否存在                    = ${runCatching { Class.forName(converterClass) }.isSuccess}")

        for (text in listOf("ln(2)", "log10(2)", "log2(8)", "sqrt(2)", "Log(2)")) {
            line("")
            line("===== $text =====")
            val expr = runCatching { AST2Expr.CONST_LC.convert(Parser(true).parse(text)) }.getOrNull()
            line("解析结果      : $expr  (${expr?.javaClass?.name})")
            val ast = expr as? IAST
            if (ast != null) {
                line("head          : ${ast.head()}  名称=" + headName(ast))
                line("参数个数      : ${ast.size - 1}")
            }
            line("TeX           : ${engine.evaluateAsLatex(text)}")
        }

        val out = File("build/diagnostic/logtex.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun headName(ast: IAST): String {
        val h = ast.head()
        return if (h.isSymbol) {
            (h as org.matheclipse.core.interfaces.ISymbol).symbolName
        } else {
            h.toString()
        }
    }
}
