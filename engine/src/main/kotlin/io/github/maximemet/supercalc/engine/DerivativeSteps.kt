package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 求导的「解决过程」：逐层拆解。
 *
 * 原版的过程引擎只管方程 / 不等式（服务端 `computeprocess` 那套），求导没有过程；
 * 这一份按教材口径自己补上：和差法则、常数因子、乘积法则、商法则、链式法则
 * 一层层展开，最后给 Symja 化简过的结果。
 *
 * 正确性锚点：每一层都是按规则拼出来的，整棵拼完后拿 `Diff(原式, x)` 的结果
 * 做数值对拍（多个采样点全一致才展示步骤），对不上就不出过程——宁可没有，
 * 不给错的。
 */
object DerivativeSteps {

    /** 允许的最大拆分深度 / 节点数：病态输入直接放弃，不硬拆。 */
    private const val MAX_DEPTH = 14
    private const val MAX_NODES = 80

    /** 对拍采样点：大多是正数，避开 ln / sqrt 的定义域问题；末尾一个负数兜符号错误。 */
    private val SAMPLE_POINTS = doubleArrayOf(0.6, 1.4, 2.3, 3.7, -0.7)

    /**
     * 生成步骤；引擎求不出来、或对拍不过时返回 null（结果页据此不显示过程区）。
     *
     * [originalLatex] 是编辑器里那份 LaTeX，用作「原式」一行。
     */
    fun build(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): List<ProcessStep>? = try {
        buildInner(engine, formula, originalLatex)
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /** 步骤转 JSON（结果页的 WebView 桥用）。没有步骤时返回 null。 */
    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): String? = ProcessSteps.toJson(build(engine, formula, originalLatex) ?: emptyList())

    private fun buildInner(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val parsed = engine.parseOrNull(formula) ?: return null
        val x = engine.symbol(EngineSettings.unknown)
        if (parsed.isFree(x)) return null

        // 引擎自己的求导结果：既是对拍基准，也是最后一行「计算结果」的来源
        // （跟结果页顶部显示的那条结果同源，不会出现两处不一致）。
        val evaluated = engine.evaluateOrNull(
            engine.parseOrNull("Diff($formula, ${EngineSettings.unknown})")
        ) ?: return null
        if (engine.isInvalid(evaluated)) return null
        val evaluatedText = evaluated.toString()
        // 引擎没求出来的（`Abs'(x)`、`Derivative(1)(foo)(x)`）不出过程
        if (evaluatedText.contains("Derivative(") || evaluatedText.contains("Diff(")) return null

        val walker = Walker(engine, x)
        val root = walker.walk(parsed, 0) ?: return null
        if (!walker.sameFunction(root.derivative, evaluated)) return null

        val steps = mutableListOf<ProcessStep>()
        val original = originalLatex?.takeIf { it.isNotBlank() }
            ?.let { LatexText.editorSafe(it) } ?: formula
        steps += ProcessStep("original", "原式", listOf("\\frac{d}{dx}\\left($original\\right)"))
        appendNode(root, steps)
        val resultTex = engine.toExactLatex(evaluated) ?: return null
        steps += ProcessStep("result", "计算结果", listOf("= $resultTex"))
        return steps
    }

    /** 先父后子：上一条的 `(u)'` 是下一条的主角，读起来是逐层展开。 */
    private fun appendNode(node: Node, out: MutableList<ProcessStep>) {
        if (node.lines.isNotEmpty()) out += ProcessStep(node.key, node.label, node.lines)
        node.children.forEach { appendNode(it, out) }
    }

    private class Node(
        val expr: IExpr,
        val key: String,
        val label: String,
        val lines: List<String>,
        val children: List<Node>,
        val derivative: IExpr,
        /** 父步骤引用该节点时用的 `(…)'` 写法；null 表示按表达式常规排版。 */
        val marker: String? = null,
    )

    /**
     * 递归拆分器。每个节点产出一条「规则 + 结果」，孩子节点在下一步展开。
     */
    private class Walker(private val engine: SymjaEngine, private val x: IExpr) {

        private var nodes = 0

