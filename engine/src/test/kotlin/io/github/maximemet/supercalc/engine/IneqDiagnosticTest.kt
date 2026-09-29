package io.github.maximemet.supercalc.engine

import java.io.File
import kotlin.test.Test

/** 诊断：嵌套列表在 TeX 里长什么样，以及上游有没有能用的不等式求解器。 */
class IneqDiagnosticTest {

    @Test
    fun diagnose() {
        val engine = SymjaEngine()
        val sb = StringBuilder()

        val probes = listOf(
            "{{x>3}}",
            "Solve(Abs(x)-1==0,x)",
            "Together(Abs(x)-1)",
            "Numerator(Together(Abs(x)-1))",
            "Denominator(Together(Abs(x)-1))",
            "Solve(1-x==0,x)",
            "Solve(x==0,x)",
            "Together(1/x-1)",
        )
        for (p in probes) {
            sb.appendLine("===== $p =====")
            sb.appendLine("  TeX : " + engine.evaluateAsLatex(p).replace("\n", "\\n"))
            sb.appendLine("  求值: " + runCatching { engine.evaluateOrNull(engine.parseOrNull(p)) }.getOrNull())
            sb.appendLine()
        }

        sb.appendLine("===== 断点处的取值 =====")
        for (p in listOf("(1/x-(1)) /. x -> 0.0", "(1/x-(1)) /. x -> -1.0", "(1/x-(1)) /. x -> 2.0")) {
            val v = runCatching { engine.evaluateOrNull(engine.parseOrNull(p), numeric = true) }.getOrNull()
            sb.appendLine("  $p  ->  $v   isNumber=${v?.isNumber}  numeric=${engine.numericValueOf(p)}")
        }

        val out = File("build/diagnostic/ineq.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}
