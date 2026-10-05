package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三张知识表的扩容回归：高等数学常见的极限替换、求导公式、积分表。
 *
 * 每条规则都要求「文案真的出现在步骤里」——规则写错会被数值回验拦下，
 * 这里断言的是"拦截之后还有货"。
 */
class RuleExpansionTest {

    private val engine = SymjaEngine()

    private fun integralText(formula: String): String =
        IntegrateSteps.build(engine, formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    private fun derivativeText(formula: String): String =
        DerivativeSteps.build(engine, formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    private fun limitText(formula: String): String =
        LimitSteps.build(engine, formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    // ---------- 积分表 ----------

    @Test
    fun integralTableCoversCommonTrigAndHyperbolic() {
        assertTrue(integralText("Integrate(Cot(x), x)").contains("cot"), "cot 没进表")
        assertTrue(integralText("Integrate(Sec(x)^2, x)").contains("sec"), "sec² 没进表")
        assertTrue(integralText("Integrate(Csc(x)^2, x)").contains("csc"), "csc² 没进表")
        assertTrue(integralText("Integrate(Sec(x)*Tan(x), x)").contains("sec"), "sec·tan 没进表")
        assertTrue(integralText("Integrate(Tanh(x), x)").contains("th"), "th 没进表")
        assertTrue(integralText("Integrate(Coth(x), x)").contains("cth"), "cth 没进表")
    }

    @Test
    fun integralTableCoversInverseTrigAndHyperbolic() {
        assertTrue(integralText("Integrate(ArcSin(x), x)").contains("arcsin"), "arcsin 没进表")
        assertTrue(integralText("Integrate(ArcCos(x), x)").contains("arccos"), "arccos 没进表")
        assertTrue(integralText("Integrate(ArcCot(x), x)").contains("arccot"), "arccot 没进表")
        assertTrue(integralText("Integrate(ArcSinh(x), x)").contains("arsh"), "arsh 没进表")
        assertTrue(integralText("Integrate(ArcTanh(x), x)").contains("arth"), "arth 没进表")
    }

    @Test
    fun integralTableCoversParametricForms() {
        // 1/(x²+4)：常数项绑到 a_，公式里的 √a 正好等于 2
        assertTrue(integralText("Integrate(1/(x^2+4), x)").contains("arctan"), "x²+4 没认出 arctan")
        assertTrue(integralText("Integrate(1/(4+x^2), x)").contains("arctan"), "加数写前面就不认了")
        assertTrue(integralText("Integrate(1/(x^2-4), x)").contains("x²-c"), "x²-4 没进表")
        assertTrue(integralText("Integrate(1/Sqrt(4-x^2), x)").contains("arcsin"), "√(4-x²) 没认出 arcsin")
        assertTrue(integralText("Integrate(Sqrt(4-x^2), x)").contains("c-x²"), "√(c-x²) 没进表")
        assertTrue(integralText("Integrate(Sqrt(x^2+4), x)").contains("x²+c"), "√(x²+c) 没进表")
        assertTrue(integralText("Integrate(1/Sqrt(x^2+4), x)").contains("√(x²+c)"), "1/√(x²+c) 没进表")
        assertTrue(integralText("Integrate(1/(4-x^2), x)").contains("c-x²"), "1/(c-x²) 没进表")
    }

    @Test
    fun integralTableCoversExponentialBase() {
        val text = integralText("Integrate(2^x, x)")
        assertTrue(text.contains("aˣ"), "2^x 没认出指数公式：$text")
        assertTrue(integralText("Integrate(E^x, x)").contains("eˣ"), "e^x 的文案被换掉了")
    }

    @Test
    fun integralTableDoesNotStealSubstitutionCases() {
        val keys = IntegrateSteps.build(engine, "Integrate(Cos(3*x+1), x)")?.map { it.key } ?: emptyList()
        assertTrue("substitution" in keys, "f(kx+b) 该留给换元：$keys")
    }

    // ---------- 求导表 ----------

    @Test
    fun derivativeTableCoversLogBase() {
        val text = derivativeText("log(2,x)")
        assertTrue(text.contains("\\frac{1}{x\\,\\ln 2}"), "log₂x 没走换底公式：$text")
    }

    @Test
    fun derivativeTableCoversInverseHyperbolic() {
        for (f in listOf("arcsinh(x)", "arccosh(x)", "arctanh(x)")) {
            val text = derivativeText(f)
            assertTrue(text.isNotBlank(), "$f 没有步骤")
            assertTrue(text.contains("1"), "$f 步骤不对：$text")
        }
    }

    @Test
    fun derivativeTableStillHandlesChainRuleWithBase() {
        val keys = DerivativeSteps.build(engine, "log(2,x^2)")?.map { it.key } ?: emptyList()
        assertTrue("chainRule" in keys, "log(2,x²) 该是链式法则：$keys")
    }

    @Test
    fun derivativeTableKeepsClassicFormulas() {
        assertTrue(derivativeText("sin(x)").contains("\\cos"), "sin 的公式变了")
        assertTrue(derivativeText("tan(x)").contains("\\sec^{2}"), "tan 的公式变了")
        assertTrue(derivativeText("arcsin(x)").contains("\\sqrt{1-"), "arcsin 的公式变了")
        assertTrue(derivativeText("sinh(x)").contains("\\cosh"), "sh 的公式变了")
    }

    // ---------- 等价无穷小表 ----------

    @Test
    fun limitTableCoversPowerAndExponentialForms() {
        val cubeRoot = limitText("Limit(((1+x)^(1/3)-1)/x, x->0)")
        assertTrue(cubeRoot.contains("1/3"), "三次根式那条没写出来：$cubeRoot")
        val expBase = limitText("Limit((2^x-1)/x, x->0)")
        assertTrue(expBase.contains("ln 2"), "2^x-1 那条没写出来：$expBase")
        val root = limitText("Limit((Sqrt(1+x)-1)/x, x->0)")
        assertTrue(root.contains("1/2"), "√(1+x)-1 那条没写出来：$root")
    }

    @Test
    fun limitTableCoversThirdOrderDifferences() {
        assertTrue(limitText("Limit((x-Sin(x))/x^3, x->0)").contains("x)³/6"), "x-sin x 那条没写出来")
        assertTrue(limitText("Limit((Tan(x)-x)/x^3, x->0)").contains("x)³/3"), "tan x-x 那条没写出来")
        assertTrue(limitText("Limit((Tan(x)-Sin(x))/x^3, x->0)").contains("x)³/2"), "tan x-sin x 那条没写出来")
    }

    @Test
    fun limitTableCoversLogAndCosForms() {
        assertTrue(limitText("Limit(Log(1+x)/x, x->0)").contains("ln(1+x)"), "ln(1+x) 那条没写出来")
        assertTrue(limitText("Limit((1-Cos(x))/x^2, x->0)").contains("1-cos"), "1-cos 那条没写出来")
    }

    @Test
    fun limitRuleNeedsConstantBase() {
        // (1+x)^x-1 的指数含 x，不是 "a^u-1" 该认的形状
        val rule = LimitRulePack.rules.first { it.id == "pow1p" }
        val expr = engine.parseOrNull("(1+x)^x-1")!!
        assertNull(
            LimitRulePack.apply(engine, expr, rule, "x"),
            "指数含 x 也被当成常数了",
        )
    }

    @Test
    fun commutativeMatchingIgnoresTermOrder() {
        // 1/(x²+4) 与 1/(4+x²) 的 Plus 参数顺序不同，规则不该挑食
        val rule = IntegralRulePack.rules.first { it.id == "recipx2pa2" }
        assertTrue(IntegralRulePack.apply(engine, engine.parseOrNull("1/(x^2+4)")!!, rule, "x") != null)
        assertTrue(IntegralRulePack.apply(engine, engine.parseOrNull("1/(4+x^2)")!!, rule, "x") != null)
    }
}
