package io.github.maximemet.supercalc.engine

import org.matheclipse.core.convert.AST2Expr
import org.matheclipse.core.expression.F
import org.matheclipse.parser.client.Parser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 符号同一性守卫。
 *
 * 方法推荐完全建立在「解析器造出来的 x 和 F.$s("x") 是同一个对象」之上。
 * 2016 版 Symja 的符号表是全局的，这个前提成立；
 * 新版改成了按 EvalEngine 隔离，同一段代码会静默失效——
 * `isFree` 永远返回 true，所有含未知数的表达式都会丢掉全部按钮。
 *
 * 这条测试就是那道防线：哪天有人顺手升级 Symja，它会立刻炸。
 */
class SymbolIdentityTest {

    private val engine = SymjaEngine()

    private fun parse(text: String) =
        AST2Expr.CONST_LC.convert(Parser(true).parse(text))

    @Test
    fun `解析器的符号和 F 的符号是同一个实例`() {
        val expr = parse("x^2")
        assertFalse(engine.isFreeOf(expr, "x"), "x^2 不应被认为「不含 x」")
        assertTrue(engine.isFreeOf(expr, "y"), "x^2 应被认为「不含 y」")
        assertTrue(F.`$s`("x") === F.x, "F.\$s(\"x\") 必须就是 F.x")
    }

    @Test
    fun `多变量表达式按符号分别判定`() {
        val expr = parse("a*x^2+b")
        assertFalse(engine.isFreeOf(expr, "x"))
        assertFalse(engine.isFreeOf(expr, "a"))
        assertFalse(engine.isFreeOf(expr, "b"))
        assertTrue(engine.isFreeOf(expr, "y"))
    }
}
