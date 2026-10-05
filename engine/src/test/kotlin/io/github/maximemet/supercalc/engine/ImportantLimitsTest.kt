package io.github.maximemet.supercalc.engine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 重要极限的结果回归。
 *
 * 触发这批测试的背景：Symja 2016 的 `Limit` 规则对 `x/Sin(x)` 在 0 处直接给出 **0**
 * （正确值 1）——不是"没求值"，是一个看起来正常的错数，结果页和绘图页的 y 轴交点
 * 都被它带偏。修法是 [LimitFallback.resultLatex] / [LimitFallback.valueAt] 里
 * 精确值和数值逼近对拍，对不上以数值为准。
 *
 * 表里每条的价值：既锁住"重要极限给对值"，也锁住"数值对拍不会把正确答案改坏"。
 */
class ImportantLimitsTest {

    private val engine = SymjaEngine()

    @AfterTest
    fun tearDown() = EngineSettings.reset()

    private fun resultOf(body: String): Double? {
        val formula = "Limit(($body), x->0)"
        val exact = engine.evaluateOrNull(engine.parseOrNull(formula))
        return LimitFallback.resultNumber(engine, formula, exact)
    }

    private fun assertLimit(expected: Double, body: String) {
        val value = resultOf(body) ?: Double.NaN
        assertEquals(
            expected,
            value,
            1e-6,
            "lim x→0 $body 应为 $expected，实际 $value",
        )
    }

    @Test
    fun `第一重要极限及其倒数`() {
        assertLimit(1.0, "Sin(x)/x")
        assertLimit(1.0, "x/Sin(x)") // Symja 会算成 0，对拍后纠正为 1
    }

    /**
     * 更根本的一条：x/sinx 现在**引擎本身就給 1**（内核对 Limit 打了 Sec 特判补丁，
     * 见 tools/build-symja.ps1 的「补丁 7」），不再依赖数值对拍兜底。
     */
    @Test
    fun `引擎自身给出正确结果`() {
        assertEquals(
            "1",
            engine.evaluateOrNull(engine.parseOrNull("Limit(x/Sin(x),x->0)"))?.toString(),
        )
        assertEquals(
            "1",
            engine.evaluateOrNull(engine.parseOrNull("Limit(x/Tan(x),x->0)"))?.toString(),
        )
    }

    @Test
    fun `三角函数的等价无穷小一族`() {
        assertLimit(1.0, "Tan(x)/x")
        assertLimit(1.0, "x/Tan(x)")
        assertLimit(1.0, "ArcSin(x)/x")
        assertLimit(1.0, "ArcTan(x)/x")
        assertLimit(1.0, "Sinh(x)/x")
        assertLimit(1.0, "Tanh(x)/x")
        assertLimit(0.5, "(1-Cos(x))/x^2")
        assertLimit(2.0, "(x^2)/(1-Cos(x))")
        assertLimit(0.5, "Tan(x)/Sin(2x)")
        assertLimit(0.6, "Sin(3x)/Sin(5x)")
        assertLimit(0.0, "(1-Cos(x))/Sin(x)")
    }

    @Test
    fun `对数与指数相关`() {
        assertLimit(1.0, "(E^x-1)/x")
        assertLimit(1.0, "Log(1+x)/x")
        assertLimit(1.0, "(E^x-1)/Sin(x)")
        assertLimit(2.0, "(E^(2x)-1)/x")
        assertLimit(1.0, "Log(1+x)/Sin(x)")
    }

    @Test
    fun `第二个重要极限走取对数改写`() {
        val formula = "Limit((1+x)^(1/x),x->0)"
        val raw = engine.evaluateAsLatex(formula)
        assertEquals("e", LimitFallback.resultLatex(engine, formula, raw))
    }

    /** 发散的极限不能拿数值当答案：1/x² 在 0 附近数值巨大，但它不是极限值。 */
    @Test
    fun `发散的重要极限保持无穷不改成大数`() {
        val formula = "Limit(1/x^2,x->0)"
        val exact = engine.evaluateOrNull(engine.parseOrNull(formula))
        assertNull(LimitFallback.resultNumber(engine, formula, exact))
        assertNull(LimitFallback.valueAt(engine, "1/x^2", "x", 0.0))
    }

    /** 绘图页 y 轴交点走的就是 valueAt：x/sin x 在 0 处必须给 1。 */
    @Test
    fun `绘图取值同样被纠正`() {
        assertEquals(
            1.0,
            LimitFallback.valueAt(engine, "x/Sin(x)", "x", 0.0) ?: Double.NaN,
            1e-6,
        )
        assertNull(LimitFallback.valueAt(engine, "Abs(x)/x", "x", 0.0))
    }
}
