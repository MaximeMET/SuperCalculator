package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 绘图页的数学支撑。
 *
 * 参考实现的图像页做了两件事：
 *  1. 把公式变成能按 x 取值的一元函数（`y==x^2`、`x^2+y^2==1` 都要支持）；
 *  2. 对二次曲线额外算出「特殊点」（焦点/中心/极值点）和「准线/渐近线」，
 *     画成白点和橙色虚线。
 *
 * 第 2 步在参考实现里由 Symja 的一个私有分支（`PlotUtils`）完成，那份源码没有公开。
 * 这里按标准解析几何重写了一份，行为对齐到「同样是二次曲线时给出同样的点和线」。
 */
object GraphPlot {

    /** 自变量恒为 x：绘图页的横轴与设置里的「未知数」无关。 */
    const val VARIABLE = "x"

    /** 参数变量。 */
    const val PARAM = "y"

    /** 解析公式，最多得到两支函数（圆、椭圆会解出上下两支）。 */
    fun functions(engine: SymjaEngine, formula: String): List<GraphFunction> {
        if (formula.isEmpty()) return emptyList()
        val body = formula.removePrefix("y==")
        if (!containsVariable(body, PARAM)) {
            // 已经是 y = f(x) 的形式（或者干脆只有一个表达式）
            if (body.contains("==")) return emptyList()
            return listOf(GraphFunction(engine, body))
        }
        // 式子里还有 y：解出来，最多取两支
        val solved = engine.evaluateRaw("Solve(${formula},y)")
        return solvedBranches(solved)
            .map { GraphFunction(engine, it) }
            .filter { it.hasRealValue() }
            .take(2)
    }

    /**
     * 二次曲线的额外信息。
     *
     * 判定方式与参考实现一致：把 `左边 - 右边` 展开后只看 x²、x、y²、y 和常数项
     * （交叉项 xy 不参与——参考实现同样没有处理，带交叉项的输入会被判成「画不了」）。
     */
    fun extraInfo(engine: SymjaEngine, formula: String): GraphExtra {
        val other = GraphExtra(ConicType.OTHER, emptyList(), emptyList())
        val parts = splitEquation(formula) ?: return other
        val expanded = engine.evaluateOrNull(
            engine.parseOrNull("ExpandAll((${parts.first}) - (${parts.second}))")
        ) ?: return other

        val coefficients = ConicCoefficients.of(engine, expanded) ?: return other
        return coefficients.describe()
    }

    /** `x^2+y^2==1` -> ("x^2+y^2", "1")；没有等号就按 `y == 右边` 处理。 */
    private fun splitEquation(formula: String): Pair<String, String>? {
        val index = formula.indexOf("==")
        if (index < 0) return Pair(PARAM, formula)
        val left = formula.substring(0, index)
        val right = formula.substring(index + 2)
        if (left.isEmpty() || right.isEmpty()) return null
        return Pair(left, right)
    }

    /** `{{y->-Sqrt(1-x^2)},{y->Sqrt(1-x^2)}}` 这类解里挑出真正的函数式。 */
    private fun solvedBranches(solved: String): List<String> {
        if (solved.isEmpty() || !solved.startsWith("{{")) return emptyList()
        return solved.removePrefix("{{").removeSuffix("}}")
            .split("},{")
            .mapNotNull { branch ->
                val arrow = branch.indexOf("->")
                if (arrow < 0) null else branch.substring(arrow + 2)
            }
            // 复根画不出来
            .filter { !it.contains("I*") && !it.contains("Complex") }
    }

    private fun containsVariable(expr: String, name: String): Boolean {
        var i = 0
        while (i < expr.length) {
            if (expr[i] == name[0] &&
                (i == 0 || !isNameChar(expr[i - 1])) &&
                (i + 1 >= expr.length || !isNameChar(expr[i + 1]))
            ) {
                return true
            }
            i++
        }
        return false
    }

    private fun isNameChar(c: Char): Boolean = c.isLetter() || c.isDigit() || c == '_'

    /** 解出来的分支可能是复根（比如 x^2+y^2==-1），取几个点试一下就知道画不画得出来。 */
    private fun GraphFunction.hasRealValue(): Boolean =
        REAL_PROBE_POINTS.any { valueAt(it) != null }

    private val REAL_PROBE_POINTS = doubleArrayOf(0.0, 1.0, -1.0, 0.5)
}

/** 可以按 x 取值的一元函数。 */
class GraphFunction internal constructor(
    private val engine: SymjaEngine,
    internal val body: String,
) {
    /** 取 x 处的函数值；落在定义域外或算不出数值时返回 null。 */
    fun valueAt(x: Double): Double? =
        engine.numericValueOf("($body) /. ${GraphPlot.VARIABLE} -> $x")
}

