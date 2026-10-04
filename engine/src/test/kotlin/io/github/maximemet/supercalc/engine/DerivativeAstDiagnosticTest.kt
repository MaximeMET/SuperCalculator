package io.github.maximemet.supercalc.engine

import kotlin.test.Test

/**
 * 求导步骤开工前的 AST 摸底：同一个式子可能有多种等价写法
 * （`sin(x^2)` 是 `Sin(Power(x,2))` 还是别的？`exp(x)` 是不是 `Power(E,x)`？），
 * 规则匹配必须按真实形状写。只打印不打断言。
 */
class DerivativeAstDiagnosticTest {

    private val engine = SymjaEngine()

    private val cases = listOf(
        "x",
        "x^2",
        "x^3",
        "1/x",
        "1/x^2",
        "sqrt(x)",
        "sqrt(x^2+1)",
        "exp(x)",
        "exp(2*x)",
        "ln(x)",
        "ln(x^2+1)",
        "log(x)",
        "sin(x)",
        "cos(x)",
        "tan(x)",
        "cot(x)",
        "sec(x)",
        "csc(x)",
        "arcsin(x)",
        "arctan(x)",
        "2^x",
        "x^x",
        "x^2*sin(x)",
        "sin(x^2)",
        "cos(x)*sin(x)",
        "exp(x)/x",
        "(x^2-1)/(x-1)",
        "x/(x+1)",
        "2*x+3",
        "a*x^2+b*x+c",
        "(x-1)*(x+1)",
        "tan(2*x)",
        "ln(sin(x))",
        "1/(x+1)",
        "x^(1/2)",
        "3*sin(x)",
        "Abs(x)",
    )

    @Test
    fun dumpAst() {
        val sb = StringBuilder()
        for (raw in cases) {
            val parsed = engine.parseOrNull(raw)
            sb.append("==== ").append(raw).append('\n')
            sb.append("  解析: ").append(parsed?.toString()).append('\n')
            sb.append("  头部: ").append((parsed as? org.matheclipse.core.interfaces.IAST)?.head()?.toString())
                .append("  参数数: ").append((parsed as? org.matheclipse.core.interfaces.IAST)?.size).append('\n')
            val diff = engine.evaluateOrNull(engine.parseOrNull("Diff($raw, x)"))
            sb.append("  Diff: ").append(diff?.toString()).append('\n')
            sb.append("  Diff LaTeX: ").append(engine.toExactLatex(diff)).append('\n')
        }
        val out = java.io.File("build/diagnostic/derivative-ast.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }

    /** 验证一个猜测：TeXT 通道会就地重排 Plus 的项序。 */
    @Test
    fun dumpPlusOrder() {
        val sb = StringBuilder()
        val parsed = engine.parseOrNull("x^3-6*x^2+11*x-6")
        sb.append("解析后: ").append(parsed).append('\n')
        sb.append("参数序: ").append(describeArgs(parsed)).append('\n')
        engine.toExactLatex(parsed)
        sb.append("TeX 后: ").append(parsed).append('\n')
        sb.append("参数序: ").append(describeArgs(parsed)).append('\n')
        print(sb)
        val out = java.io.File("build/diagnostic/plus-order.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun describeArgs(expr: org.matheclipse.core.interfaces.IExpr?): String {
        val ast = expr as? org.matheclipse.core.interfaces.IAST ?: return "非 AST"
        return buildString {
            append(ast.head()).append("(")
            for (i in 1 until ast.size) {
                if (i > 1) append(", ")
                append(ast.get(i))
            }
            append(")")
        }
    }
}
