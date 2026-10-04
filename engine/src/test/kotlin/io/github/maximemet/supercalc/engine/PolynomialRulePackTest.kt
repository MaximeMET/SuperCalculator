package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 多项式规则包的回归测试：加载、形状命中、内容因子、坏模板拦截。 */
class PolynomialRulePackTest {

    private val engine = SymjaEngine()

    private fun labelsOf(formula: String): List<String> =
        PolynomialSteps.build(engine, formula, null, Method.Decompose)?.map { it.label } ?: emptyList()

    private fun textOf(formula: String): String =
        PolynomialSteps.build(engine, formula, null, Method.Decompose)
            ?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    private fun ruleOf(formula: String): PolynomialRulePack.Rule? {
        val expr = engine.parseOrNull(formula) ?: return null
        val factored = engine.decompose(formula) ?: return null
        return PolynomialRulePack.technique(engine, expr, factored, engine.unknownSymbol())
    }

    @Test
    fun packLoadsFromResources() {
        assertTrue(PolynomialRulePack.rules.size >= 5, "规则包没加载出来：${PolynomialRulePack.rules.size}")
        assertTrue(PolynomialRulePack.rules.any { it.id == "cube" }, "缺少立方和差规则")
        assertTrue(PolynomialRulePack.rules.last().id == "generic", "兜底规则必须放最后")
    }

    @Test
    fun squareDiffCoversCoefficients() {
        // (2x)²-3²：u 是 2x 不是 x，模板匹配得出系数
        assertTrue("平方差公式" in labelsOf("4*x^2-9"), "4x²-9 该走平方差：${labelsOf("4*x^2-9")}")
    }

    @Test
    fun cubeCoversAllFourShapes() {
        assertTrue("立方和差公式" in labelsOf("x^3+8"), "x³+8 该走立方和差")
        assertTrue("立方和差公式" in labelsOf("x^3-1"), "x³-1 该走立方和差")
        assertTrue("立方和差公式" in labelsOf("8*x^3+27"), "8x³+27（u=2x）该走立方和差")
        assertTrue("立方和差公式" in labelsOf("27-8*x^3"), "27-8x³ 该走立方和差")
    }

    @Test
    fun contentFactorGoesThroughPrimitive() {
        // 2x³-16 = 2(x³-8)：内容因子先提出来，公式按 x³-8 认
        assertTrue("立方和差公式" in labelsOf("2*x^3-16"), "内容因子没提出来：${labelsOf("2*x^3-16")}")
    }

    @Test
    fun irreducibleInnerDoesNotClaimCross() {
        // x³+x = x(x²+1)：括号里那份不可约，不能被标成十字相乘
        val labels = labelsOf("x^3+x")
        assertTrue("十字相乘" !in labels, "不可约的内层被错标：$labels")
        assertTrue("因式分解" in labels, "应回落到通用因式分解：$labels")
    }

    @Test
    fun wrongTemplateIsRejectedByVerification() {
        // 假模板：x²+5x+6 不是完全平方，命中不了 (u_+v_)^2
        val bad = PolynomialRulePack.Rule(
            id = "bad",
            label = "假公式",
            formula = null,
            degree = 2,
            match = "(u_+v_)^2",
            factorable = false,
        )
        val expr = engine.parseOrNull("x^2+5*x+6")!!
        val factored = engine.decompose("x^2+5*x+6")!!
        assertNull(
            PolynomialRulePack.technique(engine, expr, factored, engine.unknownSymbol(), listOf(bad)),
            "坏模板没被回验拦下",
        )
    }

    @Test
    fun miniJsonParsesPack() {
        val rules = PolynomialRulePack.parse(
            """
            {"version": 1, "rules": [
              {"id": "a", "label": "甲", "formula": "f", "degree": 2, "match": "(u_-v_)*(u_+v_)"},
              {"id": "b", "label": "乙", "factorable": true}
            ]}
            """.trimIndent()
        )
        assertTrue(rules.size == 2 && rules[0].degree == 2 && rules[1].label == "乙", "迷你 JSON 解析不对")
        assertTrue(rules[1].factorable && rules[1].match == null, "factorable/缺省字段解析不对")
    }
}
