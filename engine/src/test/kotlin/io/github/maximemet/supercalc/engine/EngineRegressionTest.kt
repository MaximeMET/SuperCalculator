package io.github.maximemet.supercalc.engine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试。
 *
 * 标了「基准实测」的用例，期望值是从 Android 15 上运行的参考 App 里抓出来的
 * （`adb logcat -s calc:*` 里的 `getAutoResult returned res = ...`），
 * 属于硬约束：改动引擎后这些必须一字不差。
 */
class EngineRegressionTest {

    @AfterTest
    fun tearDown() = EngineSettings.reset()

    private fun session(formula: String): CalculationSession =
        CalculationSession().apply { setFormula(formula, formula) }

    // ---------- 基准实测 ----------

    @Test
    fun `基准实测 1除以3 的自动预览`() {
        assertEquals("= \\frac{1}{3}$$= 0.3333333333", session("1/3").autoResult())
    }

    @Test
    fun `基准实测 1除以7 的自动预览`() {
        assertEquals("= \\frac{1}{7}$$= 0.1428571429", session("1/7").autoResult())
    }

    @Test
    fun `保留小数位设置影响位数`() {
        EngineSettings.precision = 4
        assertEquals("= \\frac{1}{3}$$= 0.3333", session("1/3").autoResult())
    }

    // ---------- 方法推荐 ----------

    @Test
    fun `高次多项式给出五个按钮`() {
        assertEquals(
            listOf(
                Method.Integrate, Method.Derivative, Method.Draw,
                Method.Expand, Method.Decompose,
            ),
            session("x^2").availableMethods(),
        )
    }

    @Test
    fun `分式给出积分求导画图`() {
        assertEquals(
            listOf(Method.Integrate, Method.Derivative, Method.Draw),
            session("1/x").availableMethods(),
        )
    }

    @Test
    fun `等式给出求解方程`() {
        assertTrue(Method.Solve in session("x^2==1").availableMethods())
    }

    @Test
    fun `不等式给出解不等式`() {
        assertEquals(listOf(Method.SolveIneq), session("x^2>4").availableMethods())
    }

    @Test
    fun `含未知数时不显示自动预览`() {
        assertEquals("", session("x^2").autoResult())
        assertEquals("", session("sin(x)").autoResult())
    }

    // ---------- 运算结果 ----------

    @Test
    fun `求导结果`() {
        assertEquals("2 \\cdot x", session("x^2").evaluate(Method.Derivative))
        assertEquals(" - \\frac{1}{{x}^{2}}", session("1/x").evaluate(Method.Derivative))
        assertEquals("\\cos (x)", session("sin(x)").evaluate(Method.Derivative))
    }

    @Test
    fun `积分结果带常数项`() {
        val r = session("x^2").evaluate(Method.Integrate)
        assertTrue(r != null && r.endsWith(" + C"), "积分结果应带 + C，实际是 $r")
    }

    @Test
    fun `因式分解结果`() {
        // 现代 Symja 的 TeX 输出会用 \left( \right) 包裹括号；
        // 渲染效果与旧版一致，但字符串不同——对比时以渲染结果为准确认。
        assertEquals(
            "\\left( -1 + x\\right)  \\cdot \\left( 1 + x\\right) ",
            session("x^2-1").evaluate(Method.Decompose),
        )
    }
}