        fun walk(expr: IExpr, depth: Int): Node? {
            if (depth > MAX_DEPTH) return null
            if (++nodes > MAX_NODES) return null

            if (expr.isFree(x)) return constantNode(expr)
            if (expr.equals(x)) return identityNode(expr)

            val ast = expr as? IAST ?: return null
            return when {
                ast.isAST(F.Plus) -> plusNode(ast, expr, depth)
                ast.isAST(F.Times) -> timesNode(ast, expr, depth)
                ast.isAST(F.Power) && ast.size == 3 -> powerNode(ast, expr, depth)
                ast.size == 2 -> functionNode(ast, expr, depth)
                else -> null
            }
        }

        /**
         * 与引擎结果数值对拍。
         *
         * 式子里可能还有 a、b、c 这类「与 x 无关的常数」，所以对拍时把**所有**
         * 自由符号都代成采样值（同一组代入两边都做），多换几组值再比，
         * 这样 `a*x^2+b*x+c` 这类带参数式子也能验。
         */
        fun sameFunction(a: IExpr, b: IExpr): Boolean {
            val symbols = linkedSetOf<IExpr>()
            collectSymbols(a, symbols)
            collectSymbols(b, symbols)
            if (symbols.isEmpty()) return true

            var checked = 0
            for (round in 0 until 3) {
                val substitutions = symbols.mapIndexed { index, symbol ->
                    "($symbol -> ${SAMPLE_POINTS[(index + round) % SAMPLE_POINTS.size]})"
                }
                val aCode = substitutions.fold(a.toString()) { code, rule -> "($code) /. $rule" }
                val bCode = substitutions.fold(b.toString()) { code, rule -> "($code) /. $rule" }
                val va = engine.numericValueOf(aCode) ?: continue
                val vb = engine.numericValueOf(bCode) ?: continue
                checked++
                val tolerance = 1e-6 * (1.0 + kotlin.math.abs(va) + kotlin.math.abs(vb))
                if (kotlin.math.abs(va - vb) > tolerance) return false
            }
            return checked >= 2
        }

        private fun collectSymbols(expr: IExpr, out: MutableSet<IExpr>) {
            if (expr.isSymbol) {
                out.add(expr)
                return
            }
            val ast = expr as? IAST ?: return
            for (i in 1 until ast.size) collectSymbols(ast.get(i), out)
        }

        // ---------- 叶子 ----------

        private fun constantNode(expr: IExpr): Node? {
            val lhs = primeOf(expr) ?: return null
            return Node(expr, "constantRule", "常数法则", listOf("$lhs=0"), emptyList(), F.C0)
        }

        private fun identityNode(expr: IExpr): Node? {
            val lhs = primeOf(expr) ?: return null
            return Node(expr, "basicDerivative", "基本求导公式", listOf("$lhs=1"), emptyList(), F.C1)
        }

        // ---------- 和差 ----------

        private fun plusNode(ast: IAST, expr: IExpr, depth: Int): Node? {
            val terms = flat(ast, F.Plus) ?: return null
            if (terms.size < 2) return null
            val children = mutableListOf<Node>()
            val derivativeParts = mutableListOf<IExpr>()
            val markerParts = mutableListOf<String>()
            terms.forEachIndexed { index, raw ->
                val (negative, magnitude) = splitSign(raw)
                val child = walk(magnitude, depth + 1) ?: return null
                children += child
                derivativeParts += if (negative) F.Times(F.CN1, child.derivative) else child.derivative
                val marker = markerOf(child) ?: return null
                markerParts += when {
                    index == 0 && negative -> "-$marker"
                    index == 0 -> marker
                    negative -> " - $marker"
                    else -> " + $marker"
                }
            }
            val lhs = primeOf(expr) ?: return null
            val derivative =
                if (derivativeParts.size == 1) {
                    derivativeParts.first()
                } else {
                    F.Plus(*derivativeParts.toTypedArray())
                }
            return Node(
                expr, "sumRule", "和差法则",
                listOf("$lhs=${markerParts.joinToString("")}"),
                children, derivative,
            )
        }

        // ---------- 乘除 ----------

