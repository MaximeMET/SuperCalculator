package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class LatexTextTest {

    @Test
    fun `编辑器 latex 里 MathJax 不认的命令被换掉`() {
        // 键盘上的 ÷ 键，MathQuill 给的是 \slash
        assertEquals("1/2+1/3", LatexText.editorSafe("1\\slash2+1\\slash3"))
        assertEquals("\\frac{1}{2}", LatexText.editorSafe("\\frac{1}{2}"))
    }

    @Test
    fun `小数按设置位数四舍五入`() {
        assertEquals("0.3333333333", LatexText.toFixPoint("0.3333333333333333", 10))
        assertEquals("0.1428571429", LatexText.toFixPoint("0.14285714285714285", 10))
        assertEquals("1.4142135624", LatexText.toFixPoint("1.4142135623730951", 10))
    }

    @Test
    fun `整数和结构不受影响`() {
        assertEquals("14", LatexText.toFixPoint("14", 10))
        assertEquals("\\frac{1}{3}", LatexText.toFixPoint("\\frac{1}{3}", 10))
        assertEquals("2 \\cdot x", LatexText.toFixPoint("2 \\cdot x", 10))
    }

    @Test
    fun `尾零被去掉但保留一位小数`() {
        assertEquals("1.5", LatexText.replaceTailZeros("1.500"))
        assertEquals("1.0", LatexText.replaceTailZeros("1.000"))
        assertEquals("0.0", LatexText.replaceTailZeros("-0.000"))
        assertEquals("1", LatexText.replaceTailZeros("1.000", trimPoint = true))
    }

    @Test
    fun `科学计数法改写成 LaTeX`() {
        assertEquals("1.5\\cdot10^{-7}", LatexText.withoutScientificNotation("1.5E-7"))
        assertEquals("2.0\\cdot10^{12}", LatexText.withoutScientificNotation("2.0E12"))
    }

    @Test
    fun `数值结果按设置位数格式化`() {
        // 极限兜底这类我们自己的数值通道走这里，形状要和引擎输出一致
        assertEquals("2.7182818286", LatexText.fromDouble(2.718281828586351, 10))
        assertEquals("1", LatexText.fromDouble(0.99999999998, 10))
        assertEquals("0.0000001", LatexText.fromDouble(1e-7, 10))
        assertEquals("", LatexText.fromDouble(Double.NaN, 10))
    }

    @Test
    fun `去掉引擎输出外层的引号`() {
        assertEquals("\\frac{1}{3}", LatexText.replaceQuotes("\"\\frac{1}{3}\""))
        assertEquals("14", LatexText.replaceQuotes("14"))
    }

    @Test
    fun `单参数函数的花括号改成圆括号`() {
        assertEquals("\\sin(x)", LatexText.replaceFunctionBracket("\\sin{x}"))
        // 只替换紧挨函数名的那一对花括号（参考实现的算法就是只处理第一层）
        assertEquals("\\log(2){x}", LatexText.replaceFunctionBracket("\\log{2}{x}"))
    }

    @Test
    fun `反双曲记号换成 MathJax 认识的写法`() {
        assertEquals("\\operatorname{arcsinh}{x}", LatexText.mathJaxSafe("\\arcsinh{x}"))
        assertEquals(
            "\\frac{\\operatorname{arccosh}{x}}{2}",
            LatexText.mathJaxSafe("\\frac{\\arccosh{x}}{2}"),
        )
        // 普通函数不动
        assertEquals("\\sin{x}+\\cos{x}", LatexText.mathJaxSafe("\\sin{x}+\\cos{x}"))
    }
}
