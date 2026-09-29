package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IExpr

/**
 * 决定「当前这行表达式该给出哪几个运算按钮」。
 *
 * 这是原版最讨喜的一处设计：用户不用去菜单里找功能，输入什么就出现什么按钮。
 * 判定完全基于 Symja 的表达式树，不靠字符串匹配。
 *
 * 判定顺序在参考实现里是有意义的（先判 Limit，再判 NIntegrate……），
 * 所以这里保持同样的优先级，不要重排。
 */
class MethodAdvisor(private val engine: SymjaEngine) {

    fun advise(
        formula: String,
        lastFormula: String = "",
        needCalc: Boolean = true,
    ): List<Method> {
        if (formula.isEmpty()) return emptyList()
        val expr = engine.parseOrNull(formula) ?: return emptyList()
        return advise(expr, formula, lastFormula, needCalc)
    }

    private fun advise(
        expr: IExpr,
        formula: String,
        lastFormula: String,
        needCalc: Boolean,
    ): List<Method> {
        val methods = mutableListOf<Method>()

        // 1. 极限表达式 -> 只能求数值
        if (!expr.isFree(F.Limit)) return listOf(Method.Numeric)

        // 2. 含定积分符号 -> 走定积分
        if (!expr.isFree(F.NIntegrate)) return listOf(Method.DInte)

        // 3. 纯数字，或求公倍数/公约数 -> 直接计算
        if (expr.isNumber || !expr.isFree(F.LCM) || !expr.isFree(F.GCD)) {
            return if (needCalc) listOf(Method.Calc) else emptyList()
        }

        if (engine.isInvalid(expr)) return emptyList()

        val unknown = engine.unknownSymbol()

        // 4. 只含 x/y/z 之外的符号 -> 视为普通计算
        val freeOfXYZ = engine.isFreeOf(expr, "x") &&
            engine.isFreeOf(expr, "y") &&
            engine.isFreeOf(expr, "z")
        if (freeOfXYZ) {
            return if (needCalc) listOf(Method.Calc) else emptyList()
        }

        // 5. 不等式
        if (isInequality(expr)) {
            methods += Method.SolveIneq
            if (lastFormula.isNotEmpty()) methods += Method.SolveIneq2
            return methods
        }

        // 6. 等式
        if (isEquality(expr)) {
            if (formula.indexOf("==") == formula.lastIndexOf("==")) {
                if (lastFormula.isNotEmpty()) {
                    methods += Method.Solve2
                } else if (!expr.isFree(unknown)) {
                    methods += Method.Solve
                }
                // 等式分支的绘图判据与函数分支不同：必须 x、y 同时出现才给绘图。
                // （基准实测：`x^2==1` 只给「求解方程」，`x^2==y` 才给「绘制图像」）
                if (!engine.isFreeOf(expr, "x") && !engine.isFreeOf(expr, "y")) {
                    methods += Method.Draw
                }
            }
            return methods
        }

        // 7. 含未知数的函数式
        if (!expr.isFree(unknown)) {
            methods += Method.Integrate
            methods += Method.Derivative
            if (isDrawable(expr)) methods += Method.Draw
            if (expr.isPolynomial(unknown) && !expr.isPolynomialOfMaxDegree(unknown, 1L)) {
                methods += Method.Expand
                methods += Method.Decompose
            }
        }
        return methods
    }

    /**
     * 能否绘图：含 x，且不含 z / a / b / c / h / k / p 这些参数符号。
     * 判据来自参考实现，避免对含参数的式子错误地画图。
     */
    private fun isDrawable(expr: IExpr): Boolean {
        val forbidden = listOf("z", "a", "b", "c", "h", "k", "p")
        if (forbidden.any { !engine.isFreeOf(expr, it) }) return false
        return !engine.isFreeOf(expr, "x")
    }

    private fun isEquality(expr: IExpr): Boolean = !expr.isFree(F.Equal)

    private fun isInequality(expr: IExpr): Boolean = !(
        expr.isFree(F.Less) && expr.isFree(F.Greater) &&
            expr.isFree(F.GreaterEqual) && expr.isFree(F.LessEqual)
        )
}
