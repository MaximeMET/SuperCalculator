package io.github.maximemet.supercalc.engine

import org.matheclipse.core.convert.AST2Expr
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.ISymbol
import org.matheclipse.parser.client.Parser
import java.io.File
import kotlin.test.Test

/**
 * 诊断：解析器造出来的符号和 F.$s(...) 到底是不是同一个。
 *
 * 这个结论决定了「方法推荐」能不能正确识别未知数，所以单独留一个诊断测试。
 * 结果写到 build/diagnostic/symbols.txt（UTF-8，避免控制台编码干扰）。
 */
class SymbolDiagnosticTest {

    private val sb = StringBuilder()
    private fun line(s: String) = sb.append(s).append('\n')

    private fun legacyParse(engine: SymjaEngine, text: String): IExpr {
        val node = Parser(true).parse(text)
        return AST2Expr(engine.evalEngine).convert(node)
    }

    @Test
    fun diagnose() {
        val engine = SymjaEngine()
        val out = File("build/diagnostic/symbols.txt")
        out.parentFile.mkdirs()

        val sX = F.`$s`("x")
        val fX: IExpr = F.x
        line("F.\$s(\"x\") === F.x      : ${sX === fX}")
        line("F.\$s(\"x\") 稳定           : ${F.`$s`("x") === F.`$s`("x")}")
        line("F.x class                : ${fX.javaClass.name}")
        line("engine.symbol(x) === F.x : ${engine.symbol("x") === fX}")
        line("engine.symbol 稳定       : ${engine.symbol("x") === engine.symbol("x")}")

        for (text in listOf("x^2", "a^2", "x^2-5*x+6", "x+y")) {
            line("")
            line("===== $text =====")
            val e = legacyParse(engine, text)
            line("expr                     : $e   (${e.javaClass.name})")
            line("isFree(F.\$s(\"x\"))        : ${e.isFree(F.`$s`("x"))}")
            line("isFree(F.x)              : ${e.isFree(fX)}")
            line("isFree(engine.symbol x)  : ${engine.isFreeOf(e, "x")}")
            line("variables()              : ${e.variables().map { it.symbolName }}")
            line("isPolynomial(engine x)   : ${e.isPolynomial(engine.symbol("x"))}")
            val ast = e as? IAST
            if (ast != null && ast.arg1().isSymbol) {
                val sym = ast.arg1() as ISymbol
                val ref = engine.symbol(sym.symbolName)
                line("arg1                     : '${sym.symbolName}' hash=${sym.hashCode()} ")
                line("arg1 === engine.symbol   : ${sym === ref}")
                line("arg1 ==  engine.symbol   : ${sym == ref}")
                line("ref  hash                : ${ref.hashCode()}")
            }
        }

        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}

