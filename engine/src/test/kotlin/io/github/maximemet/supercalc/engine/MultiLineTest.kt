package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多行输入（方程组 / 不等式组）。
 *
 * 换行符是 `\newline` 命令的 symja 输出，字面量「反斜杠 + n」两个字符。
 * 参考实现在 `SymjaManager.parseMultilineFormula()` 里按**最后一个**换行符切开，
 * 之前的部分存进 `lastFormula`，之后的部分是当前行。
 */
class MultiLineTest {

    @Test
    fun `最后一个换行符处切开`() {
        val session = CalculationSession()
        session.setFormula("y==x+1\\ny==2*x+3", "")
        assertEquals("y==2*x+3", session.formula)
        assertEquals("y==x+1", session.lastFormula)
    }

    @Test
    fun `三段输入只切最后一段`() {
        val session = CalculationSession()
        session.setFormula("a==1\\nb==2\\nc==3", "")
        assertEquals("c==3", session.formula)
        assertEquals("a==1\\nb==2", session.lastFormula)
    }

    @Test
    fun `方程组给出求解方程组和绘制图像`() {
        val session = CalculationSession()
        session.setFormula("30*x+15*y==675\\n42*x+20*y==940", "")
        assertEquals(listOf(Method.Solve2, Method.Draw), session.availableMethods())
    }

    @Test
    fun `方程组结果和参考实机一致`() {
        val session = CalculationSession()
        session.setFormula("30*x+15*y==675\\n42*x+20*y==940", "")
        val latex = session.evaluate(Method.Solve2)
        // 参考实机（模拟器 1280×2800）实测渲染出 `( x = 20  y = 5 )`。
        // 我们这边出的是同一组解，只是包了一层 `\begin{array}`（参考实机的
        // Symja 私有分支里矩阵会摊平），渲染出来都是「两个等式并排」。
        assertTrue(latex != null, "方程组应当有解")
        assertTrue(latex!!.contains("x= 20"), "x 应当是 20：$latex")
        assertTrue(latex.contains("y= 5"), "y 应当是 5：$latex")
    }

    @Test
    fun `allFormula 按参考实现拼装`() {
        assertEquals("a, b, c", Method.allFormula("a\\nb", "c"))
        assertEquals("a*", Method.allFormula("", "a*"))
        // 参考实现会剥掉字段末尾的 `*`（乘号残渣）
        assertEquals("a, b", Method.allFormula("a*", "b"))
        // 行首的 `*` 就是 Marker.ANY_MARKER，也一样剥掉
        assertEquals("a, b, c", Method.allFormula("*a\\n*b", "c"))
    }

    @Test
    fun `未知数按 x y z 的顺序列出`() {
        assertEquals("{x,y}", Method.unknowns("30*x+15*y==675, 42*x+20*y==940"))
        assertEquals("{x}", Method.unknowns("x==1"))
        assertEquals("{x,y,z}", Method.unknowns("x+y+z==1"))
    }

    @Test
    fun `不等式组走解不等式组`() {
        val session = CalculationSession()
        session.setFormula("x>1\\nx<5", "")
        // 参考实现先给「解不等式」，再给「解不等式组」，顺序一致
        assertEquals(listOf(Method.SolveIneq, Method.SolveIneq2), session.availableMethods())
    }
}
