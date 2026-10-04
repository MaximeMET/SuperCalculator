package io.github.maximemet.supercalc.engine

import kotlin.test.Test

/**
 * 极限步骤开工前的摸底：代入后的形状、各类极限引擎给出什么、
 * `Limit` 未求值时长什么样。只打印不打断言。
 */
class LimitAstDiagnosticTest {

    private val engine = SymjaEngine()

    private val cases = listOf(
        "Limit(x^2, x->2)",
        "Limit(Sin(x)/x, x->0)",
        "Limit((1-Cos(x))/x^2, x->0)",
        "Limit((E^x-1)/x, x->0)",
        "Limit(Log(1+x)/x, x->0)",
        "Limit(Tan(x)/x, x->0)",
        "Limit((1+x)^(1/x), x->0)",
        "Limit(x/E^x, x->Infinity)",
        "Limit(x^2, x->Infinity)",
        "Limit(1/x, x->0)",
        "Limit(Sin(x), x->0)",
    )

    private val substitutions = listOf(
        "Sin(x)/x" to "0",
        "(1+x)^(1/x)" to "0",
        "x/E^x" to "Infinity",
        "1/x" to "0",
    )

    @Test
    fun dumpLimits() {
        val sb = StringBuilder()
        for (raw in cases) {
            val parsed = engine.parseOrNull(raw)
            val value = engine.evaluateOrNull(parsed)
            sb.append("==== ").append(raw).append('\n')
            sb.append("  值: ").append(value).append('\n')
            sb.append("  isInfinity=").append(value?.isInfinity)
                .append(" isDirectedInfinity=").append(value?.isDirectedInfinity)
                .append(" isNegativeInfinity=").append(value?.isNegativeInfinity)
                .append(" isIndeterminate=").append(value?.isIndeterminate)
                .append(" isZero=").append(value?.isZero).append('\n')
            sb.append("  LaTeX: ").append(engine.toExactLatex(value)).append('\n')
            sb.append("  TexForm 通道: ").append(engine.evaluateAsLatex(raw)).append('\n')
        }
        for ((body, point) in substitutions) {
            val code = "($body) /. x -> $point"
            val value = engine.evaluateOrNull(engine.parseOrNull(code))
            sb.append("==== 代入 ").append(code).append('\n')
            sb.append("  值: ").append(value)
                .append("  isIndeterminate=").append(value?.isIndeterminate)
                .append(" isInfinity=").append(value?.isInfinity)
                .append(" isDirectedInfinity=").append(value?.isDirectedInfinity)
                .append(" 类: ").append(value?.javaClass?.simpleName).append('\n')
        }
        sb.append("==== E 的 LaTeX: ").append(engine.toExactLatex(org.matheclipse.core.expression.F.E)).append('\n')
        sb.append("==== E 的 TexForm: ").append(engine.evaluateAsLatex("E")).append('\n')
        val out = java.io.File("build/diagnostic/limit-ast.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }

    /** 摸一下重建 AST 的 API：`F.ast(args, head)` 与 `IAST.map` 的行为。 */
    @Test
    fun dumpRebuildApi() {
        val sb = StringBuilder()
        val expr = engine.parseOrNull("Sin(1+x)") as org.matheclipse.core.interfaces.IAST
        sb.append("原: ").append(expr).append(" head=").append(expr.head()).append('\n')
        val rebuilt = org.matheclipse.core.expression.F.ast(
            arrayOf(expr.arg1()),
            expr.head(),
        )
        sb.append("F.ast(args,head): ").append(rebuilt).append(" head=").append(rebuilt.head()).append('\n')
        val mapped = expr.map { child: org.matheclipse.core.interfaces.IExpr ->
            org.matheclipse.core.expression.F.C0
        }
        sb.append("map: ").append(mapped).append(" head=").append(mapped.head()).append('\n')
        print(sb)
        java.io.File("build/diagnostic/rebuild-api.txt").writeText(sb.toString(), Charsets.UTF_8)
    }
}