/** 二次曲线的类型。 */
enum class ConicType { OTHER, CIRCLE, ELLIPSE, HYPERBOLA, PARABOLA }

/** 特殊点的种类。 */
enum class SpecialPointKind { FOCUS, CENTER, MIN, MAX }

/** 白点：曲线上的特殊点（焦点、中心、极值点）。 */
data class SpecialPoint(val x: Double, val y: Double, val kind: SpecialPointKind)

/** 橙色虚线的形状。 */
sealed class ExtraLine {
    /** 竖直准线 x = value。 */
    data class Vertical(val x: Double) : ExtraLine()

    /** 水平准线 y = value。 */
    data class Horizontal(val y: Double) : ExtraLine()

    /** 斜渐近线 y = kx + b。 */
    data class Slanted(val k: Double, val b: Double) : ExtraLine()
}

/** 二次曲线分析结果。 */
data class GraphExtra(
    val type: ConicType,
    val points: List<SpecialPoint>,
    val lines: List<ExtraLine>,
)

/**
 * 展开后的二次式 `xA*x^2 + xB*x + yA*y^2 + yB*y + c`。
 *
 * 参考实现会先配方（把一次项并进平方里），这里保持同样的中间量，
 * 这样特殊点/准线的公式可以逐条对上。
 */
