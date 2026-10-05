package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
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

    /**
     * 有交换律的头：`a+b` 与 `b+a`、`a*b` 与 `b*a` 在引擎里是同一个东西，
     * 参数顺序不该影响匹配——不然 `1/(x^2+4)` 和 `1/(4+x^2)` 会一条命中、一条不中。
     */
    private val COMMUTATIVE = setOf("Plus", "Times")

    /** 穷举排列的上限：Plus/Times 参数再多就不硬排了，退回按顺序匹配。 */
    private const val MAX_COMMUTATIVE_ARGS = 6

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
        // 负数：模式里写 (-1)*a_，引擎求值之后可能已经收成 -4，把 |值| 绑给通配符
        if (matchNegatedConstant(pattern, expr, bindings)) return true
        val patternAst = pattern as? IAST
        val exprAst = expr as? IAST
        if (patternAst == null || exprAst == null) return sameAtom(pattern, expr)
        if (patternAst.size != exprAst.size) return false
        val head = patternAst.head().toString()
        if (head != exprAst.head().toString()) return false
        val patternArgs = (1 until patternAst.size).map { patternAst.get(it) }
        val exprArgs = (1 until exprAst.size).map { exprAst.get(it) }
        if (head in COMMUTATIVE && patternArgs.size in 2..MAX_COMMUTATIVE_ARGS) {
            return matchCommutative(patternArgs, exprArgs, bindings)
        }
        for (i in patternArgs.indices) {
            if (!matchWhole(patternArgs[i], exprArgs[i], bindings)) return false
        }
        return true
    }

    /**
     * 交换律头的匹配：给每个 pattern 参数找**位置互不重复**的 expr 参数，带回溯。
     * 通配符绑定在回溯时要能回滚，否则一条走不通的分支会污染后面的尝试。
     */
    private fun matchCommutative(
        patternArgs: List<IExpr>,
        exprArgs: List<IExpr>,
        bindings: MutableMap<String, IExpr>,
    ): Boolean {
        val used = BooleanArray(exprArgs.size)
        fun step(index: Int): Boolean {
            if (index == patternArgs.size) return true
            for (i in exprArgs.indices) {
                if (used[i]) continue
                val snapshot = LinkedHashMap(bindings)
                used[i] = true
                val ok = matchWhole(patternArgs[index], exprArgs[i], bindings) && step(index + 1)
                used[i] = false
                if (ok) return true
                bindings.clear()
                bindings.putAll(snapshot)
            }
            return false
        }
        return step(0)
    }

    private fun wildcardName(expr: IExpr): String? {
        val pattern = expr as? IPattern ?: return null
        if (!pattern.isPattern) return null
        return pattern.symbol?.toString()
    }

    /**
     * 负常数的匹配：模式写 `(-1)*a_`，而引擎求值后的表达式里是一个负数字面量（`x²-4`
     * 内部的 `-4` 就是这样）。两者等价，匹配时把 `|值|` 绑给通配符——`a_` 拿到 4，
     * `Sqrt(a_)`、`2*Sqrt(a_)` 这类模板才写得下去。
     */
    private fun matchNegatedConstant(
        pattern: IExpr,
        expr: IExpr,
        bindings: MutableMap<String, IExpr>,
    ): Boolean {
        if (!expr.isNumber || !expr.isNegative) return false
        val ast = pattern as? IAST ?: return false
        if (!ast.isAST(F.Times) || ast.size != 3) return false
        val rest = when {
            ast.arg1().isMinusOne -> ast.arg2()
            ast.arg2().isMinusOne -> ast.arg1()
            else -> return false
        }
        val magnitude = F.eval(F.Negate(expr))
        val wildcard = wildcardName(rest)
        if (wildcard != null) {
            val existing = bindings[wildcard]
            if (existing == null) {
                bindings[wildcard] = magnitude
                return true
            }
            return existing.equals(magnitude)
        }
        return matchWhole(rest, magnitude, bindings)
    }

    /**
     * 原子对原子的比较。
     *
     * 不能只比字符串：`Limit(...)` 的参数会被引擎求值，`-1` 在里面是整数 `-1`，
     * 而刚解析出来的模式里写作 `(-1)*1`——同一个数两种写法，直接比字符串就漏掉了。
     * 先求值再比字符串，两种写法都能对上。
     */
    private fun sameAtom(a: IExpr, b: IExpr): Boolean {
        if (a.toString() == b.toString()) return true
        val ea = runCatching { F.eval(a).toString() }.getOrDefault(a.toString())
        val eb = runCatching { F.eval(b).toString() }.getOrDefault(b.toString())
        return ea == eb
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

    /** 公式模板里的 `{u}` 占位符换成绑定表达式的 LaTeX（求导公式串用）。 */
    fun fillLatex(template: String, bindings: Map<String, IExpr>, engine: SymjaEngine): String =
        bindings.entries.fold(template) { acc, (name, value) ->
            val tex = engine.toExactLatex(value) ?: value.toString()
            acc.replace("{$name}", tex)
        }
}
