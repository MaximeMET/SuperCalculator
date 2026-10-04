package io.github.maximemet.supercalc.engine

import org.matheclipse.core.computeprocess.StepJournal
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 离线解题步骤。
 *
 * 参考 App 的「解决过程」是服务器算好 JSON 再塞进结果页的，服务早就下线了。
 * 但它用的那套算法本来就在内核里：`Solve / Roots / QuarticSolver` 会在
 * 「求次数、因式分解、求根公式、配方」这些位置发 trace 事件，参考实现把它整理成
 * 步骤。这里不再用参考实现那套「按 trace 尾部下标取帧」的写法（实测换一个方程
 * 就会越界），而是把发点顺序记进 [StepJournal]，把它翻译成中文步骤。
 *
 * 步骤标签沿用原版前端 `result.min.js` 里的那份表：
 * 移项，合并同类项 / 因式分解 / 求根公式 / 配方法 / 消元 / 计算结果。
 */
object SolveSteps {

    // 与 StepJournal 里的步骤码一一对应
    private const val DEGREE = 1
    private const val FACTORIZATION = 2
    private const val COEFFICIENTS = 3
    private const val QUADRATIC_COEFFICIENTS = 4
    private const val COMPLETE_SQUARE = 5
    private const val EXPAND_FACTORS = 6

    private class Entry(val code: Int, val input: IExpr, val result: IExpr)

