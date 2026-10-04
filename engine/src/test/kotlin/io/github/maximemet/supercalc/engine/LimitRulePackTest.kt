package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/** 等价无穷小规则包的回归测试。 */
class LimitRulePackTest {

    private val engine = SymjaEngine()

    private fun textOf(formula: String): String =
        LimitSteps.build(engine, formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    private fun keysOf(formula: String): List<String> =
        LimitSteps.build(engine, formula)?.map { it.key } ?: emptyList()

    @Test
    fun packLoadsFromResources() {
        assertTrue(LimitRulePack.rules.size >= 6, "规则包没加载：${LimitRulePack.rules.size}")
    }

    @Test
    fun classicSinOverXStillWorks() {
        val text = textOf("Limit(Sin(x)/x, x->0)")
        assertTrue("equivalentInfinitesimal" in keysOf("Limit(Sin(x)/x, x->0)"), "没走等价无穷小：$text")
        assertTrue(text.contains("sin(x) ~ x"), "文案不对：$text")
    }

    @Test
    fun packAddsNewEquivalentRules() {
        // sh(x)/x 之前没有等价无穷小标注，靠规则包补上
        val formula = "Limit(Sinh(x)/x, x->0)"
        val text = textOf(formula)
        assertTrue("equivalentInfinitesimal" in keysOf(formula), "sh(x)/x 没走规则包：$text")
        assertTrue(text.contains("sh(x) ~ x"), "文案不对：$text")
    }

    @Test
    fun compositeShapesStillHandledInCode() {
        assertTrue(textOf("Limit((1-Cos(x))/x^2, x->0)").contains("1-cos"), "1-cos 那条不见了")
        val expFormula = "Limit((E^x-1)/x, x->0)"
        assertTrue("equivalentInfinitesimal" in keysOf(expFormula), "e^x-1 那条不见了")
        assertTrue(textOf(expFormula).contains("e^(x)-1 ~ x"), "e^x-1 文案不对")
    }

    @Test
    fun notTendingToZeroIsNotReplaced() {
        // sin(x) 在 x→π/2 时趋于 1，不能替换成 x
        val text = textOf("Limit(Sin(x), x->pi/2)")
        assertTrue(!text.contains("等价无穷小"), "不该替换：$text")
    }
}
