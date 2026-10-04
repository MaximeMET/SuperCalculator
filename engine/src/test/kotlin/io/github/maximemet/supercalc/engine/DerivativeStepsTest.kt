package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 求导步骤的回归测试。
 *
 * 形状优先：钉住每类式子至少能拆出哪种规则、最后有「计算结果」；
 * 文案怎么变不敏感。每条能出步骤的用例本身就意味着通过了和引擎的数值对拍，
 * 所以这里只要断言步骤存在，等价性已经被 build() 验过一遍。
 */
class DerivativeStepsTest {

    private val engine = SymjaEngine()

    private fun keysOf(formula: String): List<String> =
        DerivativeSteps.build(engine, formula)?.map { it.key } ?: emptyList()

    private fun assertHasResult(keys: List<String>, formula: String) {
        assertTrue("result" in keys, "$formula 缺少计算结果：$keys")
        assertTrue(keys.firstOrNull() == "original", "$formula 第一条应该是原式：$keys")
    }

    @Test
    fun polynomialUsesSumRule() {
        val keys = keysOf("x^2-5*x+6")
        assertTrue("sumRule" in keys, "缺少和差法则：$keys")
        assertTrue("powerRule" in keys, "缺少幂函数法则：$keys")
        assertHasResult(keys, "x^2-5*x+6")
    }

    @Test
    fun productUsesProductRule() {
        val keys = keysOf("x^2*sin(x)")
        assertTrue("productRule" in keys, "缺少乘积法则：$keys")
        assertTrue("basicDerivative" in keys, "缺少基本求导公式：$keys")
        assertHasResult(keys, "x^2*sin(x)")
    }

    @Test
    fun compositeUsesChainRule() {
        val keys = keysOf("sin(x^2)")
        assertTrue("chainRule" in keys, "缺少链式法则：$keys")
        assertTrue("powerRule" in keys, "缺少幂函数法则：$keys")
        assertHasResult(keys, "sin(x^2)")
    }

    @Test
    fun quotientUsesQuotientRule() {
        val keys = keysOf("exp(x)/x")
        assertTrue("quotientRule" in keys, "缺少商法则：$keys")
        assertHasResult(keys, "exp(x)/x")
    }

    @Test
    fun rationalFunctionUsesQuotientRule() {
        val keys = keysOf("x/(x+1)")
        assertTrue("quotientRule" in keys, "缺少商法则：$keys")
        assertHasResult(keys, "x/(x+1)")
    }

    @Test
    fun squareRootUsesSqrtRule() {
        val keys = keysOf("sqrt(x)")
        assertTrue("sqrtRule" in keys, "缺少根式法则：$keys")
        assertHasResult(keys, "sqrt(x)")
    }

    @Test
    fun reciprocalUsesReciprocalRule() {
        val keys = keysOf("1/x")
        assertTrue("reciprocalRule" in keys, "缺少倒数法则：$keys")
        assertHasResult(keys, "1/x")
    }

    @Test
    fun exponentialUsesExponentialRule() {
        val keys = keysOf("2^x")
        assertTrue("exponentialRule" in keys, "缺少指数函数法则：$keys")
        assertHasResult(keys, "2^x")
    }

    @Test
    fun sessionExposesProcessJsonForDerivative() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("x^2*sin(x)", "x^{2}\\sin x")
        val json = session.processSteps(Method.Derivative)
        assertTrue(json != null && json.contains("\"steps\""), "会话层拿不到步骤：$json")
    }

    @Test
    fun unsupportedFunctionGivesNoProcess() {
        // Abs 的导数引擎只回一个没求值的 Derivative(...)，不该硬编步骤
        assertTrue(DerivativeSteps.build(engine, "Abs(x)") == null, "Abs 不该有步骤")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            "x^2",
            "2*x+3",
            "a*x^2+b*x+c",
            "x^2-5*x+6",
            "x^2*sin(x)",
            "sin(x^2)",
            "cos(x)*sin(x)",
            "exp(x)/x",
            "x/(x+1)",
            "(x^2-1)/(x-1)",
            "sqrt(x)",
            "sqrt(x^2+1)",
            "1/x",
            "2/x",
            "ln(x)",
            "ln(sin(x))",
            "tan(2*x)",
            "2^x",
            "x^x",
            "(x-1)*(x+1)",
            "x^3-6*x^2+11*x-6",
        )
        val sb = StringBuilder()
        for (raw in cases) {
            sb.append("==== ").append(raw).append('\n')
            val steps = DerivativeSteps.build(engine, raw)
            if (steps == null) {
                sb.append("  （没有步骤）\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
        }
        val out = java.io.File("build/diagnostic/derivative-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