    /**
     * 生成解题步骤。
     *
     * [formula] 是当前行，[lastFormula] 是换行之前的那些行（方程组用）。
     * 拿不到步骤（不是多项式方程、引擎没走那条路）时返回空表。
     */
    fun build(
        engine: SymjaEngine,
        formula: String,
        lastFormula: String,
        method: Method,
    ): List<ProcessStep> = try {
        when (method) {
            Method.Solve -> buildForEquation(engine, formula)
            Method.Solve2 -> buildForSystem(engine, formula, lastFormula)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    /** 步骤转 JSON（结果页的 WebView 桥用）。没有步骤时返回 null。 */
    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        lastFormula: String,
        method: Method,
    ): String? = ProcessSteps.toJson(build(engine, formula, lastFormula, method))

    // ---------- 单变量方程 ----------

    private fun buildForEquation(engine: SymjaEngine, formula: String): List<ProcessStep> {
        val parsedEquation = engine.parseOrNull(formula) ?: return emptyList()
        val symja = MethodConsts.SYMJA_SOLVE.format(formula, EngineSettings.unknown)
        val parsed = engine.parseOrNull(symja) ?: return emptyList()
        val entries = collectJournal(engine, parsed)
        if (entries.isEmpty()) return emptyList()

        val steps = mutableListOf<ProcessStep>()

        // 1. 移项，合并同类项：把方程整理成「左边 = 0」
        val normalized = normalizedEquationLatex(engine, parsedEquation) ?: return emptyList()
        steps += ProcessStep("groupSameItem", "移项，合并同类项", listOf(normalized))

        val degree = entries.firstOrNull { it.code == DEGREE }
            ?.result?.let { intValueOf(it) } ?: 0
        val quadratic = entries.firstOrNull { it.code == QUADRATIC_COEFFICIENTS }
            ?.result as? IAST
        val coefficients = entries.firstOrNull { it.code == COEFFICIENTS }
            ?.result as? IAST

        // 2. 一元一次：系数化 1；一元二次：判别式
        if (degree == 2 && quadratic != null && quadratic.size == 4) {
            discriminantLines(engine, quadratic)?.let {
                steps += ProcessStep("discriminant", "判别式", it)
            }
        } else if (degree == 1) {
            linearSolveLines(engine, parsedEquation)?.let {
                steps += ProcessStep("linearSolve", "系数化 1", it)
            }
        }

        // 3. 因式分解：二次看 "Polynomial's factor!"，三次以上看展开后的因式表
        val factors = (entries.firstOrNull { it.code == FACTORIZATION }
            ?: entries.firstOrNull { it.code == EXPAND_FACTORS })?.result as? IAST
        val factorLines = factorizationLines(engine, factors)
        if (factorLines != null) {
            steps += ProcessStep("factors", "因式分解", factorLines)
        }

        // 4. 求根公式：二次给 a/b/c 代入式，三次以上给各项系数
        if (quadratic != null && quadratic.size == 4) {
            steps += ProcessStep("rootsFormula", "求根公式", rootsFormulaLines(engine, quadratic))
        } else if (coefficients != null && coefficients.size >= 3) {
            steps += ProcessStep("rootsFormula", "求根公式", coefficientLines(engine, coefficients))
        }

        // 5. 配方法
        val squareEntry = entries.firstOrNull { it.code == COMPLETE_SQUARE }?.result
        val squareLines = completeSquareLines(engine, squareEntry, quadratic)
        if (squareLines != null) {
            steps += ProcessStep("compeleteSquare", "配方法", squareLines)
        }

        // 6. 计算结果：重新求一遍解，按规则渲染（不再走参考实现的服务器通道）
        val solved = engine.evaluateOrNull(parsed)
        val resultLines = resultLines(engine, solved)
        if (resultLines.isNotEmpty()) {
            steps += if (degree >= 2) {
                ProcessStep("equalityResult", "方程结果", resultLines)
            } else {
                ProcessStep("result", "计算结果", resultLines)
            }
        }
        return steps
    }

    /** 把方程整理成「左边 = 0」。 */
    private fun normalizedEquationLatex(engine: SymjaEngine, equation: IExpr): String? {
        val (lhs, rhs) = sidesOf(equation) ?: return null
        val moved = F.eval(F.Subtract(lhs, rhs))
        val left = engine.toExactLatex(moved) ?: return null
        return "$left = 0"
    }

    private fun sidesOf(equation: IExpr): Pair<IExpr, IExpr>? = when {
        equation is IAST && equation.isAST(F.Equal) && equation.size == 3 ->
            equation.arg1() to equation.arg2()

        equation is IAST && equation.isAST(F.List) -> null

        else -> equation to F.C0
    }

    /** 因式分解步骤。只有一个因式且它就是原式时返回 null（没分解出东西）。 */
    private fun factorizationLines(engine: SymjaEngine, factors: IAST?): List<String>? {
        if (factors == null || factors.size <= 2) return null
        val items = (1 until factors.size).map { factors.get(it) }
        val product = F.Times(*items.toTypedArray())
        val latex = engine.toExactLatex(product) ?: return null
        val branches = mutableListOf<String>()
        for (item in items) {
            val itemTex = engine.toExactLatex(item) ?: return null
            branches.add("$itemTex = 0")
        }
        return listOf("$latex = 0", branches.joinToString(",\\quad "))
    }

    /**
     * 一元一次：移项后两边同除以未知数的系数。
     *
     * `2x-4=0` -> `2x = 4` -> `x = 4/2 = 2`。
     * 系数里有参数（`a*x+1==0`）时返回 null，不硬拆。
     */
    private fun linearSolveLines(engine: SymjaEngine, equation: IExpr): List<String>? {
        val (lhs, rhs) = sidesOf(equation) ?: return null
        val x = engine.symbol(EngineSettings.unknown)
        val moved = F.eval(F.Subtract(lhs, rhs))
        val a = engine.evaluateOrNull(
            engine.parseOrNull("Coefficient(($moved), ${EngineSettings.unknown})")
        ) ?: return null
        if (!a.isNumber || a.isZero) return null
        val b = F.eval(F.Subtract(moved, F.Times(a, x)))
        if (!b.isNumber) return null
        val negB = F.eval(F.Negate(b))
        val aTex = engine.toExactLatex(a) ?: return null
        val negBTex = engine.toExactLatex(negB) ?: return null
        val xTex = engine.toExactLatex(F.eval(F.Divide(negB, a))) ?: return null
        return if (a.isOne) {
            listOf("x = $negBTex")
        } else {
            listOf(
                "$aTex x = $negBTex",
                "x = \\frac{$negBTex}{$aTex} = $xTex",
            )
        }
    }

    /** 一元二次：判别式 Δ=b²-4ac 及其符号含义。 */
    private fun discriminantLines(engine: SymjaEngine, quadratic: IAST): List<String>? {
        val a = quadratic.get(1)
        val b = quadratic.get(2)
        val c = quadratic.get(3)
        val discriminant = F.eval(F.Subtract(F.Power(b, F.C2), F.Times(F.C4, a, c)))
        val aTex = engine.toExactLatex(a) ?: return null
        val bTex = engine.toExactLatex(b) ?: return null
        val cTex = engine.toExactLatex(c) ?: return null
        val discTex = engine.toExactLatex(discriminant) ?: return null
        val value = engine.numericValueOf(discriminant.toString()) ?: return null
        val tail = when {
            value > 1e-12 -> "T:Δ > 0，方程有两个不相等的实数根"
            value < -1e-12 -> "T:Δ < 0，方程没有实数根"
            else -> "T:Δ = 0，方程有两个相等的实数根"
        }
        return listOf(
            "\\Delta = b^{2}-4ac = ($bTex)^{2}-4\\times($aTex)\\times($cTex) = $discTex",
            tail,
        )
    }

    /** 二次方程：把 a、b、c 代进求根公式。 */
    private fun rootsFormulaLines(engine: SymjaEngine, quadratic: IAST): List<String> {
        val a = quadratic.get(1)
        val b = quadratic.get(2)
        val c = quadratic.get(3)
        val discriminant = F.eval(F.Subtract(F.Power(b, F.C2), F.Times(F.C4, a, c)))
        val aTex = engine.toExactLatex(a) ?: "a"
        val bTex = engine.toExactLatex(b) ?: "b"
        val cTex = engine.toExactLatex(c) ?: "c"
        val minusB = F.eval(F.Negate(b))
        val minusBTex = engine.toExactLatex(minusB) ?: "-$bTex"
        val discTex = engine.toExactLatex(discriminant) ?: "b^{2}-4ac"
        val denominatorTex = engine.toExactLatex(F.eval(F.Times(F.C2, a))) ?: "2a"
        return listOf(
            "x=\\frac{-b\\pm\\sqrt{b^{2}-4ac}}{2a}",
            "x=\\frac{$minusBTex\\pm\\sqrt{$discTex}}{$denominatorTex}",
            "a=$aTex,\\quad b=$bTex,\\quad c=$cTex",
        )
    }

    /** 三次 / 四次方程：列出各项系数。 */
    private fun coefficientLines(engine: SymjaEngine, coefficients: IAST): List<String> {
        val names = listOf("a_{0}", "a_{1}", "a_{2}", "a_{3}", "a_{4}")
        // 记账本里的系数表固定是 5 个（到四次），三次方程最后那个 0 不该显示出来
        var last = coefficients.size - 1
        while (last > 3 && coefficients.get(last).isZero) {
            last--
        }
        val parts = mutableListOf<String>()
        for (i in 1..last) {
            val value = engine.toExactLatex(coefficients.get(i)) ?: continue
            parts.add("${names.getOrElse(i - 1) { "a_{$i}" }}=$value")
        }
        return listOf(
            "T:根据求根公式，各项系数分别为：",
            parts.joinToString(",\\quad "),
        )
    }

    /**
     * 配方法。优选用 a、b、c 重新拼一遍标准形式
     * （参考实现发出来的式子会带一个多余的负号，比如 `(-x+5/2)^2`）。
     */
    private fun completeSquareLines(
        engine: SymjaEngine,
        entry: IExpr?,
        quadratic: IAST?,
    ): List<String>? {
        if (quadratic != null && quadratic.size == 4) {
            val a = quadratic.get(1)
            val b = quadratic.get(2)
            val c = quadratic.get(3)
            val x = engine.symbol(EngineSettings.unknown)
            val half = F.eval(F.Divide(b, F.Times(F.C2, a)))
            // 显示成 `(x - 5/2)^2` 而不是参考实现发出来的 `(-x + 5/2)^2`：
            // 两者等价，但后者看着像写错了。
            val shift = F.eval(F.Negate(half))
            val lhs = F.eval(F.Power(F.eval(F.Subtract(x, shift)), F.C2))
            val rhs = F.eval(
                F.Subtract(
                    F.Divide(F.Power(b, F.C2), F.Times(F.C4, F.Power(a, F.C2))),
                    F.Divide(c, a),
                )
            )
            val lhsTex = engine.toExactLatex(lhs)
            val rhsTex = engine.toExactLatex(rhs)
            if (lhsTex != null && rhsTex != null) {
                return listOf("$lhsTex = $rhsTex", "T:两边开平方即可得到方程的解")
            }
        }
        val latex = entry?.let { engine.toExactLatex(it) } ?: return null
        return listOf(latex)
    }

    // ---------- 方程组 ----------

    private fun buildForSystem(engine: SymjaEngine, formula: String, lastFormula: String): List<ProcessStep> {
        val all = Method.allFormula(lastFormula, formula)
        val unknowns = Method.unknowns(all)
        val symja = MethodConsts.SYMJA_SOLVE2.format(all, unknowns)
        val parsed = engine.parseOrNull(symja) ?: return emptyList()
        val entries = collectJournal(engine, parsed)
        if (entries.isEmpty()) return emptyList()

        val steps = mutableListOf<ProcessStep>()

        // 1. 移项，合并同类项：每个方程整理成「左边 = 0」
        val equations = (parsed as? IAST)?.get(1) as? IAST
        val normalizedLines = mutableListOf<String>()
        if (equations != null) {
            for (i in 1 until equations.size) {
                normalizedEquationLatex(engine, equations.get(i))?.let { normalizedLines.add(it) }
            }
        }
        if (normalizedLines.isNotEmpty()) {
            steps += ProcessStep("groupSameItem", "移项，合并同类项", normalizedLines)
        }

        // 2. 消元：二元一次给「加减消元 + 变量替换」；其它系统退回记账本里的消元结果
        val names = orderedUnknowns(unknowns)
        val eliminated = entries.filter { it.code == DEGREE }.map { it.input }
        val ordered = sortByUnknownOrder(engine, names, eliminated)
        val linearSteps = equations?.let { twoByTwoLinearSteps(engine, it, names) }
        if (linearSteps != null) {
            steps += linearSteps
        } else {
            if (ordered.isNotEmpty()) {
                val lines = ordered.mapNotNull { expr ->
                    engine.toExactLatex(expr)?.let { "$it = 0" }
                }
                if (lines.isNotEmpty()) {
                    steps += ProcessStep("gaussianElimination", "消元", lines)
                }
            }
            subEquationStep(engine, names, ordered)?.let { steps += it }
        }

        // 3. 计算结果
        val resultLines = resultLines(engine, engine.evaluateOrNull(parsed))
        if (resultLines.isNotEmpty()) {
            steps += ProcessStep("equalityResult", "方程结果", resultLines)
        }
        return steps
    }

    // ---------- 方程组的加减消元 / 代入 ----------

    /** 一元线性式 `a·u + b·v + c = 0` 的系数。 */
    private class LinearForm(val a: IExpr, val b: IExpr, val c: IExpr)

    /** 一条「消去某个未知数」的组合方案。 */
    private class EliminationPlan(
        val score: Int,
        val eliminated: String,
        val keep: String,
        val keepValue: IExpr,
        val combinationTex: String,
        val notation: String,
    )

    /**
     * 二元一次方程组的「加减消元 + 回代」。
     *
     * 原版的「变量替换 / 求解子方程」两步是服务端 trace 整理出来的，我们这条
     * trace 链上没有那几个发点，所以这里按线性方程组的结构**确定性生成**：
     * 先选一个消元方向凑一条尽量干净的组合（优先 ①±②），再把求出的未知数
     * 回代进其中一式；两个值代回原方程组都成立才展示，否则返回 null 走通用步骤。
     */
    private fun twoByTwoLinearSteps(
        engine: SymjaEngine,
        equations: IAST,
        names: List<String>,
    ): List<ProcessStep>? {
        if (equations.size != 3 || names.size != 2) return null
        val u = names[0]
        val v = names[1]
        val first = linearForm(engine, equations.get(1), u, v) ?: return null
        val second = linearForm(engine, equations.get(2), u, v) ?: return null

        val plans = listOf(
            eliminationPlan(engine, first, second, u, v, eliminateFirst = true),
            eliminationPlan(engine, first, second, u, v, eliminateFirst = false),
        ).filterNotNull()
        if (plans.isEmpty()) return null
        val plan = plans.minBy { it.score }

        // 回代：挑系数不为零、确实含被消去未知数的那一式
        val eliminatedCoefInFirst = if (plan.eliminated == u) first.a else first.b
        val useFirst = !eliminatedCoefInFirst.isZero
        val intoEquation = if (useFirst) equations.get(1) else equations.get(2)

        val keepValueTex = engine.toExactLatex(plan.keepValue) ?: return null
        val (intoLhs, intoRhs) = sidesOf(intoEquation) ?: return null
        // 注意：`IExpr.replaceAll` 在「没命中符号」时会返回 NIL 指针（例如常数
        // 那一侧的 3），所以代入统一走引擎的 ReplaceAll 通道。
        val subLhs = substitute(engine, intoLhs, plan.keep, plan.keepValue) ?: return null
        val subRhs = substitute(engine, intoRhs, plan.keep, plan.keepValue) ?: return null
        val subMoved = F.eval(F.Subtract(subLhs, subRhs))
        val elimSym = engine.symbol(plan.eliminated)
        val coefficient = engine.evaluateOrNull(
            engine.parseOrNull("Coefficient(($subMoved), ${plan.eliminated})")
        ) ?: return null
        if (!coefficient.isNumber || coefficient.isZero) return null
        val constant = F.eval(F.Subtract(subMoved, F.Times(coefficient, elimSym)))
        if (!constant.isNumber) return null
        val elimValue = F.eval(F.Divide(F.Negate(constant), coefficient))
        if (!elimValue.isNumber) return null

        // 回代验证：两个值代进原方程组每一式都必须成立
        val uValue = if (plan.eliminated == u) elimValue else plan.keepValue
        val vValue = if (plan.eliminated == v) elimValue else plan.keepValue
        for (i in 1 until equations.size) {
            if (!isSolution(engine, equations.get(i), u, uValue, v, vValue)) return null
        }

        val subLhsTex = engine.toExactLatex(subLhs) ?: return null
        val subRhsTex = engine.toExactLatex(subRhs) ?: return null
        val elimValueTex = engine.toExactLatex(elimValue) ?: return null
        return listOf(
            ProcessStep(
                "gaussianElimination",
                "加减消元",
                listOf(
                    "T:${plan.notation}，消去 ${plan.eliminated}",
                    plan.combinationTex,
                    "${plan.keep} = $keepValueTex",
                ),
            ),
            ProcessStep(
                "replaceVariable",
                "变量替换",
                listOf(
                    "T:把 ${plan.keep} = $keepValueTex 代入${if (useFirst) "①" else "②"}",
                    "$subLhsTex = $subRhsTex",
                    "${plan.eliminated} = $elimValueTex",
                ),
            ),
        )
    }

    /** 把方程整理成 `a·u + b·v + c = 0`；非线性 / 带参数返回 null。 */
    private fun linearForm(
        engine: SymjaEngine,
        equation: IExpr,
        u: String,
        v: String,
    ): LinearForm? {
        val (lhs, rhs) = sidesOf(equation) ?: return null
        val moved = F.eval(F.Subtract(lhs, rhs))
        val uSym = engine.symbol(u)
        val vSym = engine.symbol(v)
        val a = engine.evaluateOrNull(engine.parseOrNull("Coefficient(($moved), $u)")) ?: return null
        val b = engine.evaluateOrNull(engine.parseOrNull("Coefficient(($moved), $v)")) ?: return null
        if (!a.isNumber || !b.isNumber || (a.isZero && b.isZero)) return null
        val c = F.eval(
            F.Subtract(F.Subtract(moved, F.Times(a, uSym)), F.Times(b, vSym))
        )
        if (!c.isFree(uSym) || !c.isFree(vSym)) return null
        return LinearForm(a, b, c)
    }

    /** 消去 u（或 v）的一条组合：①×m1 - ②×m2。 */
    private fun eliminationPlan(
        engine: SymjaEngine,
        first: LinearForm,
        second: LinearForm,
        u: String,
        v: String,
        eliminateFirst: Boolean,
    ): EliminationPlan? {
        val coef1 = if (eliminateFirst) first.a else first.b
        val coef2 = if (eliminateFirst) second.a else second.b
        if (!coef1.isNumber || !coef2.isNumber || coef1.isZero || coef2.isZero) return null
        val keepCoef1 = if (eliminateFirst) first.b else first.a
        val keepCoef2 = if (eliminateFirst) second.b else second.a
        if (!keepCoef1.isNumber || !keepCoef2.isNumber) return null

        val (m1, m2) = reduceMultipliers(coef2, coef1)
        val kept = F.eval(F.Subtract(F.Times(m1, keepCoef1), F.Times(m2, keepCoef2)))
        if (!kept.isNumber || kept.isZero) return null
        val constant = F.eval(F.Subtract(F.Times(m1, first.c), F.Times(m2, second.c)))
        if (!constant.isNumber) return null
        val keepValue = F.eval(F.Divide(F.Negate(constant), kept))
        if (!keepValue.isNumber) return null

        val keepUnknown = if (eliminateFirst) v else u
        val keepSym = engine.symbol(keepUnknown)
        val leftTex = engine.toExactLatex(F.Times(kept, keepSym)) ?: return null
        val rightTex = engine.toExactLatex(F.eval(F.Negate(constant))) ?: return null
        val clean = (m1.isOne || m1.isMinusOne) && (m2.isOne || m2.isMinusOne)
        return EliminationPlan(
            score = if (clean) 0 else 1,
            eliminated = if (eliminateFirst) u else v,
            keep = keepUnknown,
            keepValue = keepValue,
            combinationTex = "$leftTex = $rightTex",
            notation = combinationText(m1, m2),
        )
    }

    /** `①-②` / `①×4 - ②×2` 这类文字记法（纯文本行，不走 LaTeX）。 */
    private fun combinationText(m1: IExpr, m2: IExpr): String {
        val first = when {
            m1.isOne -> "①"
            m1.isMinusOne -> "-①"
            m1.isNegative -> "-①×${plainNumber(F.eval(F.Abs(m1)))}"
            else -> "①×${plainNumber(m1)}"
        }
        val absSecond = F.eval(F.Abs(m2))
        val second = if (absSecond.isOne) "②" else "②×${plainNumber(absSecond)}"
        return first + (if (m2.isNegative) " + " else " - ") + second
    }

    private fun plainNumber(expr: IExpr): String = expr.toString().trim()

    /** 两个整数乘数约掉公因子，组合更干净（4、2 -> 2、1）。 */
    private fun reduceMultipliers(m1: IExpr, m2: IExpr): Pair<IExpr, IExpr> {
        val a = m1.toString().toLongOrNull() ?: return m1 to m2
        val b = m2.toString().toLongOrNull() ?: return m1 to m2
        val g = gcdLong(kotlin.math.abs(a), kotlin.math.abs(b))
        if (g <= 1L) return m1 to m2
        return F.integer(a / g) to F.integer(b / g)
    }

    private fun gcdLong(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0L) 1L else x
    }

