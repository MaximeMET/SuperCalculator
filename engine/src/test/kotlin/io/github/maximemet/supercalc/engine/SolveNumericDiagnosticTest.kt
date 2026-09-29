package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import java.io.File
import kotlin.test.Test

/** 诊断：给 Solve 的根追加数值形式时，用哪种写法才不会提前求值掉。 */
class SolveNumericDiagnosticTest {

    @Test
    fun diagnose() {
        val engine = SymjaEngine()
        val sb = StringBuilder()

        val probes = listOf(
            "N(-i)",
            "N(i)",
            "N(e)",
            "N(sqrt(2))",
            "N((-i)*sqrt(3)-1)",
            "N(-1-i*sqrt(3))",
        )
        for (p in probes) {
            sb.appendLine("===== $p =====")
            sb.appendLine("  TeX : " + engine.evaluateAsLatex(p).replace("\n", "\\n"))
            sb.appendLine()
        }

        // 用表达式 API 拼「x -> (精确 -> 数值)」，绕开解析优先级和提前求值
        sb.appendLine("===== 表达式 API 拼装 =====")
        val x = engine.symbol("x")
        for (text in listOf("sqrt(2)", "(-i)*sqrt(3)-1", "-i", "e", "2", "1/5")) {
            val v = engine.parseOrNull(text) ?: continue
            val n = engine.evaluateOrNull(v, numeric = true) ?: continue
            val wrapped = F.Rule(v, n)
            val list = F.List(F.List(F.Rule(x, wrapped)))
            sb.appendLine("  $text")
            sb.appendLine("    精确: $v   数值: $n")
            sb.appendLine("    TeX : " + engine.toLatex(list)?.replace("\n", "\\n"))
        }

        val out = File("build/diagnostic/solve_numeric.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}
