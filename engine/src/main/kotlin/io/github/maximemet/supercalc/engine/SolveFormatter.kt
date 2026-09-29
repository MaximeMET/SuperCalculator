package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.ISymbol

/**
 * 求解方程的显示加工。
 *
 * 参考实现会给「不够直白」的根再补一段数值形式：
 *
 *   x^2==2    ->  x= \sqrt{2}= 1.4142135624
 *   ln(x)==1  ->  x= e= 2.7182818285
 *   x^2==-1   ->  x= -1\,i = 0.0 + -1.0\,i
 *
 * 整数根和有理根不加（`x= 2`、`x= \frac{1}{5}` 都只有一段），
 * 含参数、数值化不了的根也不加（`x= \sqrt{a}`、`x= \frac{-b}{a}`）。
 *
 * 拼接用的是 **Rule**（TeX 里是 `\to`，随后被替换成 `=`），不是 Equal——
 * `Equal[\sqrt{2}, 1.4142135623730951]` 会被 Symja 直接判成 True，
 * 那样就只能显示一个 `true`。
 */
object SolveFormatter {

    /** 加工 `Solve(...)` 的结果；形状对不上就返回 null，交回原路径。 */
    fun decorate(engine: SymjaEngine, formula: String, unknown: String): String? {
        val solved = engine.evaluateOrNull(
            engine.parseOrNull(MethodConsts.SYMJA_SOLVE.format(formula, unknown))
        ) ?: return null
        if (!solved.isAST(F.List)) return null

        val x = engine.symbol(unknown)
        val decorated = decorateSolutions(engine, solved as IAST, x) ?: return null
        return engine.toLatex(decorated)
    }

    private fun decorateSolutions(engine: SymjaEngine, outer: IAST, x: ISymbol): IAST? {
        val groups = mutableListOf<IExpr>()
        for (i in 1 until outer.size) {
            val inner = outer.get(i)
            if (!inner.isAST(F.List)) return null
            val group = inner as IAST
            val rules = mutableListOf<IExpr>()
            for (j in 1 until group.size) {
                val rule = group.get(j)
                if (!rule.isAST(F.Rule)) return null
                val ast = rule as IAST
                if (ast.size != 3) return null
                rules.add(F.Rule(x, decorateRoot(engine, ast.arg2(), x)))
            }
            groups.add(F.List(*rules.toTypedArray()))
        }
        return F.List(*groups.toTypedArray())
    }

    /** 给单个根决定要不要补数值形式。 */
    private fun decorateRoot(engine: SymjaEngine, root: IExpr, x: ISymbol): IExpr {
        // 含未知数（含参数解）不动
        if (!root.isFree(x)) return root
        // 整数、有理数本身已经够直观
        if (root.isInteger || root.isFraction) return root
        val numeric = engine.evaluateOrNull(F.N(root)) ?: return root
        if (!numeric.isNumber) return root
        return F.Rule(root, numeric)
    }
}