    /** 把值代回原方程，验证它确实是解。 */
    private fun isSolution(
        engine: SymjaEngine,
        equation: IExpr,
        u: String,
        uValue: IExpr,
        v: String,
        vValue: IExpr,
    ): Boolean {
        val (lhs, rhs) = sidesOf(equation) ?: return false
        val code = "(($lhs) - ($rhs)) /. $u -> ($uValue) /. $v -> ($vValue)"
        val value = engine.evaluateOrNull(engine.parseOrNull(code)) ?: return false
        if (value.isZero) return true
        val numeric = engine.numericValueOf(value.toString()) ?: return false
        return kotlin.math.abs(numeric) < 1e-9
    }

    /** 把 `name = value` 代入表达式；不命中（常数侧）时原样返回。 */
    private fun substitute(
        engine: SymjaEngine,
        expr: IExpr,
        name: String,
        value: IExpr,
    ): IExpr? = engine.evaluateOrNull(engine.parseOrNull("($expr) /. $name -> ($value)"))

    /**
     * 消元后剩下的单变量子方程（记账本里的 DEGREE 输入），逐个解出来。
     *
     * 三次元的线性方程组走这条：消元结果本来就是 `3x-5 = 0` 这种一次式。
     */
    private fun subEquationStep(
        engine: SymjaEngine,
        names: List<String>,
        equations: List<IExpr>,
    ): ProcessStep? {
        if (equations.isEmpty()) return null
        val lines = mutableListOf<String>()
        for (expr in equations) {
            val unknown = names.firstOrNull { !expr.isFree(engine.symbol(it)) } ?: continue
            val moved = F.eval(expr)
            val sym = engine.symbol(unknown)
            val a = engine.evaluateOrNull(engine.parseOrNull("Coefficient(($moved), $unknown)"))
                ?: continue
            if (!a.isNumber || a.isZero) continue
            val b = F.eval(F.Subtract(moved, F.Times(a, sym)))
            if (!b.isNumber) continue
            val value = F.eval(F.Divide(F.Negate(b), a))
            val check = engine.evaluateOrNull(moved.replaceAll(F.Rule(sym, value))) ?: continue
            if (!check.isZero) continue
            val movedTex = engine.toExactLatex(moved) ?: continue
            val valueTex = engine.toExactLatex(value) ?: continue
            lines += "$movedTex = 0"
            lines += "$unknown = $valueTex"
        }
        if (lines.isEmpty()) return null
        return ProcessStep("subEquation", "求解子方程", lines)
    }

