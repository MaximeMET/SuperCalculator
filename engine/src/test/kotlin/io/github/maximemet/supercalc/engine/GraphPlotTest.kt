package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 绘图用的一元函数与二次曲线分析。
 *
 * 这里的期望值全部来自参考 App 的实测日志，例如 `y=x^2` 时它打出的是
 * `{Type=PARABOLA, NeatLine={y==-0.25}, MinPoint={0.0,0.0}, FocusPoint={0.0,0.25}}`。
 */
class GraphPlotTest {

    private val engine = SymjaEngine()

    @Test
    fun `一元函数取值`() {
        val functions = GraphPlot.functions(engine, "y==x^(2)")
        assertEquals(1, functions.size)
        assertEquals(4.0, functions[0].valueAt(2.0) ?: Double.NaN, 1e-9)
        assertEquals(0.25, functions[0].valueAt(-0.5) ?: Double.NaN, 1e-9)
    }

    @Test
    fun `连续取值不应该出现空洞`() {
        val function = GraphPlot.functions(engine, "y==x^(2)").single()
        var missing = 0
        var x = -4.0
        while (x <= 4.0) {
            if (function.valueAt(x) == null) missing++
            x += 0.25
        }
        assertEquals(0, missing, "x^2 在全实数域上都有值")
    }

    @Test
    fun `y轴交点取极限值不是直接代入`() {
        // `(1+x)^(1/x)` 在 x=0 直接代入得到 1，而曲线真正的走向是 e。
        // 用户报的「与y轴交点求错」（截图上是 (0,1)）就是直接代入造成的。
        val function = GraphPlot.functions(engine, "y==(1+x)^(1/x)").single()
        assertEquals(Math.E, function.curveValueAt(0.0) ?: Double.NaN, 1e-8)
        // 曲线以外的位置仍然是普通取值
        assertEquals(2.0, function.curveValueAt(1.0) ?: Double.NaN, 1e-9)
    }

    @Test
    fun `抛物线给出焦点准线和极值点`() {
        val extra = GraphPlot.extraInfo(engine, "y==x^(2)")
        assertEquals(ConicType.PARABOLA, extra.type)
        val focus = extra.points.first { it.kind == SpecialPointKind.FOCUS }
        assertEquals(0.0, focus.x, 1e-9)
        assertEquals(0.25, focus.y, 1e-9)
        val min = extra.points.first { it.kind == SpecialPointKind.MIN }
        assertEquals(0.0, min.x, 1e-9)
        assertEquals(0.0, min.y, 1e-9)
        val line = extra.lines.single()
        assertTrue(line is ExtraLine.Horizontal)
        assertEquals(-0.25, (line as ExtraLine.Horizontal).y, 1e-9)
    }

    @Test
    fun `圆给出圆心`() {
        val extra = GraphPlot.extraInfo(engine, "x^2+y^2==4")
        assertEquals(ConicType.CIRCLE, extra.type)
        val center = extra.points.single()
        assertEquals(SpecialPointKind.CENTER, center.kind)
        assertEquals(0.0, center.x, 1e-9)
        assertEquals(0.0, center.y, 1e-9)
        assertTrue(extra.lines.isEmpty())
    }

    @Test
    fun `椭圆给出中心与两个焦点`() {
        val extra = GraphPlot.extraInfo(engine, "x^2/9+y^2/4==1")
        assertEquals(ConicType.ELLIPSE, extra.type)
        assertEquals(1, extra.points.count { it.kind == SpecialPointKind.CENTER })
        val focuses = extra.points.filter { it.kind == SpecialPointKind.FOCUS }
        assertEquals(2, focuses.size)
        // a=3 b=2 -> c=sqrt(5)
        assertEquals(Math.sqrt(5.0), focuses.maxOf { it.x }, 1e-9)
        assertEquals(Math.sqrt(5.0), -focuses.minOf { it.x }, 1e-9)
    }

    @Test
    fun `双曲线给出渐近线`() {
        val extra = GraphPlot.extraInfo(engine, "x^2-y^2==1")
        assertEquals(ConicType.HYPERBOLA, extra.type)
        assertEquals(2, extra.lines.count { it is ExtraLine.Slanted })
        val slopes = extra.lines.filterIsInstance<ExtraLine.Slanted>().map { it.k }
        assertTrue(slopes.any { Math.abs(it - 1.0) < 1e-9 })
        assertTrue(slopes.any { Math.abs(it + 1.0) < 1e-9 })
    }

    @Test
    fun `非二次曲线不给额外信息`() {
        val extra = GraphPlot.extraInfo(engine, "y==sin(x)")
        assertEquals(ConicType.OTHER, extra.type)
        assertTrue(extra.points.isEmpty())
    }

    @Test
    fun `圆解出上下两支`() {
        val branches = GraphPlot.functions(engine, "x^2+y^2==4")
        assertEquals(2, branches.size)
        val values = branches.mapNotNull { it.valueAt(0.0) }.sorted()
        assertEquals(listOf(-2.0, 2.0), values.map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `多函数分隔符切分`() {
        val text = Method.drawFormula("y==x^(2)", "y==2*x")
        assertEquals(listOf("y==2*x", "y==x^(2)"), Method.splitDrawFormula(text))
    }

    @Test
    fun `没有实解的方程画不出来`() {
        // x^2+y^2==-1 解出来是虚的，绘图页应当当作「画不了」
        assertTrue(GraphPlot.functions(engine, "x^2+y^2==-1").isEmpty())
    }
}
