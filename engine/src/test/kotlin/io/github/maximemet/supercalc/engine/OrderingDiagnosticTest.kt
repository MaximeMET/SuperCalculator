package io.github.maximemet.supercalc.engine

import org.matheclipse.core.eval.EvalAttributes
import org.matheclipse.core.eval.EvalEngine
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.convert.AST2Expr
import org.matheclipse.parser.client.Parser
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test

/** 诊断：降幂排序补丁为什么在两个参数的 Plus 上抛 NPE。 */
class OrderingDiagnosticTest {

    @Test
    fun diagnose() {
        val sb = StringBuilder()
        SymjaEngine() // 触发全局开关初始化
        val engine = EvalEngine(true)

        sb.appendLine("===== 完整求值栈（TexForm[x^2-1]） =====")
        try {
            val node = Parser(true).parse("TexForm[x^2-1]")
            val ex = AST2Expr.CONST_LC.convert(node)
            val r = EvalEngine(true).evaluate(ex)
            sb.appendLine("结果 = $r")
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.appendLine(sw.toString())
        }
        sb.appendLine()

        for (text in listOf("x^2-1", "x^2+2*x+1", "2*x+3")) {
            sb.appendLine("===== $text =====")
            val expr = try {
                engine.parse(text)
            } catch (e: Exception) {
                sb.appendLine("解析失败: $e")
                continue
            }
            val ast = expr as? IAST
            sb.appendLine("AST 类: ${expr.javaClass.name}  size=${ast?.size}")
            if (ast != null) {
                for (i in 0 until ast.size) {
                    sb.appendLine("  [$i] = ${runCatching { ast.get(i) }.getOrNull()}")
                }
                val argsList = runCatching { ast.args() }
                sb.appendLine("  args() = ${argsList.getOrNull()}")
                sb.appendLine("  args() 类 = ${argsList.getOrNull()?.javaClass?.name}")
                try {
                    EvalAttributes.sort(ast, true)
                    sb.appendLine("  降幂排序后再看: " + (0 until ast.size).joinToString(" | ") { "$it=${ast.get(it)}" })
                } catch (t: Throwable) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    sb.appendLine("  降幂排序抛异常:\n$sw")
                }
            }
            sb.appendLine()
        }

        val out = File("build/diagnostic/ordering.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}
