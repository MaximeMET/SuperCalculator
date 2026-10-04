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

    /** 一条步骤。[key] 与原版标签表对应，[lines] 是逐行排版的 LaTeX。 */
    data class Step(val key: String, val label: String, val lines: List<String>)

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
    ): List<Step> = try {
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
    ): String? {
        val steps = build(engine, formula, lastFormula, method)
        if (steps.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("{\"steps\":[")
        steps.forEachIndexed { index, step ->
            if (index > 0) sb.append(',')
            sb.append("{\"order\":\"").append(index + 1).append("\",")
            sb.append("\"key\":").append(quote(step.key)).append(',')
            sb.append("\"label\":").append(quote(step.label)).append(',')
            sb.append("\"lines\":[")
            step.lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) sb.append(',')
                sb.append(quote(line))
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    // ---------- 单变量方程 ----------

    private fun buildForEquation(engine: SymjaEngine, formula: String): List<Step> {
        val parsedEquation = engine.parseOrNull(formula) ?: return emptyList()
        val symja = MethodConsts.SYMJA_SOLVE.format(formula, EngineSettings.unknown)
        val parsed = engine.parseOrNull(symja) ?: return emptyList()
        val entries = collectJournal(engine, parsed)
        if (entries.isEmpty()) return emptyList()

        val steps = mutableListOf<Step>()

        // 1. 移项，合并同类项：把方程整理成「左边 = 0」
        val normalized = normalizedEquationLatex(engine, parsedEquation) ?: return emptyList()
        steps += Step("groupSameItem", "移项，合并同类项", listOf(normalized))

        val degree = entries.firstOrNull { it.code == DEGREE }
            ?.result?.let { intValueOf(it) } ?: 0

        // 2. 因式分解：二次看 "Polynomial's factor!"，三次以上看展开后的因式表
        val factors = (entries.firstOrNull { it.code == FACTORIZATION }
            ?: entries.firstOrNull { it.code == EXPAND_FACTORS })?.result as? IAST
        val factorLines = factorizationLines(engine, factors)
        if (factorLines != null) {
            steps += Step("factors", "因式分解", factorLines)
        }

        // 3. 求根公式：二次给 a/b/c 代入式，三次以上给各项系数
        val quadratic = entries.firstOrNull { it.code == QUADRATIC_COEFFICIENTS }
            ?.result as? IAST
        val coefficients = entries.firstOrNull { it.code == COEFFICIENTS }
            ?.result as? IAST
        if (quadratic != null && quadratic.size == 4) {
            steps += Step("rootsFormula", "求根公式", rootsFormulaLines(engine, quadratic))
        } else if (coefficients != null && coefficients.size >= 3) {
            steps += Step("rootsFormula", "求根公式", coefficientLines(engine, coefficients))
        }

        // 4. 配方法
        val squareEntry = entries.firstOrNull { it.code == COMPLETE_SQUARE }?.result
        val squareLines = completeSquareLines(engine, squareEntry, quadratic)
        if (squareLines != null) {
            steps += Step("compeleteSquare", "配方法", squareLines)
        }

        // 5. 计算结果：重新求一遍解，按规则渲染（不再走参考实现的服务器通道）
        val solved = engine.evaluateOrNull(parsed)
        val resultLines = resultLines(engine, solved)
        if (resultLines.isNotEmpty()) {
            steps += if (degree >= 2) {
                Step("equalityResult", "方程结果", resultLines)
            } else {
                Step("result", "计算结果", resultLines)
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

    private fun buildForSystem(engine: SymjaEngine, formula: String, lastFormula: String): List<Step> {
        val all = Method.allFormula(lastFormula, formula)
        val unknowns = Method.unknowns(all)
        val symja = MethodConsts.SYMJA_SOLVE2.format(all, unknowns)
        val parsed = engine.parseOrNull(symja) ?: return emptyList()
        val entries = collectJournal(engine, parsed)
        if (entries.isEmpty()) return emptyList()

        val steps = mutableListOf<Step>()

        // 1. 移项，合并同类项：每个方程整理成「左边 = 0」
        val equations = (parsed as? IAST)?.get(1) as? IAST
        val normalizedLines = mutableListOf<String>()
        if (equations != null) {
            for (i in 1 until equations.size) {
                normalizedEquationLatex(engine, equations.get(i))?.let { normalizedLines.add(it) }
            }
        }
        if (normalizedLines.isNotEmpty()) {
            steps += Step("groupSameItem", "移项，合并同类项", normalizedLines)
        }

        // 2. 消元：记账本里每个子方程的输入表达式就是消元后剩下的项
        val eliminated = entries.filter { it.code == DEGREE }.map { it.input }
        val ordered = sortByUnknownOrder(engine, orderedUnknowns(unknowns), eliminated)
        if (ordered.isNotEmpty()) {
            val lines = ordered.mapNotNull { expr ->
                engine.toExactLatex(expr)?.let { "$it = 0" }
            }
            if (lines.isNotEmpty()) {
                steps += Step("gaussianElimination", "消元", lines)
            }
        }

        // 3. 计算结果
        val resultLines = resultLines(engine, engine.evaluateOrNull(parsed))
        if (resultLines.isNotEmpty()) {
            steps += Step("equalityResult", "方程结果", resultLines)
        }
        return steps
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

    /** JSON 字符串转义（引擎模块不依赖 Android 的 org.json）。 */
    private fun quote(text: String): String {
        val sb = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.append('"').toString()
    }
}
