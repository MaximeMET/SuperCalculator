package io.github.maximemet.supercalc.engine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 极限兜底通道。
 *
 * 期望值来自 work/logs/limitprobe.txt 的实测：Symja 2016 对 `lim x→0 (1+x)^(1/x)`
 * 原样返回不求值，只能靠取对数改写 + 数值验证补上。
 */
class LimitFallbackTest {

    private val engine = SymjaEngine()

    @AfterTest
    fun tearDown() = EngineSettings.reset()

    @Test
    fun `解析出极限的主体变量和极限点`() {
        val parsed = LimitFallback.parse(engine, "Limit((1+x)^(1/x),x->0)")
        assertNotNull(parsed)
        assertEquals("x", parsed.variable)
        assertEquals(0.0, parsed.point, 0.0)
        assertNull(parsed.direction, "没写 Direction 就是两侧")
    }

    @Test
    fun `无穷远的极限点不做数值兜底`() {
        assertNull(LimitFallback.parse(engine, "Limit((1+1/x)^x,x->Infinity)"))
    }

    @Test
    fun `重要极限取对数改写成精确的 e`() {
        assertEquals("e", LimitFallback.evaluateUnevaluated(engine, "Limit((1+x)^(1/x),x->0)"))
    }

    @Test
    fun `两侧对不上就不给结果`() {
        // 1/x 在 0 两侧发散、符号相反；Abs(x)/x 两侧一个是 1 一个是 -1
        assertNull(LimitFallback.valueAt(engine, "1/x", "x", 0.0))
        assertNull(LimitFallback.valueAt(engine, "Abs(x)/x", "x", 0.0))
    }

    @Test
    fun `连续函数照常取值`() {
        val value = LimitFallback.valueAt(engine, "x^2", "x", 2.0)
        assertEquals(4.0, value ?: Double.NaN, 1e-9)
    }

    @Test
    fun `数值逼近的精度够用`() {
        // 兜底用 h=1e-5 的对称平均，误差应当在 1e-9 量级
        val value = LimitFallback.valueAt(engine, "(1+x)^(1/x)", "x", 0.0)
        assertEquals(Math.E, value ?: Double.NaN, 1e-8)
    }
}
