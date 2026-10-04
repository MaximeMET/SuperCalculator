package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/** 纯算式步骤（通分 / 合并 / 约分）的回归测试。 */
class ArithmeticStepsTest {

    private val engine = SymjaEngine()

    private fun keysOf(formula: String): List<String> =
        ArithmeticSteps.build(engine, formula)?.map { it.key } ?: emptyList()

    @Test
    fun fractionSumGetsCommonDenominator() {
        val keys = keysOf("1/2+1/3")
        assertTrue("original" in keys, "缺少原式：$keys")
        assertTrue("commonDenominator" in keys, "缺少通分：$keys")
        assertTrue("combine" in keys, "缺少合并：$keys")
    }

    @Test
    fun reducibleFractionIsReduced() {
        val keys = keysOf("6/8")
        assertTrue("reduce" in keys, "缺少约分：$keys")
    }

    @Test
    fun fractionProductIsReduced() {
        val keys = keysOf("2/3*9/4")
        assertTrue("reduce" in keys, "缺少约分：$keys")
    }

    @Test
    fun decimalSumTurnsIntoFractions() {
        val keys = keysOf("0.25+0.5")
        assertTrue("commonDenominator" in keys, "缺少通分：$keys")
    }

    @Test
    fun expressionWithUnknownHasNoArithmeticSteps() {
        assertTrue(ArithmeticSteps.build(engine, "x^2+1") == null, "含未知数不该出算式步骤")
    }

    @Test
    fun plainIntegerSumStillHasResultStep() {
        val steps = ArithmeticSteps.build(engine, "2+3")
        assertTrue(steps != null && steps.size >= 2, "整数算式也该给原式+结果：$steps")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            "1/2+1/3",
            "5/6-1/4",
            "1/2+1/3+1/6",
            "2/3*9/4",
            "6/8",
            "0.25+0.5",
            "2+3*4",
            "sin(Pi/6)",
        )
        val sb = StringBuilder()
        val probe = engine.parseOrNull("1/2+1/3+1/6")
        sb.append("probe 1/2+1/3+1/6: ").append(probe)
            .append(" class=").append(probe?.javaClass?.simpleName)
            .append(" isAST=").append(probe?.isAST)
            .append(" isPlus=").append((probe as? org.matheclipse.core.interfaces.IAST)?.isPlus)
            .append(" size=").append((probe as? org.matheclipse.core.interfaces.IAST)?.size)
            .append('\n')
        for (raw in cases) {
            sb.append("==== ").append(raw).append('\n')
            val steps = ArithmeticSteps.build(engine, raw)
            if (steps == null) {
                sb.append("  <没有步骤>\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        val out = java.io.File("build/diagnostic/arithmetic-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