private class ConicCoefficients(
    val xA: Double,
    val xB: Double,
    val yA: Double,
    val yB: Double,
    /** 配好方的常数项。 */
    val c: Double,
) {

    fun describe(): GraphExtra {
        // 两个平方项都没有：这是一条直线或者根本不是二次曲线，参考实现同样画不了
        if (xA == 0.0 && yA == 0.0) return GraphExtra(ConicType.OTHER, emptyList(), emptyList())
        val type = when {
            xA == 0.0 || yA == 0.0 -> ConicType.PARABOLA
            Math.signum(xA) == Math.signum(yA) -> when {
                c >= 0 -> return GraphExtra(ConicType.OTHER, emptyList(), emptyList())
                xA == yA -> ConicType.CIRCLE
                else -> ConicType.ELLIPSE
            }
            else -> ConicType.HYPERBOLA
        }
        return when (type) {
            ConicType.CIRCLE -> GraphExtra(type, centerPoint(), emptyList())
            ConicType.ELLIPSE -> GraphExtra(type, centerPoint() + focusPoints(), emptyList())
            ConicType.HYPERBOLA -> GraphExtra(type, centerPoint() + focusPoints(), asymptotes())
            else -> parabola()
        }
    }

    private fun centerPoint(): List<SpecialPoint> {
        if (xA == 0.0 || yA == 0.0) return emptyList()
        return listOf(SpecialPoint(-xB / (2 * xA), -yB / (2 * yA), SpecialPointKind.CENTER))
    }

    private fun focusPoints(): List<SpecialPoint> {
        if (xA == 0.0 || yA == 0.0) return emptyList()
        val centerX = -xB / (2 * xA)
        val centerY = -yB / (2 * yA)
        return if (Math.signum(xA) == Math.signum(yA)) {
            // 椭圆：焦点落在长轴上
            if (1 / xA > 1 / yA) {
                val f = Math.sqrt(-c / xA + c / yA)
                listOf(
                    SpecialPoint(centerX + f, centerY, SpecialPointKind.FOCUS),
                    SpecialPoint(centerX - f, centerY, SpecialPointKind.FOCUS),
                )
            } else {
                val f = Math.sqrt(-c / yA + c / xA)
                listOf(
                    SpecialPoint(centerX, centerY + f, SpecialPointKind.FOCUS),
                    SpecialPoint(centerX, centerY - f, SpecialPointKind.FOCUS),
                )
            }
        } else if (c < 0) {
            // 双曲线：焦点在实的那个轴上
            if (xA < 0) {
                val f = Math.sqrt(c / xA - c / yA)
                listOf(
                    SpecialPoint(centerX, centerY + f, SpecialPointKind.FOCUS),
                    SpecialPoint(centerX, centerY - f, SpecialPointKind.FOCUS),
                )
            } else {
                val f = Math.sqrt(-c / xA + c / yA)
                listOf(
                    SpecialPoint(centerX + f, centerY, SpecialPointKind.FOCUS),
                    SpecialPoint(centerX - f, centerY, SpecialPointKind.FOCUS),
                )
            }
        } else if (xA < 0) {
            val f = Math.sqrt(-c / xA + c / yA)
            listOf(
                SpecialPoint(centerX + f, centerY, SpecialPointKind.FOCUS),
                SpecialPoint(centerX - f, centerY, SpecialPointKind.FOCUS),
            )
        } else {
            val f = Math.sqrt(c / xA - c / yA)
            listOf(
                SpecialPoint(centerX, centerY + f, SpecialPointKind.FOCUS),
                SpecialPoint(centerX, centerY - f, SpecialPointKind.FOCUS),
            )
        }
    }

    private fun asymptotes(): List<ExtraLine> {
        if (xA == 0.0 || yA == 0.0) return emptyList()
        val centerX = -xB / (2 * xA)
        val centerY = -yB / (2 * yA)
        val k = Math.sqrt(-xA / yA)
        return listOf(
            ExtraLine.Slanted(k, centerY - k * centerX),
            ExtraLine.Slanted(-k, centerY + k * centerX),
        )
    }

    /** 抛物线：一条准线 + 一个焦点 + 一个极值点。 */
    private fun parabola(): GraphExtra {
        val points = mutableListOf<SpecialPoint>()
        val lines = mutableListOf<ExtraLine>()
        if (yA == 0.0) {
            // y = a x^2 + b x + c 形状，开口上下
            val focusX = -xB / (2 * xA)
            val shift = if (yB != 0.0) c / yB else 0.0
            points += SpecialPoint(focusX, -yB / (4 * xA) - shift, SpecialPointKind.FOCUS)
            lines += ExtraLine.Horizontal(yB / (4 * xA) - shift)
            // 顶点：把式子整理成 y = a(x - h)^2 + k 后的 (h, k)
            if (yB != 0.0) {
                val a = xA / yB
                val b = xB / yB
                val cc = (xB * xB / (4 * xA) - c) / yB
                val vertexX = -b / (2 * a)
                val vertexY = (4 * a * cc - b * b) / (4 * a)
                points += SpecialPoint(
                    vertexX,
                    vertexY,
                    if (a >= 0) SpecialPointKind.MAX else SpecialPointKind.MIN,
                )
            }
        } else {
            // x = a y^2 + b y + c 形状，开口左右
            points += SpecialPoint(
                -xB / (4 * yA) - if (xB != 0.0) c / xB else 0.0,
                -yB / (2 * yA),
                SpecialPointKind.FOCUS,
            )
            lines += ExtraLine.Vertical(xB / (4 * yA) - if (xB != 0.0) c / xB else 0.0)
        }
        return GraphExtra(ConicType.PARABOLA, points, lines)
    }

    companion object {

        /** 从展开后的表达式里取出 5 个系数；不是二次式就返回 null。 */
        fun of(engine: SymjaEngine, expanded: IExpr): ConicCoefficients? {
            val text = expanded.toString()
            val xA = coefficient(engine, text, GraphPlot.VARIABLE, 2)
            val yA = coefficient(engine, text, GraphPlot.PARAM, 2)
            // 交叉项：参考实现里没有处理，带交叉项一律按「画不了」处理
            val xCross = coefficient(engine, text, GraphPlot.VARIABLE, 1, GraphPlot.PARAM, 1)
            if (xCross != 0.0) return null
            val xB = coefficient(engine, text, GraphPlot.VARIABLE, 1)
            val yB = coefficient(engine, text, GraphPlot.PARAM, 1)
            val constant = numericTerms(expanded)

            val xConstant = if (xB == 0.0 || xA == 0.0) 0.0 else -(xB * xB) / (4 * xA)
            val yConstant = if (yB == 0.0 || yA == 0.0) 0.0 else -(yB * yB) / (4 * yA)
            return ConicCoefficients(xA, xB, yA, yB, xConstant + yConstant + constant)
        }

        private fun coefficient(
            engine: SymjaEngine,
            text: String,
            name: String,
            degree: Int,
            otherName: String? = null,
            otherDegree: Int = 0,
        ): Double {
            var code = "Coefficient($text, $name, $degree)"
            if (otherName != null) {
                code = "Coefficient(Coefficient($text, $name, $degree), $otherName, $otherDegree)"
            }
            return engine.numericValueOf(code) ?: 0.0
        }

        /** 展开式里所有「纯数字项」的和——也就是参考实现里的 expandNumber。 */
        private fun numericTerms(expanded: IExpr): Double {
            if (expanded.isNumber) return expanded.evalDouble()
            if (!expanded.isAST()) return 0.0
            val ast = expanded as IAST
            var sum = 0.0
            for (i in 1 until ast.size) {
                val arg = ast.get(i)
                if (arg.isNumber) sum += arg.evalDouble()
            }
            return sum
        }
    }
}