    private fun orderedUnknowns(unknowns: String): List<String> =
        unknowns.removePrefix("{").removeSuffix("}")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun sortByUnknownOrder(
        engine: SymjaEngine,
        order: List<String>,
        exprs: List<IExpr>,
    ): List<IExpr> = exprs.sortedBy { expr ->
        val hit = order.indexOfFirst { name -> !expr.isFree(engine.symbol(name)) }
        if (hit < 0) order.size else hit
    }

    // ---------- 公共 ----------

    /** 走一遍带记账的 trace，拿回 `[步骤码, 输入, 结果]` 序列。 */
    private fun collectJournal(engine: SymjaEngine, parsed: IExpr): List<Entry> {
        StepJournal.begin()
        val journal = try {
            engine.evalEngine.evalTrace(parsed, null, F.List())
            StepJournal.end()
        } catch (e: Exception) {
            StepJournal.cancel()
            return emptyList()
        } catch (e: StackOverflowError) {
            StepJournal.cancel()
            return emptyList()
        }
        val entries = mutableListOf<Entry>()
        for (i in 1 until journal.size) {
            val item = journal.get(i) as? IAST ?: continue
            if (item.size != 4) continue
            val code = intValueOf(item.get(1)) ?: continue
            entries.add(Entry(code, item.get(2), item.get(3)))
        }
        return entries
    }

