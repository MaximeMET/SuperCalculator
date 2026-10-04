package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 积分步骤的回归测试。
 *
 * 形状优先：钉住每类被积函数至少给出哪条技术路线、最后有「计算结果 + C」；
 * 每条能出步骤的用例本身就意味着逐项原函数通过了「求导回验」。
 */
class IntegrateStepsTest {

    private val engine = SymjaEngine()

    private fun stepsOf(formula: String) = IntegrateSteps.build(engine, formula)

    private fun keysOf(formula: String): List<String> = stepsOf(formula)?.map { it.key } ?: emptyList()

    private fun textOf(formula: String): String =
        stepsOf(formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    @Test
    fun powerUsesBasicTable() {
        val keys = keysOf("Integrate(x^2, x)")
        assertTrue("basicTable" in keys, "缺少基本积分表：$keys")
        assertTrue("result" in keys, "缺少结果：$keys")
        assertTrue(textOf("Integrate(x^2, x)").contains("幂函数"), "幂函数公式没写出来")
        assertTrue(textOf("Integrate(x^2, x)").contains("+ C"), "结果没有 + C")
    }

    @Test
    fun sumUsesLinearity() {
        val keys = keysOf("Integrate(2*x+3, x)")
        assertTrue("linearity" in keys, "缺少线性性：$keys")
        assertTrue("basicTable" in keys, "缺少基本积分表：$keys")
    }

    @Test
    fun sinAndCosUseBasicTable() {
        assertTrue("basicTable" in keysOf("Integrate(Sin(x), x)"), "sin 该走基本公式")
        assertTrue("basicTable" in keysOf("Integrate(Cos(x), x)"), "cos 该走基本公式")
        assertTrue("reciprocal" in textOf("Integrate(1/x, x)") || "ln|x|" in textOf("Integrate(1/x, x)"),
            "1/x 该走对数公式")
    }

    @Test
    fun arctanFormUsesBasicTable() {
        val text = textOf("Integrate(1/(1+x^2), x)")
        assertTrue(text.contains("arctan"), "1/(1+x²) 该认出 arctan：$text")
    }

    @Test
    fun compositeFunctionUsesSubstitution() {
        val keys = keysOf("Integrate(E^(2*x), x)")
        assertTrue("substitution" in keys, "缺少第一类换元：$keys")
        val text = textOf("Integrate(E^(2*x), x)")
        assertTrue(text.contains("u=2") && text.contains("du="), "换元行没写出来：$text")
    }

    @Test
    fun productWithExpUsesParts() {
        val keys = keysOf("Integrate(x*E^x, x)")
        assertTrue("integrationByParts" in keys, "缺少分部积分：$keys")
        assertTrue(textOf("Integrate(x*E^x, x)").contains("dv="), "分部说明没写出来")
    }

    @Test
    fun plainTextLinesDoNotLeakLatex() {
        // 纯文本行（T: 前缀）是给用户看的，混进 `\sin{x}` 这类源码就是排版事故
        val cases = listOf(
            "Integrate(x*E^x, x)",
            "Integrate(E^(2*x), x)",
            "Integrate(x*Log(x), x)",
            "Integrate(2*x+3, x)",
            "Integrate(Sin(3*x+1), x)",
        )
        for (raw in cases) {
            val bad = stepsOf(raw)?.flatMap { it.lines }
                ?.filter { it.startsWith("T:") && it.contains("\\") } ?: emptyList()
            assertTrue(bad.isEmpty(), "$raw 的纯文本行混进了 LaTeX：$bad")
        }
    }

    @Test
    fun productWithLogUsesParts() {
        val keys = keysOf("Integrate(x*Log(x), x)")
        assertTrue("integrationByParts" in keys, "缺少分部积分：$keys")
    }

    @Test
    fun squareProductUsesParts() {
        // 注意：x²·eˣ 在 Symja 2016 里会回 Gamma(3,-x)，属于特殊函数，不做步骤；
        // 幂函数×三角是能正常给分部积分的
        val keys = keysOf("Integrate(x^2*Sin(x), x)")
        assertTrue("integrationByParts" in keys, "缺少分部积分：$keys")
    }

    @Test
    fun nonIntegrableGivesNoProcess() {
        // e^(x²) 没有初等原函数，引擎会原样返回 Integrate(...)，不该出过程
        assertTrue(IntegrateSteps.build(engine, "Integrate(E^(x^2), x)") == null, "算不出的不该出步骤")
    }

    @Test
    fun sessionExposesProcessJsonForIntegrate() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("x^2", "x^{2}")
        val json = session.processSteps(Method.Integrate)
        assertTrue(json != null && json.contains("\"steps\""), "会话层拿不到步骤：$json")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            "Integrate(x^2, x)",
            "Integrate(x^2+2*x+1, x)",
            "Integrate(2*x+3, x)",
            "Integrate(Sin(x), x)",
            "Integrate(Cos(x), x)",
            "Integrate(1/x, x)",
            "Integrate(Sqrt(x), x)",
            "Integrate(1/(1+x^2), x)",
            "Integrate(E^(2*x), x)",
            "Integrate(Sin(3*x+1), x)",
            "Integrate(x*E^x, x)",
            "Integrate(x^2*E^x, x)",
            "Integrate(x*Sin(x), x)",
            "Integrate(Log(x), x)",
            "Integrate(x*Log(x), x)",
            "Integrate(E^(x^2), x)",
        )
        val sb = StringBuilder()
        for (raw in cases) {
            sb.append("==== ").append(raw).append('\n')
            val steps = IntegrateSteps.build(engine, raw)
            if (steps == null) {
                sb.append("  （没有步骤）\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        val out = java.io.File("build/diagnostic/integrate-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
