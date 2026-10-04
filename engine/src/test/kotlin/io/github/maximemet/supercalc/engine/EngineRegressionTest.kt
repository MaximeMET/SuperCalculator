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

    // ---------- 三角函数的自动度数 ----------

    /**
     * 编辑器在 sin/cos/tan 里敲数字时会自动补 `\degree`（原版行为），
     * 送进引擎的是 `sin(5degree)`。基准：原版算出来是 sin(π/36) = 0.0871557427。
     */
    @Test
    fun `编辑器补的度数符号按角度算`() {
        assertEquals(
            "= \\sin{\\frac{\\pi}{36}}$$= 0.0871557427",
            session("sin(5degree)").autoResult(),
        )
    }

    // ---------- 极限 ----------

    @Test
    fun `含极限时只给计算结果按钮`() {
        assertEquals(listOf(Method.Limit), session("Limit(sin(x)/x,x->0)").availableMethods())
    }

    @Test
    fun `重要极限能算出来`() {
        // 参考实现这一格挂的是 RawMethod：公式原样送引擎，外面不套 N()。
        // 套了 N() 的话 Symja 的数值模式会把 x->0 变成 x->0.0，
        // 极限规则匹配不上，lim sin(x)/x 会算成 NaN（用户报的就是这个）。
        assertEquals("1", session("Limit(sin(x)/x,x->0)").evaluate(Method.Limit))
        assertEquals("\\frac{1}{2}", session("Limit((1-cos(x))/x^2,x->0)").evaluate(Method.Limit))
        assertEquals("4", session("Limit(x^2,x->2)").evaluate(Method.Limit))
    }

    @Test
    fun `无穷用编辑器写的 infty 也能算`() {
        // 键盘上 ∞ 键的 symja 输出是小写 `infty`（原版 MathQuill 就长这样），
        // 引擎侧把它归一成 Symja 认的 Infinity。
        assertEquals("e", session("Limit((1+1/x)^x,x->infty)").evaluate(Method.Limit))
        assertEquals("0", session("Limit(1/x,x->infty)").evaluate(Method.Limit))
    }

    @Test
    fun `Symja 算不出的 1 的无穷次方极限走兜底通道`() {
        // Symja 2016 对 x→0 的 1^∞ 形状原样返回不求值（实测见 work/logs/limitprobe.txt），
        // 兜底先做取对数改写拿到精确的 e，数值结果只当交叉验证。用户报的
        // 「重要极限算不出来」就是这一格。
        assertEquals("e", session("Limit((1+x)^(1/x),x->0)").evaluate(Method.Limit))
    }

    // ---------- 运算结果 ----------

    @Test
    fun `求导结果`() {
        // 期望值全部来自基准 App 的插桩探针实测（work/corpus/ref_methods.tsv）
        assertEquals("2\\,x", session("x^2").evaluate(Method.Derivative))
        assertEquals("\\frac{-1}{x^{2}}", session("1/x").evaluate(Method.Derivative))
        assertEquals("\\cos{x}", session("sin(x)").evaluate(Method.Derivative))
    }

    @Test
    fun `积分结果带常数项`() {
        val r = session("x^2").evaluate(Method.Integrate)
        assertTrue(r != null && r.endsWith(" + C"), "积分结果应带 + C，实际是 $r")
    }

    @Test
    fun `因式分解结果`() {
        // 基准行为：项按降幂排（x-1 而不是 -1+x），多因子之间用 \, 分隔。
        assertEquals(
            "\\left( x-1\\right) \\,\\left( x+1\\right) ",
            session("x^2-1").evaluate(Method.Decompose),
        )
    }

    // ---------- 不等式 ----------
    // 期望值全部来自基准 App 的插桩探针实测。输出形状是「列表套列表」：
    // 外层是若干情形（或），内层是同时成立的条件（且），Symja 会把它排成矩阵。

    @Test
    fun `一元一次不等式`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx > 3 \n\\end{array}\n\\right) ",
            session("x>3").evaluate(Method.SolveIneq),
        )
    }

    @Test
    fun `二次不等式给出两段`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx < -2 \\\\\nx > 2 \n\\end{array}\n\\right) ",
            session("x^2>4").evaluate(Method.SolveIneq),
        )
    }

    @Test
    fun `二次不等式给出夹逼区间`() {
        assertEquals(
            "\\left(\n\\begin{array}{cc}\nx > -3 & x < 3 \n\\end{array}\n\\right) ",
            session("x^2<9").evaluate(Method.SolveIneq),
        )
    }

    @Test
    fun `分式不等式的断点不算解`() {
        // 1/x > 1 的解是 (0, 1)：x=0 是分母零点，不能写成闭端点。
        assertEquals(
            "\\left(\n\\begin{array}{cc}\nx > 0 & x < 1 \n\\end{array}\n\\right) ",
            session("1/x>1").evaluate(Method.SolveIneq),
        )
    }

    @Test
    fun `绝对值不等式先平方化`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx < -1 \\\\\nx > 1 \n\\end{array}\n\\right) ",
            session("abs(x)>1").evaluate(Method.SolveIneq),
        )
    }

    // ---------- 求解方程的数值后缀 ----------

    @Test
    fun `无理根补一段数值形式`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx= \\sqrt{2}= 1.4142135624 \\\\\n" +
                "x=  - \\sqrt{2}= -1.4142135624 \n\\end{array}\n\\right) ",
            session("x^2==2").evaluate(Method.Solve),
        )
    }

    @Test
    fun `整数根不补数值形式`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx= -1 \\\\\nx= 1 \n\\end{array}\n\\right) ",
            session("x^2==1").evaluate(Method.Solve),
        )
    }

    @Test
    fun `有理根不补数值形式`() {
        assertEquals(
            "\\left(\n\\begin{array}{c}\nx= \\frac{1}{5} \n\\end{array}\n\\right) ",
            session("5*x==1").evaluate(Method.Solve),
        )
    }
}
