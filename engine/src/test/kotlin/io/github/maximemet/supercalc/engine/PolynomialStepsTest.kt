package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 展开 / 分解步骤的回归测试。
 *
 * 形状优先：钉住每类式子至少给出哪条技术路线，最后一行要等于引擎自己的结果。
 */
class PolynomialStepsTest {

    private val engine = SymjaEngine()

    private fun stepsOf(formula: String, method: Method) =
        PolynomialSteps.build(engine, formula, null, method)

    private fun labelsOf(formula: String, method: Method): List<String> =
        stepsOf(formula, method)?.map { it.label } ?: emptyList()

    private fun textOf(formula: String, method: Method): String =
        stepsOf(formula, method)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    @Test
    fun productExpandsWithDistributiveLaw() {
        val labels = labelsOf("(x+1)*(x+2)", Method.Expand)
        assertTrue("分配律" in labels, "缺少分配律：$labels")
        assertTrue("合并同类项" in labels, "缺少合并同类项：$labels")
        val text = textOf("(x+1)*(x+2)", Method.Expand)
        assertTrue(text.contains("x\\cdot x") || text.contains("x\\,x"), "逐项相乘没写出来：$text")
        assertTrue(text.contains("x^{2}+3\\,x+2") || text.contains("x^{2}+3x+2"),
            "展开结果不对：$text")
    }

    @Test
    fun powerExpands() {
        val labels = labelsOf("(x+1)^3", Method.Expand)
        assertTrue("分配律" in labels, "缺少分配律：$labels")
        assertTrue(textOf("(x+1)^3", Method.Expand).contains("x^{3}"), "三次展开结果不对")
    }

    @Test
    fun alreadyExpandedHasNoProcess() {
        assertTrue(stepsOf("x^2+3*x+2", Method.Expand) == null, "已经是展开式，不该有过程")
    }

    @Test
    fun monicQuadraticUsesCrossMultiplication() {
        val labels = labelsOf("x^2+5*x+6", Method.Decompose)
        assertTrue("十字相乘" in labels, "缺少十字相乘：$labels")
        assertTrue(textOf("x^2+5*x+6", Method.Decompose).contains("x+2"),
            "分解结果不对：${textOf("x^2+5*x+6", Method.Decompose)}")
    }

    @Test
    fun differenceOfSquares() {
        val labels = labelsOf("x^2-4", Method.Decompose)
        assertTrue("平方差公式" in labels, "x²-4 该走平方差")
        assertTrue(textOf("x^2-4", Method.Decompose).contains("a²-b²=(a+b)(a-b)"),
            "公式原文没显示：${textOf("x^2-4", Method.Decompose)}")
    }

    @Test
    fun perfectSquare() {
        assertTrue("完全平方公式" in labelsOf("x^2+2*x+1", Method.Decompose), "该走完全平方")
    }

    @Test
    fun cubicDifference() {
        assertTrue("立方和差公式" in labelsOf("x^3-1", Method.Decompose), "x³-1 该走立方差")
    }

    @Test
    fun commonFactorIsPulledOut() {
        val labels = labelsOf("x^3+x^2", Method.Decompose)
        assertTrue("提公因式" in labels, "缺少提公因式：$labels")
        assertTrue(textOf("x^3+x^2", Method.Decompose).contains("x^{2}"), "公因式没提出来")
    }

    @Test
    fun contentKeepsEngineForm() {
        // Symja 给的是 (2x-4)(x+2)，过程最后一行必须和它一致。
        // 内容因子 2 会先被提出来，公式按括号里的 x²-4 认成平方差。
        assertTrue("平方差公式" in labelsOf("2*x^2-8", Method.Decompose), "2 该先提出去再认公式")
        assertTrue(textOf("2*x^2-8", Method.Decompose).contains("2\\,x-4") ||
            textOf("2*x^2-8", Method.Decompose).contains("2x-4"),
            "分解行和引擎结果不一致：${textOf("2*x^2-8", Method.Decompose)}")
    }

    @Test
    fun irreducibleHasNoProcess() {
        assertTrue(stepsOf("x^2+2*x+3", Method.Decompose) == null, "不可约的不该有过程")
    }

    @Test
    fun sessionExposesProcessJson() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("(x+1)*(x+2)", "\\left(x+1\\right)\\left(x+2\\right)")
        val json = session.processSteps(Method.Expand)
        assertTrue(json != null && json.contains("\"steps\""), "会话层拿不到展开步骤：$json")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            "(x+1)*(x+2)" to Method.Expand,
            "(x+1)^2" to Method.Expand,
            "(x+1)^3" to Method.Expand,
            "(x+y)*(x-y)" to Method.Expand,
            "x^2+3*x+2" to Method.Expand,
            "x^2+5*x+6" to Method.Decompose,
            "x^2-4" to Method.Decompose,
            "x^2+2*x+1" to Method.Decompose,
            "x^3-1" to Method.Decompose,
            "x^3+x^2" to Method.Decompose,
            "2*x^2-8" to Method.Decompose,
            "2*x^2+4*x" to Method.Decompose,
            "x^3+x^2-x-1" to Method.Decompose,
            "x^2+2*x+3" to Method.Decompose,
        )
        val sb = StringBuilder()
        for ((raw, method) in cases) {
            sb.append("==== ").append(method.label).append("  ").append(raw).append('\n')
            val steps = PolynomialSteps.build(engine, raw, null, method)
            if (steps == null) {
                sb.append("  （没有步骤）\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        val out = java.io.File("build/diagnostic/polynomial-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