        private fun timesNode(ast: IAST, expr: IExpr, depth: Int): Node? {
            val factors = flat(ast, F.Times) ?: return null
            if (factors.size == 1) return walk(factors.first(), depth)
            val consts = factors.filter { it.isFree(x) }
            val variable = factors.filter { !it.isFree(x) }
            if (variable.isEmpty()) return constantNode(expr)

            val inverse = variable.filter { isNegativePower(it) }
            val plain = variable.filterNot { isNegativePower(it) }

            // 含负幂（除法）：拆出分子 / 分母，能当常数因子就当常数因子，否则商法则
            if (inverse.isNotEmpty()) {
                val denominator = timesOf(inverse.map { reciprocalBase(it) })
                val numerator = timesOf(consts + plain)
                return when {
                    denominator.isFree(x) ->
                        constantFactorNode(expr, F.eval(F.Power(denominator, F.CN1)), numerator, depth)

                    numerator.isFree(x) ->
                        constantFactorNode(expr, numerator, timesOf(inverse), depth)

                    else -> quotientNode(expr, numerator, denominator, depth)
                }
            }

            if (consts.isNotEmpty()) {
                return constantFactorNode(expr, timesOf(consts), timesOf(plain), depth)
            }
            if (plain.size < 2) return walk(plain.first(), depth)

            val childNodes = plain.map { walk(it, depth + 1) ?: return null }
            val factorTex = plain.map { tex(it) ?: return null }
            val termTexts = mutableListOf<String>()
            val derivativeParts = mutableListOf<IExpr>()
            for (i in plain.indices) {
                val marker = markerOf(childNodes[i]) ?: return null
                val rest = factorTex.filterIndexed { j, _ -> j != i }
                termTexts += buildString {
                    append(marker)
                    rest.forEach { append("\\cdot ").append(parenIfSum(it)) }
                }
                derivativeParts.add(
                    F.Times(
                        *plain.mapIndexed { j, factor ->
                            if (j == i) childNodes[j].derivative else factor
                        }.toTypedArray()
                    )
                )
            }
            val lhs = primeOf(expr) ?: return null
            return Node(
                expr, "productRule", "乘积法则",
                listOf("$lhs=${termTexts.joinToString("+")}"),
                childNodes, F.Plus(*derivativeParts.toTypedArray()),
            )
        }

        private fun constantFactorNode(
            expr: IExpr,
            constant: IExpr,
            inner: IExpr,
            depth: Int,
        ): Node? {
            val child = walk(inner, depth + 1) ?: return null
            val lhs = primeOf(expr) ?: return null
            val constantTex = tex(constant) ?: return null
            val innerPrime = markerOf(child) ?: return null
            return Node(
                expr, "constantFactor", "常数因子法则",
                listOf("$lhs=$constantTex\\cdot $innerPrime"),
                listOf(child), F.Times(constant, child.derivative),
            )
        }

        private fun quotientNode(expr: IExpr, u: IExpr, v: IExpr, depth: Int): Node? {
            val uNode = walk(u, depth + 1) ?: return null
            val vNode = walk(v, depth + 1) ?: return null
            val uTex = tex(u) ?: return null
            val vTex = tex(v) ?: return null
            val uPrime = markerOf(uNode) ?: return null
            val vPrime = markerOf(vNode) ?: return null
            val lhs = primeOf(expr) ?: return null
            val vSquareTex = tex(F.Power(v, F.C2)) ?: return null
            val derivative = F.Divide(
                F.Subtract(F.Times(uNode.derivative, v), F.Times(u, vNode.derivative)),
                F.Power(v, F.C2),
            )
            return Node(
                expr, "quotientRule", "商法则",
                listOf(
                    "$lhs=\\frac{$uPrime\\cdot ${parenIfSum(vTex)}" +
                        "-${parenIfSum(uTex)}\\cdot $vPrime}{$vSquareTex}"
                ),
                listOf(uNode, vNode), derivative,
            )
        }

        // ---------- 幂 / 指数 ----------

        private fun powerNode(ast: IAST, expr: IExpr, depth: Int): Node? {
            val base = ast.arg1()
            val exponent = ast.arg2()
            if (exponent.isFree(x)) return fixedExponentNode(expr, base, exponent, depth)
            return if (base.isFree(x)) exponentialNode(expr, base, exponent, depth)
            else logarithmicDerivativeNode(expr, base, exponent, depth)
        }

