package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 极限步骤的回归测试。
 *
 * 形状优先：钉住每类极限至少给出哪条技术路线、最后有「计算结果」；
 * 每条能出步骤的用例本身就意味着中间结论通过了和最终值的对拍。
 */
class LimitStepsTest {

    private val engine = SymjaEngine()

    private fun stepsOf(formula: String) = LimitSteps.build(engine, formula)

    private fun keysOf(formula: String): List<String> = stepsOf(formula)?.map { it.key } ?: emptyList()

    private fun textOf(formula: String): String =
        stepsOf(formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    @Test
    fun continuousFunctionSubstitutesDirectly() {
        val keys = keysOf("Limit(x^2,x->2)")
        assertTrue("substitute" in keys, "缺少代入：$keys")
        assertTrue("result" in keys, "缺少结果：$keys")
        assertTrue(textOf("Limit(x^2,x->2)").contains("4"), "代入结果没露面")
    }

    @Test
    fun sinOverXUsesEquivalentInfinitesimal() {
        val keys = keysOf("Limit(Sin(x)/x,x->0)")
        assertTrue("equivalentInfinitesimal" in keys, "缺少等价无穷小：$keys")
        assertTrue(textOf("Limit(Sin(x)/x,x->0)").contains("sin(x) ~ x"), "等价关系没写出来")
    }

    @Test
    fun oneMinusCosUsesEquivalentInfinitesimal() {
        val keys = keysOf("Limit((1-Cos(x))/x^2,x->0)")
        assertTrue("equivalentInfinitesimal" in keys, "缺少等价无穷小：$keys")
        assertTrue(textOf("Limit((1-Cos(x))/x^2,x->0)").contains("1-cos(x)"), "1-cos 的等价没写出来")
    }

    @Test
    fun expMinusOneUsesEquivalentInfinitesimal() {
        val keys = keysOf("Limit((E^x-1)/x,x->0)")
        assertTrue("equivalentInfinitesimal" in keys, "缺少等价无穷小：$keys")
    }

    @Test
    fun indeterminateResultGoesThroughNumericFallback() {
        // Symja 对 (e^x-1)/x 直接回 indeterminate；结果页现在也要走数值兜底给 1
        assertTrue(
            textOf("Limit((E^x-1)/x,x->0)").trimEnd().endsWith("= 1"),
            "indeterminate 没兜底：${textOf("Limit((E^x-1)/x,x->0)")}",
        )
    }

    @Test
    fun cubicOverXUsesLHopital() {
        val keys = keysOf("Limit((x-Sin(x))/x^3,x->0)")
        assertTrue("lhopital" in keys, "缺少洛必达：$keys")
        assertTrue("result" in keys, "缺少结果：$keys")
    }

    @Test
    fun importantLimitUsesLogRewrite() {
        val keys = keysOf("Limit((1+x)^(1/x),x->0)")
        assertTrue("powerLog" in keys, "缺少取对数：$keys")
        assertTrue(textOf("Limit((1+x)^(1/x),x->0)").endsWith("= e"), "结果不是 e")
    }

    @Test
    fun onePowerInfinityAtInfinityUsesLogRewrite() {
        // 引擎直接给 e；代入是 1^∞，应当补一步取对数
        val keys = keysOf("Limit((1+1/x)^x,x->Infinity)")
        assertTrue("powerLog" in keys, "缺少取对数：$keys")
    }

    @Test
    fun divergentLimitGivesNoProcess() {
        assertTrue(LimitSteps.build(engine, "Limit(1/x,x->0)") == null, "发散极限不该出步骤")
    }

    @Test
    fun nonLimitFormulaGivesNoProcess() {
        assertTrue(LimitSteps.build(engine, "x^2") == null, "普通式子不该出极限步骤")
    }

    @Test
    fun sessionExposesProcessJsonForLimit() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("Limit(Sin(x)/x,x->0)", "\\lim_{x\\to 0}{\\frac{\\sin x}{x}}")
        val json = session.processSteps(Method.Limit)
        assertTrue(json != null && json.contains("\"steps\""), "会话层拿不到步骤：$json")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            "Limit(x^2,x->2)",
            "Limit(Sin(x)/x,x->0)",
            "Limit((1-Cos(x))/x^2,x->0)",
            "Limit((E^x-1)/x,x->0)",
            "Limit(Log(1+x)/x,x->0)",
            "Limit(Tan(x)/x,x->0)",
            "Limit((1+x)^(1/x),x->0)",
            "Limit((1+1/x)^x,x->Infinity)",
            "Limit((x-Sin(x))/x^3,x->0)",
            "Limit((E^x-1-x)/x^2,x->0)",
            "Limit(Log(x)/(x-1),x->1)",
            "Limit(x/E^x,x->Infinity)",
            "Limit(1/x,x->0)",
        )
        val sb = StringBuilder()
        for (raw in cases) {
            sb.append("==== ").append(raw).append('\n')
            val steps = LimitSteps.build(engine, raw)
            if (steps == null) {
                sb.append("  （没有步骤）\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        val out = java.io.File("build/diagnostic/limit-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
