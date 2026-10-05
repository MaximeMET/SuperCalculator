package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.matheclipse.core.expression.F

/**
 * 回归：a-z 字母作为**参数**参与求导 / 积分 / 极限。
 *
 * 用户报告的 bug 是"带字母的式子无法积分/求导/极限"。根因有两处：
 *  - 步骤回验只把自变量代采样点，参数还是自由符号，求不出数导致整条步骤被误杀；
 *  - 极限的数值对拍通道代不进参数（`a` 是自由的），等价无穷小/洛必达分支直接放弃。
 *
 * 这组用例把这三种运算的关键形状钉住，防止回退。
 */
class LetterParamRegressionTest {

    private val engine = SymjaEngine()

    @Test
    fun derivativeWithLetterParameter() {
        val cases = mapOf(
            "a*x^2" to "2*a*x",
            "k*x+b" to "k",
            "sin(a*x)" to "a*Cos(a*x)",
        )
        for ((formula, expected) in cases) {
            val steps = DerivativeSteps.build(engine, formula)
            assertNotNull(steps, "导数步骤为空：$formula")
            assertTrue(
                steps.any { it.key == "result" && it.lines.any { line -> line.contains("=") } },
                "导数步骤缺少结果行：$formula",
            )
            val value = engine.evaluateOrNull(engine.parseOrNull("Diff($formula, x)"))
            assertNotNull(value, "引擎求导失败：$formula")
            val expectedExpr = engine.parseOrNull(expected)
            assertNotNull(expectedExpr)
            val diff = F.eval(F.Subtract(value, expectedExpr))
            assertTrue(
                diff.isZero,
                "求导结果不符合预期：$formula -> $value（期望 $expected）",
            )
        }
    }

    @Test
    fun integrateWithLetterParameter() {
        val formulas = listOf(
            "Integrate(a*x^2, x)",
            "Integrate(1/(x^2+a), x)",
            "Integrate(Sin(a*x), x)",
            "Integrate(a^x, x)",
        )
        for (formula in formulas) {
            val steps = IntegrateSteps.build(engine, formula)
            assertNotNull(steps, "积分步骤为空：$formula")
            assertTrue(
                steps.any { it.key == "result" },
                "积分步骤缺少结果行：$formula",
            )
        }
    }

    @Test
    fun limitWithLetterParameter() {
        // 直接代入型
        val substitute = LimitSteps.build(engine, "Limit(a*x, x->0)")
        assertNotNull(substitute, "极限步骤为空：Limit(a*x, x->0)")
        assertTrue(substitute.any { it.key == "substitute" })

        // 0/0 型，结果含参数：lim sin(ax)/x = a，应给出洛必达
        val lhopital = LimitSteps.build(engine, "Limit(Sin(a*x)/x, x->0)")
        assertNotNull(lhopital, "极限步骤为空：Limit(Sin(a*x)/x, x->0)")
        assertTrue(
            lhopital.any { it.key == "lhopital" },
            "缺少洛必达步骤：$lhopital",
        )

        // 1^∞ 型，结果含参数：lim (1+ax)^(1/x) = e^a
        val power = LimitSteps.build(engine, "Limit((1+a*x)^(1/x), x->0)")
        assertNotNull(power, "极限步骤为空：Limit((1+a*x)^(1/x), x->0)")
        assertTrue(power.any { it.key == "result" })
    }

    @Test
    fun limitSymbolicResultIsRendered() {
        // 结果页通道也应给出 e^a，而不是原样回显未求值的极限
        val latex = LimitFallback.resultLatex(
            engine,
            "Limit((1+a*x)^(1/x), x->0)",
            engine.evaluateAsLatex("Limit((1+a*x)^(1/x), x->0)"),
        )
        assertNotNull(latex, "结果页极限为空")
        assertTrue(!latex.contains("\\lim_"), "结果页仍是未求值的极限：$latex")
    }

    /**
     * 按钮分发也要一起守住：带字母参数的式子要能看到「积分/求导」按钮，
     * 按钮真的按下以后要拿到含参数的结果（不只是步骤，结果页的通道也要通）。
     */
    @Test
    fun letterParameterOffersMethodsAndEvaluates() {
        val session = CalculationSession(engine)

        session.setFormula("a*x^2", "a x^{2}")
        val methods = session.availableMethods()
        assertTrue(
            Method.Integrate in methods && Method.Derivative in methods,
            "带参数式子没有积分/求导按钮：$methods",
        )
        val derivative = session.evaluate(Method.Derivative)
        assertNotNull(derivative, "结果页求导为空")
        assertTrue(
            derivative.contains("a") && derivative.contains("x"),
            "求导结果不对：$derivative",
        )

        session.setFormula("theta*x", "theta x")
        val greekMethods = session.availableMethods()
        assertTrue(
            Method.Integrate in greekMethods && Method.Derivative in greekMethods,
            "希腊字母式子没有积分/求导按钮：$greekMethods",
        )
        val greekDerivative = session.evaluate(Method.Derivative)
        assertNotNull(greekDerivative, "希腊字母求导为空")
        assertTrue(greekDerivative.contains("theta"), "希腊字母求导结果不对：$greekDerivative")
    }

}