        /** `u^n`（n 与 x 无关）：幂函数 / 倒数 / 根式。 */
        private fun fixedExponentNode(
            expr: IExpr,
            base: IExpr,
            exponent: IExpr,
            depth: Int,
        ): Node? {
            if (base.isFree(x)) return constantNode(expr)
            if (exponent.isZero) return constantNode(expr)
            if (exponent.isOne) return walk(base, depth)

            val baseIsX = base.equals(x)
            val child = if (baseIsX) null else walk(base, depth + 1) ?: return null
            val baseTex = tex(base) ?: return null
            val expTex = tex(exponent) ?: return null
            val lhs = primeOf(expr) ?: return null
            val chain = if (baseIsX) "" else "\\cdot ${markerOf(child!!) ?: return null}"
            val nMinusOne = F.eval(F.Subtract(exponent, F.C1))
            val derivative = if (baseIsX) {
                F.Times(exponent, F.Power(base, nMinusOne))
            } else {
                F.Times(exponent, F.Power(base, nMinusOne), child!!.derivative)
            }
            val children = if (child == null) emptyList() else listOf(child)

            // 倒数
            if (exponent.isMinusOne) {
                val reciprocalLhs = "\\left(\\frac{1}{$baseTex}\\right)'"
                val partial =
                    tex(F.eval(F.Times(F.CN1, F.Power(base, F.CN2)))) ?: return null
                val line = if (baseIsX) {
                    "$reciprocalLhs=$partial"
                } else {
                    "$reciprocalLhs=$partial$chain"
                }
                return Node(
                    expr, "reciprocalRule", "倒数法则", listOf(line), children, derivative,
                    marker = reciprocalLhs,
                )
            }

            // 根式（1/2 次幂）
            if (exponent.isFraction && exponent.equals(F.C1D2)) {
                val partial =
                    tex(F.eval(F.Times(F.C1D2, F.Power(base, F.CN1D2)))) ?: return null
                val line = if (baseIsX) {
                    "$lhs=\\frac{1}{2\\,\\sqrt{$baseTex}}"
                } else {
                    "$lhs=$partial$chain"
                }
                return Node(expr, "sqrtRule", "根式法则", listOf(line), children, derivative)
            }

            // 一般幂函数：整数次幂显示 n-1 那一步，其余直接给结果
            val partial =
                tex(F.eval(F.Times(exponent, F.Power(base, nMinusOne)))) ?: return null
            val showExponentStep = exponent.isInteger && exponent.isPositive
            val line = when {
                showExponentStep && baseIsX -> "$lhs=$expTex\\,$baseTex^{$expTex-1}=$partial"
                showExponentStep -> "$lhs=$expTex\\,$baseTex^{$expTex-1}$chain"
                else -> "$lhs=$partial$chain"
            }
            return Node(expr, "powerRule", "幂函数法则", listOf(line), children, derivative)
        }

        /** `a^u`（底数与 x 无关）：指数函数。 */
        private fun exponentialNode(
            expr: IExpr,
            base: IExpr,
            exponent: IExpr,
            depth: Int,
        ): Node? {
            val exponentIsX = exponent.equals(x)
            val child = if (exponentIsX) null else walk(exponent, depth + 1) ?: return null
            val lhs = primeOf(expr) ?: return null
            val powTex = tex(expr) ?: return null
            val chain = if (exponentIsX) "" else "\\cdot ${markerOf(child!!) ?: return null}"
            val children = if (child == null) emptyList() else listOf(child)

            if (base.isE) {
                val derivative = if (exponentIsX) {
                    expr
                } else {
                    F.Times(expr, child!!.derivative)
                }
                return Node(
                    expr, "exponentialRule", "指数函数法则",
                    listOf("$lhs=$powTex$chain"), children, derivative,
                )
            }

            val baseTex = tex(base) ?: return null
            val derivative = if (exponentIsX) {
                F.Times(expr, F.Log(base))
            } else {
                F.Times(expr, F.Log(base), child!!.derivative)
            }
            return Node(
                expr, "exponentialRule", "指数函数法则",
                listOf("$lhs=$powTex\\ln $baseTex$chain"), children, derivative,
            )
        }

