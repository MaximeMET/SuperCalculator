package io.github.maximemet.supercalc.engine

import kotlin.test.Test

/**
 * 输出对照表。
 *
 * 这个测试不打断言，只把引擎的真实输出打印出来，
 * 用于和基准 App 的实测结果逐条比对、再把确认过的结果固化成断言测试。
 */
class EngineDumpTest {

    private val expressions = listOf(
        "1/3",
        "2+3*4",
        "sqrt(2)",
        "pi",
        "sin(30*Degree)",
        "sin(5degree)",
        "x^2",
        "x^2-1",
        "1/x",
        "sin(x)",
        "log2(8)",
        "x^2==1",
        "2*x+3==7",
        "x^2-5*x+6==0",
        "x^2>4",
        "1/3+1/7",
        "10!",
        "ln(e)",
    )

    @Test
    fun dumpOutputs() {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        val session = CalculationSession()
        line("=".repeat(110))
        line(String.format("%-16s %-26s %-38s %s", "输入", "可用按钮", "按钮结果", "自动预览"))
        line("=".repeat(110))

        for (raw in expressions) {
            session.setFormula(raw, raw)
            val methods = session.availableMethods().joinToString(",") { it.label }
            val auto = session.autoResult().replace("\n", "\\n")

            val primary = listOf(Method.Numeric, Method.Derivative, Method.Integrate)
                .firstOrNull { it in session.availableMethods() }
            val buttonResult = primary?.let { runCatching { session.evaluate(it) }.getOrNull() } ?: ""

            line(String.format("%-16s %-26s %-38s %s", raw, methods, buttonResult, auto))
        }
        line("=".repeat(110))

        val out = java.io.File("build/diagnostic/engine-dump.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}
