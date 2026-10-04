package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.IPattern

/**
 * 规则包共用的模式工具：整串匹配 + 模板代入。
 *
 * 为什么不用引擎自带的 `ReplaceAll`：
 *  - 它会连**子表达式**一起换。`Sin(u_) -> -Cos(u_)` 套到 `Sin(x)*Cos(x)` 上
 *    会把里面的 `Sin` 换掉，规则包就"认错了题"；
 *  - 我们只要整串匹配：`u_` 绑子表达式，其余节点要求头与参数逐个相同。
 *
 * 通配符 `u_` 在 2016 版里是 `org.matheclipse.core.expression.Pattern`
 * （`IPattern`，不是 IAST）。
 */
internal object RulePatterns {

    fun matchWhole(pattern: IExpr, expr: IExpr, bindings: MutableMap<String, IExpr>): Boolean {
        val wildcard = wildcardName(pattern)
        if (wildcard != null) {
            val existing = bindings[wildcard]
            if (existing == null) {
                bindings[wildcard] = expr
                return true
            }
            return existing.equals(expr)
        }
        val patternAst = pattern as? IAST
        val exprAst = expr as? IAST
        if (patternAst == null || exprAst == null) return pattern.toString() == expr.toString()
        if (patternAst.size != exprAst.size) return false
        if (patternAst.head().toString() != exprAst.head().toString()) return false
        for (i in 1 until patternAst.size) {
            if (!matchWhole(patternAst.get(i), exprAst.get(i), bindings)) return false
        }
        return true
    }

    private fun wildcardName(expr: IExpr): String? {
        val pattern = expr as? IPattern ?: return null
        if (!pattern.isPattern) return null
        return pattern.symbol?.toString()
    }

    /** 把模板里的 `u_` 换成绑定的表达式（字符串层替换，结果包一层括号）。 */
    fun instantiate(template: String, bindings: Map<String, IExpr>): String =
        bindings.entries.fold(template) { acc, (name, value) ->
            acc.replace("$name" + "_", "($value)")
        }

    /** 说明文案里的 `{u}` 占位符换成绑定表达式的文本形式。 */
    fun fillNote(template: String, bindings: Map<String, IExpr>): String =
        bindings.entries.fold(template) { acc, (name, value) ->
            acc.replace("{$name}", value.toString())
        }
}