        /** `u^v`（底数与指数都含 x）：对数求导法。 */
        private fun logarithmicDerivativeNode(
            expr: IExpr,
            base: IExpr,
            exponent: IExpr,
            depth: Int,
        ): Node? {
            val baseNode = walk(base, depth + 1) ?: return null
            val exponentNode = walk(exponent, depth + 1) ?: return null
            val baseTex = tex(base) ?: return null
            val expTex = tex(exponent) ?: return null
            val powTex = tex(expr) ?: return null
            val basePrime = markerOf(baseNode) ?: return null
            val expPrime = markerOf(exponentNode) ?: return null
            val lhs = primeOf(expr) ?: return null
            val derivative = F.Times(
                expr,
                F.Plus(
                    F.Times(exponentNode.derivative, F.Log(base)),
                    F.Divide(F.Times(exponent, baseNode.derivative), base),
                ),
            )
            return Node(
                expr, "logDerivativeRule", "对数求导法",
                listOf("$lhs=$powTex\\left($expPrime\\ln $baseTex+\\frac{$expTex\\cdot $basePrime}{$baseTex}\\right)"),
                if (baseNode.expr.equals(exponentNode.expr)) {
                    listOf(baseNode)
                } else {
                    listOf(baseNode, exponentNode)
                },
                derivative,
            )
        }

        // ---------- 基本函数 / 链式 ----------

        private fun functionNode(ast: IAST, expr: IExpr, depth: Int): Node? {
            val rule = basicRuleOf(ast) ?: return null
            val inner = ast.arg1()
            val innerIsX = inner.equals(x)
            val child = if (innerIsX) null else walk(inner, depth + 1) ?: return null
            val innerTex = tex(inner) ?: return null
            val fPrimeExpr = rule.derivative(inner)
            val fPrimeTex = rule.latex(innerTex)
            val derivative = if (innerIsX) fPrimeExpr else F.Times(fPrimeExpr, child!!.derivative)
            val lhs = primeOf(expr) ?: return null
            val line = if (innerIsX) {
                "$lhs=$fPrimeTex"
            } else {
                "$lhs=$fPrimeTex\\cdot ${markerOf(child!!) ?: return null}"
            }
            return Node(
                expr,
                if (innerIsX) "basicDerivative" else "chainRule",
                if (innerIsX) "基本求导公式" else "链式法则",
                listOf(line),
                if (child == null) emptyList() else listOf(child),
                derivative,
            )
        }

        private class BasicRule(
            val derivative: (IExpr) -> IExpr,
            val latex: (String) -> String,
        )