    private fun intValueOf(expr: IExpr): Int? =
        expr.toString().trim().toIntOrNull()

    /** `{{x->2},{x->3}}` -> `x_{1}=2`；`{{x->2,y->1}}` -> `x=2,\quad y=1`。 */
    private fun resultLines(engine: SymjaEngine, solved: IExpr?): List<String> {
        if (solved !is IAST) return emptyList()
        val groups = mutableListOf<List<Pair<IExpr, IExpr>>>()
        for (i in 1 until solved.size) {
            val group = solved.get(i) as? IAST ?: continue
            val rules = mutableListOf<Pair<IExpr, IExpr>>()
            for (j in 1 until group.size) {
                val rule = group.get(j) as? IAST ?: continue
                if (!rule.isAST(F.Rule) || rule.size != 3) continue
                rules.add(rule.arg1() to rule.arg2())
            }
            if (rules.isNotEmpty()) groups.add(rules)
        }
        if (groups.isEmpty()) return emptyList()
        if (groups.size == 1 || groups.any { it.size > 1 }) {
            return groups.map { rules ->
                rules.joinToString(",\\quad ") { (sym, value) ->
                    "${engine.toExactLatex(sym)} = ${engine.toExactLatex(value)}"
                }
            }
        }
        return groups.mapIndexed { index, rules ->
            val (sym, value) = rules.first()
            "${engine.toExactLatex(sym)}_{${index + 1}} = ${engine.toExactLatex(value)}"
        }
    }

}
