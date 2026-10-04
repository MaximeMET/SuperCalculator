package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 积分规则包的回归测试：加载、整串匹配、数值回验、坏规则拦截。 */
class IntegralRulePackTest {

    private val engine = SymjaEngine()

    private fun textOf(formula: String): String =
        IntegrateSteps.build(engine, formula)?.joinToString("\n") { it.lines.joinToString("\n") } ?: ""

    @Test
    fun packLoadsFromResources() {
        assertTrue(IntegralRulePack.rules.size >= 8, "规则包没加载出来：${IntegralRulePack.rules.size}")
        assertTrue(IntegralRulePack.rules.any { it.id == "sin" }, "缺少 sin 规则")
    }

    @Test
    fun packAddsNewDirectForms() {
        // sec / √(1+x²) 原来没有公式名，靠规则包补上
        assertTrue(textOf("Integrate(Sec(x), x)").contains("sec x"), "sec 没走规则包")
        assertTrue(textOf("Integrate(Sqrt(1+x^2), x)").contains("√(1+x²)"), "√(1+x²) 没走规则包")
    }

    @Test
    fun packRulesKeepOldNotes() {
        assertTrue(textOf("Integrate(Sin(x), x)").contains("∫sin x dx = -cos x"), "sin 文案变了")
        assertTrue(textOf("Integrate(Log(x), x)").contains("∫ln x dx"), "ln 文案变了")
        assertTrue(textOf("Integrate(1/x, x)").contains("∫(1/x)dx"), "1/x 文案不见了")
    }

    @Test
    fun compositeStillGoesThroughSubstitution() {
        // 规则包只做"直接套公式"，Sin(2x) 仍旧走第一类换元
        val keys = IntegrateSteps.build(engine, "Integrate(Sin(2*x), x)")?.map { it.key } ?: emptyList()
        assertTrue("substitution" in keys, "Sin(2x) 被规则包抢走了：$keys")
    }

    @Test
    fun wrongRuleIsRejectedByVerification() {
        // 故意写错的规则：∫sin x dx = x²。数值回验必须拦下
        val bad = IntegralRulePack.Rule(
            id = "badSin",
            match = "Sin(u_)",
            result = "u_^2",
            note = "编的公式",
        )
        val core = engine.parseOrNull("Sin(x)")!!
        val claimed = IntegralRulePack.apply(engine, core, bad, "x")
        assertNotNull(claimed, "匹配本身应该成功")
        // 验证：差得离谱，IntegrateSteps 不会采用（这里直接核对回验结论）
        val derivative = engine.evaluateOrNull(engine.parseOrNull("Diff(($claimed), x)"))!!
        val atOne = engine.signAt("($derivative)-(Sin(x))", "x", 1.0)
        assertTrue(atOne != null && kotlin.math.abs(atOne) > 0.01, "坏规则竟然对上了")
    }

    @Test
    fun patternMatchIsWholeExpression() {
        // Sin(x)*Cos(x) 不能被 Sin(u_) 整串匹配（否则会抢先标注成 sin 的公式）
        val core = engine.parseOrNull("Sin(x)*Cos(x)")!!
        val sin = IntegralRulePack.rules.first { it.id == "sin" }
        assertNull(IntegralRulePack.apply(engine, core, sin, "x"), "子表达式不该被匹配")
    }

    @Test
    fun miniJsonParsesPack() {
        val rules = IntegralRulePack.parse(
            """
            {"version": 1, "rules": [
              {"id": "a", "match": "Sin(u_)", "result": "-Cos(u_)", "note": "n"},
              {"id": "b", "match": "Cos(u_)", "result": "Sin(u_)", "note": "m"}
            ]}
            """.trimIndent()
        )
        assertTrue(rules.size == 2 && rules[0].id == "a" && rules[1].note == "m", "迷你 JSON 解析不对")
    }
}