        private fun basicRuleOf(ast: IAST): BasicRule? = when {
            ast.isAST(F.Sin) -> BasicRule(
                { u -> F.Cos(u) },
                { u -> "\\cos\\left($u\\right)" },
            )
            ast.isAST(F.Cos) -> BasicRule(
                { u -> F.Negate(F.Sin(u)) },
                { u -> "-\\sin\\left($u\\right)" },
            )
            ast.isAST(F.Tan) -> BasicRule(
                { u -> F.Power(F.Sec(u), F.C2) },
                { u -> "\\sec^{2}\\left($u\\right)" },
            )
            ast.isAST(F.Cot) -> BasicRule(
                { u -> F.Negate(F.Power(F.Csc(u), F.C2)) },
                { u -> "-\\csc^{2}\\left($u\\right)" },
            )
            ast.isAST(F.Sec) -> BasicRule(
                { u -> F.Times(F.Sec(u), F.Tan(u)) },
                { u -> "\\sec\\left($u\\right)\\tan\\left($u\\right)" },
            )
            ast.isAST(F.Csc) -> BasicRule(
                { u -> F.Negate(F.Times(F.Cot(u), F.Csc(u))) },
                { u -> "-\\cot\\left($u\\right)\\csc\\left($u\\right)" },
            )
            ast.isAST(F.Log) -> BasicRule(
                { u -> F.Power(u, F.CN1) },
                { u -> "\\frac{1}{$u}" },
            )
            ast.isAST(F.ArcSin) -> BasicRule(
                { u -> F.Power(F.Subtract(F.C1, F.Power(u, F.C2)), F.CN1D2) },
                { u -> "\\frac{1}{\\sqrt{1-\\left($u\\right)^{2}}}" },
            )
            ast.isAST(F.ArcCos) -> BasicRule(
                { u -> F.Negate(F.Power(F.Subtract(F.C1, F.Power(u, F.C2)), F.CN1D2)) },
                { u -> "-\\frac{1}{\\sqrt{1-\\left($u\\right)^{2}}}" },
            )
            ast.isAST(F.ArcTan) -> BasicRule(
                { u -> F.Power(F.Plus(F.C1, F.Power(u, F.C2)), F.CN1) },
                { u -> "\\frac{1}{1+\\left($u\\right)^{2}}" },
            )
            ast.isAST(F.ArcCot) -> BasicRule(
                { u -> F.Negate(F.Power(F.Plus(F.C1, F.Power(u, F.C2)), F.CN1)) },
                { u -> "-\\frac{1}{1+\\left($u\\right)^{2}}" },
            )
            ast.isAST(F.Sinh) -> BasicRule(
                { u -> F.Cosh(u) },
                { u -> "\\cosh\\left($u\\right)" },
            )
            ast.isAST(F.Cosh) -> BasicRule(
                { u -> F.Sinh(u) },
                { u -> "\\sinh\\left($u\\right)" },
            )
            ast.isAST(F.Tanh) -> BasicRule(
                { u -> F.Power(F.Cosh(u), F.CN2) },
                { u -> "\\frac{1}{\\cosh^{2}\\left($u\\right)}" },
            )
            ast.isAST(F.Coth) -> BasicRule(
                { u -> F.Negate(F.Power(F.Sinh(u), F.CN2)) },
                { u -> "-\\frac{1}{\\sinh^{2}\\left($u\\right)}" },
            )
            else -> null
        }

        // ---------- 工具 ----------

        private fun tex(expr: IExpr): String? = engine.toExactLatex(expr)

        private fun primeOf(expr: IExpr): String? = tex(expr)?.let { "\\left($it\\right)'" }

        private fun markerOf(node: Node): String? = node.marker ?: primeOf(node.expr)

        /** 因子本身是加减式时补一层括号，单个项就省掉，长行能短一点。 */
        private fun parenIfSum(tex: String): String =
            if (tex.contains('+') || tex.contains('-')) "\\left($tex\\right)" else tex

        /** 摊平右嵌套的 Plus / Times。 */
        private fun flat(ast: IAST, head: IExpr): List<IExpr>? {
            val out = mutableListOf<IExpr>()
            for (i in 1 until ast.size) {
                val child = ast.get(i)
                val childAst = child as? IAST
                if (childAst != null && childAst.isAST(head) && childAst.size >= 2) {
                    out.addAll(flat(childAst, head) ?: return null)
                } else {
                    out.add(child)
                }
            }
            return out
        }

        /** 项前面的负号拆出来：`-2x` -> `(true, 2x)`。 */
        private fun splitSign(term: IExpr): Pair<Boolean, IExpr> {
            val ast = term as? IAST
            if (ast != null && ast.isAST(F.Times) && ast.size >= 2) {
                val first = ast.arg1()
                if (first.isNumber && first.isNegative) {
                    return true to F.eval(F.Negate(term))
                }
                return false to term
            }
            if (term.isNumber && term.isNegative) return true to F.eval(F.Negate(term))
            return false to term
        }

        private fun isNegativePower(expr: IExpr): Boolean {
            val ast = expr as? IAST ?: return false
            if (!ast.isAST(F.Power) || ast.size != 3) return false
            val exponent = ast.arg2()
            return exponent.isNumber && exponent.isNegative
        }

        /** `1/u^k` -> `u^k`（分母）。 */
        private fun reciprocalBase(expr: IExpr): IExpr {
            val ast = expr as IAST
            val base = ast.arg1()
            val exponent = ast.arg2()
            return if (exponent.isMinusOne) base else F.Power(base, F.eval(F.Negate(exponent)))
        }

        private fun timesOf(factors: List<IExpr>): IExpr = when (factors.size) {
            0 -> F.C1
            1 -> factors.first()
            else -> F.Times(*factors.toTypedArray())
        }
    }
}
