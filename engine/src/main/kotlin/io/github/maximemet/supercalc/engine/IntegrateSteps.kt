package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 不定积分的「解决过程」：线性性 → 基本积分表 / 第一类换元 / 分部积分 → 结果。
 *
 * 原版的过程引擎不管积分，这一份是补的。做法和[DerivativeSteps]同一条思路：
 *  - 把被积函数拆成若干项（线性性），逐项找出它属于哪条基本公式；
 *  - 形如 `f(kx+b)` 的项给第一类换元（凑微分）；
 *  - 乘积里含对数/反三角/幂函数与指数/三角混合的项给分部积分（LIATE 选 u）；
 *  - 每一项的「原函数」都由引擎算，算完**求导回验**（数值采样），
 *    验不过就不出过程；结果行和结果页顶部保持同一条通道。
 */
object IntegrateSteps {

    /**
     * 回验采样点：避开 0、±1、±2 这些常见奇点，同时留几个落在 (-1,1) 里的点——
     * arcsin / arccos / artanh 型公式（∫dx/√(1-x²)、∫arcsin x dx…）只在 (-1,1)
     * 上有实数值，点全取大了这些规则会一条都验不过。
     */
    private val SAMPLE_POINTS = doubleArrayOf(0.6, 0.3, 1.4, 2.3, 3.7)

    fun buildJson(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String? = null,
    ): String? = ProcessSteps.toJson(build(engine, formula, originalLatex) ?: emptyList())

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

    private enum class Kind { BASIC, SUBSTITUTION, PARTS, GENERIC }

    private class TermInfo(
        val expr: IExpr,
        val constant: IExpr?,
        val core: IExpr,
        val anti: IExpr,
        val kind: Kind,
        val note: String?,
        val equation: String,
        /** 数学排版的提示行（如分部的 u/dv/du/v），与 [note] 二选一。 */
        val noteTex: List<String> = emptyList(),
        /** 长等式拆出的第二行（`= 原函数`）；短等式是 null。 */
        val equationTail: String? = null,
    )

    private fun buildInner(
        engine: SymjaEngine,
        formula: String,
        originalLatex: String?,
    ): List<ProcessStep>? {
        val parsed = engine.parseOrNull(formula) ?: return null
        if (!parsed.isAST(F.Integrate)) return null
        val ast = parsed as IAST
        if (ast.size != 3) return null
        val body = ast.arg1()
        val variableExpr = ast.arg2()
        if (!variableExpr.isSymbol) return null
        val variable = variableExpr.toString()
        if (body.isFree(variableExpr)) return null

        val finalAnti = integrateOf(engine, body, variable) ?: return null
        if (!verifies(engine, body, finalAnti, variable)) return null
        val resultTex = engine.toExactLatex(finalAnti) ?: return null

        val bodyTex = engine.toExactLatex(body) ?: return null
        val shownBody = originalLatex?.takeIf { it.isNotBlank() }?.let { LatexText.editorSafe(it) }
            ?: bodyTex
        val wrappedBody = if (body.isAST(F.Plus)) "\\left($shownBody\\right)" else shownBody

        val steps = mutableListOf<ProcessStep>()
        steps += ProcessStep("original", "原式", listOf("\\int $wrappedBody\\,dx"))

        val terms = flatPlus(body) ?: listOf(body)
        val infos = mutableListOf<TermInfo>()
        for (term in terms) {
            val anti = integrateOf(engine, term, variable) ?: return null
            if (!verifies(engine, term, anti, variable)) return null
            infos += infoFor(engine, variable, term, anti)
        }

        // 1. 线性性：多项，或者有常数因子
        if (infos.size > 1 || infos.any { it.constant != null && !it.constant.isOne }) {
            linearityStep(engine, infos)?.let { steps += it }
        }

        // 2. 按技巧分组
        addGroup(steps, infos, Kind.BASIC, "basicTable", "基本积分表")
        addGroup(steps, infos, Kind.SUBSTITUTION, "substitution", "第一类换元")
        addGroup(steps, infos, Kind.PARTS, "integrationByParts", "分部积分")
        addGroup(steps, infos, Kind.GENERIC, "termByTerm", "逐项积分")

        steps += ProcessStep("result", "计算结果", listOf("= $resultTex + C"))
        return steps
    }

    private fun addGroup(
        steps: MutableList<ProcessStep>,
        infos: List<TermInfo>,
        kind: Kind,
        key: String,
        label: String,
    ) {
        val group = infos.filter { it.kind == kind }
        if (group.isEmpty()) return
        val lines = mutableListOf<String>()
        for (info in group) {
            info.note?.let { lines += "T:$it" }
            lines += info.noteTex
            lines += info.equation
            info.equationTail?.let { lines += it }
        }
        steps += ProcessStep(key, label, lines)
    }

    /** `\int(c_1f_1+c_2f_2)dx=c_1\int f_1dx+c_2\int f_2dx`。 */
    private fun linearityStep(engine: SymjaEngine, infos: List<TermInfo>): ProcessStep? {
        val bodyTex = engine.toExactLatex(timesSum(infos.map { it.expr })) ?: return null
        val parts = infos.map { info ->
            val coreTex = engine.toExactLatex(info.core) ?: return null
            val termTex = engine.toExactLatex(info.expr) ?: return null
            if (info.constant != null && !info.constant.isOne && !info.core.isOne) {
                val constTex = engine.toExactLatex(info.constant) ?: return null
                "$constTex\\int $coreTex\\,dx"
            } else {
                "\\int $termTex\\,dx"
            }
        }
        val wrapped = if (bodyTex.contains('+') || bodyTex.contains('-')) {
            "\\left($bodyTex\\right)"
        } else {
            bodyTex
        }
        return ProcessStep(
            "linearity",
            "线性性",
            listOf("\\int $wrapped\\,dx=${parts.joinToString("+")}"),
        )
    }

    // ---------- 逐项的规则识别 ----------

    private fun infoFor(
        engine: SymjaEngine,
        variable: String,
        term: IExpr,
        anti: IExpr,
    ): TermInfo {
        val (constant, core) = splitConstant(engine, variable, term)
        val antiTex = engine.toExactLatex(anti) ?: term.toString()
        val coreTex = engine.toExactLatex(core) ?: core.toString()
        val termTex = engine.toExactLatex(term) ?: term.toString()
        val variableSym = engine.symbol(variable)

        // 常数项（整个项与 x 无关）
        if (core.isFree(variableSym)) {
            return TermInfo(
                term, constant, core, anti, Kind.BASIC,
                "常数公式：∫c dx = c·x",
                "\\int $termTex\\,dx=$antiTex",
            )
        }

        // 基本公式
        basicNote(engine, variable, core)?.let { note ->
            val equation = if (constant != null && !constant.isOne) {
                val constTex = engine.toExactLatex(constant) ?: return@let
                "\\int $termTex\\,dx=$constTex\\int $coreTex\\,dx=$antiTex"
            } else {
                "\\int $termTex\\,dx=$antiTex"
            }
            return TermInfo(term, constant, core, anti, Kind.BASIC, note, equation)
        }

        // 第一类换元：f(kx+b)
        linearArgumentOf(engine, variable, core)?.let { (arg, k, _) ->
            val argTex = engine.toExactLatex(arg) ?: return@let
            val kTex = engine.toExactLatex(k) ?: return@let
            val uSymbol = engine.symbol("u")
            val coreWithU = withArgument(core, uSymbol) ?: return@let
            val coefficient = F.eval(
                F.Divide(if (constant != null) constant else F.C1, k)
            )
            val coefficientTex = engine.toExactLatex(coefficient) ?: return@let
            val coreWithUTex = engine.toExactLatex(coreWithU) ?: return@let
            val equation = "\\int $termTex\\,dx=$coefficientTex\\int $coreWithUTex\\,du"
            return TermInfo(
                term, constant, core, anti, Kind.SUBSTITUTION, null, equation,
                listOf("u=$argTex,\\quad du=$kTex\\,dx"),
                "=$antiTex",
            )
        }

        // 分部积分
        partsInfo(engine, variable, term, anti)?.let { return it }

        return TermInfo(
            term, constant, core, anti, Kind.GENERIC, null,
            "\\int $termTex\\,dx=$antiTex",
        )
    }

    /** 基本积分表里能一眼认出的形状。 */
    private fun basicNote(engine: SymjaEngine, variable: String, core: IExpr): String? {
        // 先查数据规则包（仓库里加一条就多认一类直接套公式的题）
        rulePackNote(engine, variable, core)?.let { return it }

        val x = engine.symbol(variable)
        val ast = core as? IAST
        if (core.equals(x)) return "幂函数公式：∫x dx = x²/2"
        if (ast != null && ast.isAST(F.Power) && ast.size == 3) {
            val base = ast.arg1()
            val exponent = ast.arg2()
            if (base.equals(x) && exponent.isNumber) {
                return if (exponent.isMinusOne) {
                    "基本积分公式：∫(1/x)dx = ln|x|"
                } else {
                    "幂函数公式：∫xⁿdx = xⁿ⁺¹/(n+1)"
                }
            }
            if (base.isE && exponent.equals(x)) return "基本积分公式：∫eˣdx = eˣ"
            if (exponent.isMinusOne && isOnePlusSquare(engine, base, x)) {
                return "基本积分公式：∫dx/(1+x²) = arctan x"
            }
            val halfNegative = exponent.isFraction && exponent.equals(F.CN1D2)
            if (halfNegative && isOneMinusSquare(engine, base, x)) {
                return "基本积分公式：∫dx/√(1-x²) = arcsin x"
            }
            return null
        }
        if (ast != null && ast.size == 2) {
            val arg = ast.arg1()
            if (!arg.equals(x)) return null
            return when {
                ast.isAST(F.Sin) -> "基本积分公式：∫sin x dx = -cos x"
                ast.isAST(F.Cos) -> "基本积分公式：∫cos x dx = sin x"
                ast.isAST(F.Tan) -> "基本积分公式：∫tan x dx = -ln|cos x|"
                ast.isAST(F.Sinh) -> "基本积分公式：∫sh x dx = ch x"
                ast.isAST(F.Cosh) -> "基本积分公式：∫ch x dx = sh x"
                ast.isAST(F.Log) -> "基本积分公式：∫ln x dx = x·ln x - x"
                ast.isAST(F.ArcTan) -> "基本积分公式：∫arctan x dx = x·arctan x - ½ln(1+x²)"
                else -> null
            }
        }
        return null
    }

    /**
     * 规则包里能整串匹配上的第一条：模板原函数先过数值求导回验，验不过跳过。
     *
     * 这样仓库里写错的公式不会显示出来；返回的是规则的说明文案，
     * 实际结果行仍旧用引擎算出来的原函数（和顶部结果同源）。
     */
    private fun rulePackNote(engine: SymjaEngine, variable: String, core: IExpr): String? {
        for (rule in IntegralRulePack.rules) {
            val claimed = IntegralRulePack.apply(engine, core, rule, variable) ?: continue
            println("DBG pack ${rule.id} claimed=$claimed verified=${verifies(engine, core, claimed, variable)}")
            if (!verifies(engine, core, claimed, variable)) continue
            return rule.note
        }
        return null
    }

    private fun isOnePlusSquare(engine: SymjaEngine, expr: IExpr, x: IExpr): Boolean {
        val diff = F.eval(F.Subtract(expr, F.Plus(F.C1, F.Power(x, F.C2))))
        return diff.isZero
    }

    private fun isOneMinusSquare(engine: SymjaEngine, expr: IExpr, x: IExpr): Boolean {
        val diff = F.eval(F.Subtract(expr, F.Subtract(F.C1, F.Power(x, F.C2))))
        return diff.isZero
    }

    /**
     * `f(kx+b)` 的识别：函数类核心且自变量部分关于 x 是一次式。
     * 返回 (kx+b, k, b)。
     */
    private fun linearArgumentOf(
        engine: SymjaEngine,
        variable: String,
        core: IExpr,
    ): Triple<IExpr, IExpr, IExpr>? {
        val ast = core as? IAST ?: return null
        if (ast.isAST(F.Power) && ast.size == 3) {
            // a^(kx+b) / e^(kx+b)
            val base = ast.arg1()
            if (!base.isFree(engine.symbol(variable))) return null
            val arg = ast.arg2()
            val coeffs = linearCoefficients(engine, variable, arg) ?: return null
            return Triple(arg, coeffs.first, coeffs.second)
        }
        if (ast.size != 2) return null
        val interesting = ast.isAST(F.Sin) || ast.isAST(F.Cos) || ast.isAST(F.Tan) ||
            ast.isAST(F.Sinh) || ast.isAST(F.Cosh) || ast.isAST(F.Log)
        if (!interesting) return null
        val arg = ast.arg1()
        val coeffs = linearCoefficients(engine, variable, arg) ?: return null
        return Triple(arg, coeffs.first, coeffs.second)
    }

    /** 把一元函数/`a^u` 的自变量换成 [arg]（换元行里显示 f(u) 用）。 */
    private fun withArgument(core: IExpr, arg: IExpr): IExpr? {
        val ast = core as? IAST ?: return null
        if (ast.isAST(F.Power) && ast.size == 3) return F.Power(ast.arg1(), arg)
        if (ast.size != 2) return null
        return F.ast(arrayOf(arg), ast.head())
    }

    /** u = k·x + b（k 数值非零、b 与 x 无关）；k=1、b=0 时视为「就是 x」，不再算换元。 */
    private fun linearCoefficients(
        engine: SymjaEngine,
        variable: String,
        expr: IExpr,
    ): Pair<IExpr, IExpr>? {
        val x = engine.symbol(variable)
        if (expr.equals(x)) return null
        val k = engine.evaluateOrNull(engine.parseOrNull("Coefficient(($expr), $variable)"))
            ?: return null
        if (!k.isNumber || k.isZero) return null
        val b = F.eval(F.Subtract(expr, F.Times(k, x)))
        if (!b.isFree(x)) return null
        return k to b
    }

    /**
     * 分部积分：LIATE 选 u（对数 > 反三角 > 幂函数 > 三角 > 指数）。
     * 单因子是 ln / arctan 这类时补一个 dv = dx。
     */
    private fun partsInfo(
        engine: SymjaEngine,
        variable: String,
        term: IExpr,
        anti: IExpr,
    ): TermInfo? {
        val factors = flatTimes(term) ?: listOf(term)
        val ranked = factors.map { it to rankOf(engine, variable, it) }
        val (u, uRank) = ranked.minByOrNull { it.second } ?: return null
        if (uRank > 3) return null

        val rest = factors.filterIndexed { index, _ -> index != ranked.indexOfFirst { it.first === u } }
        val dv = timesOf(rest)
        // 单个对数/反三角函数补 dx
        val effectiveDv = if (rest.isEmpty()) F.C1 else dv

        val du = derivativeOf(engine, u, variable) ?: return null
        val v = integrateOf(engine, effectiveDv, variable) ?: return null
        val remainingProduct = F.eval(F.Times(v, du))
        val remaining = integrateOf(engine, remainingProduct, variable) ?: return null
        val claimed = F.eval(F.Subtract(F.Times(u, v), remaining))
        if (!verifies(engine, term, claimed, variable)) return null

        val uTex = engine.toExactLatex(u) ?: return null
        val dvTex = engine.toExactLatex(effectiveDv) ?: return null
        val duTex = engine.toExactLatex(du) ?: return null
        val vTex = engine.toExactLatex(v) ?: return null
        val termTex = engine.toExactLatex(term) ?: return null
        val remainingIntegrandTex = engine.toExactLatex(remainingProduct) ?: return null
        val claimedTex = engine.toExactLatex(claimed) ?: return null
        val noteTex = listOf(
            "u=$uTex,\\quad dv=$dvTex\\,dx",
            "du=$duTex,\\quad v=$vTex",
        )
        // 负号折叠进乘积/积分，避免 `x·(-cos x)-∫(-cos x)dx` 这类又长又绕的写法
        val productTex = if (vTex.trimStart().startsWith("-")) {
            val positive = F.eval(F.Negate(v))
            val positiveTex = engine.toExactLatex(positive) ?: return null
            "-${paren(u, uTex)}\\cdot ${factorParen(positive, positiveTex)}"
        } else {
            "${paren(u, uTex)}\\cdot ${factorParen(v, vTex)}"
        }
        val (operatorTex, integrandTex) = if (remainingIntegrandTex.trimStart().startsWith("-")) {
            val positive = F.eval(F.Negate(remainingProduct))
            val positiveTex = engine.toExactLatex(positive) ?: return null
            "+" to sumParen(positive, positiveTex)
        } else {
            "-" to sumParen(remainingProduct, remainingIntegrandTex)
        }
        val equation = "\\int $termTex\\,dx=$productTex$operatorTex\\int $integrandTex\\,dx"
        return TermInfo(
            term, null, term, anti, Kind.PARTS, null, equation, noteTex,
            "=$claimedTex",
        )
    }

    /**
     * 套括号的场合：加法式、以负号开头、以及 `ln x` 这类函数式因子
     * （避免 `x·-cos x`、`ln x·x²/2` 这种看不清边界或连写的排版）。
     */
    private fun paren(expr: IExpr, tex: String): String {
        val ast = expr as? IAST
        val trimmed = tex.trimStart()
        val functionCall = ast != null && ast.size == 2 && !ast.isAST(F.Power)
        val needs = trimmed.startsWith("-") || (ast != null && ast.isPlus()) || functionCall
        return if (needs) "\\left($tex\\right)" else tex
    }

    /** 跟在 `∫` 后面的被积函数：只有多项式这种加法式才需要补括号。 */
    private fun sumParen(expr: IExpr, tex: String): String {
        val ast = expr as? IAST
        return if (ast != null && ast.isPlus()) "\\left($tex\\right)" else tex
    }

    /** `u·v` 右边的因子：负号、加法式要补括号，`cos x` 这种函数式不用。 */
    private fun factorParen(expr: IExpr, tex: String): String {
        val ast = expr as? IAST
        val needs = tex.trimStart().startsWith("-") || (ast != null && ast.isPlus())
        return if (needs) "\\left($tex\\right)" else tex
    }

    private fun rankOf(engine: SymjaEngine, variable: String, expr: IExpr): Int {
        val x = engine.symbol(variable)
        val ast = expr as? IAST ?: return if (expr.equals(x)) 2 else 5
        return when {
            ast.isAST(F.Log) -> 0
            ast.isAST(F.ArcSin) || ast.isAST(F.ArcCos) ||
                ast.isAST(F.ArcTan) || ast.isAST(F.ArcCot) -> 1
            ast.isAST(F.Power) && ast.size == 3 -> {
                val base = ast.arg1()
                val exponentIsConstant = ast.arg2().isFree(x)
                when {
                    base.equals(x) && exponentIsConstant -> 2
                    base.isE -> 4
                    base.isFree(x) -> 4
                    else -> 5
                }
            }
            ast.isAST(F.Sin) || ast.isAST(F.Cos) || ast.isAST(F.Tan) -> 3
            ast.isAST(F.Plus) || ast.isAST(F.Times) -> {
                // 多项式/一般代数式：只要不含超越函数就当幂函数类
                if (ast.isFree(F.Sin) && ast.isFree(F.Cos) && ast.isFree(F.Log) &&
                    ast.isFree(F.E)
                ) 2 else 5
            }
            else -> 5
        }
    }

    // ---------- 工具 ----------

    private fun splitConstant(
        engine: SymjaEngine,
        variable: String,
        term: IExpr,
    ): Pair<IExpr?, IExpr> {
        val x = engine.symbol(variable)
        val ast = term as? IAST ?: return null to term
        if (!ast.isAST(F.Times)) return null to term
        val factors = flatTimes(ast) ?: return null to term
        val consts = factors.filter { it.isFree(x) }
        if (consts.isEmpty()) return null to term
        val rest = factors.filterNot { it.isFree(x) }
        if (rest.isEmpty()) return timesOf(consts) to F.C1
        return timesOf(consts) to timesOf(rest)
    }

    private fun flatPlus(expr: IExpr): List<IExpr>? {
        val ast = expr as? IAST ?: return null
        if (!ast.isAST(F.Plus)) return null
        val out = mutableListOf<IExpr>()
        for (i in 1 until ast.size) {
            val child = ast.get(i)
            val childOut = flatPlus(child)
            if (childOut != null) out.addAll(childOut) else out += child
        }
        return out
    }

    private fun flatTimes(expr: IExpr): List<IExpr>? {
        val ast = expr as? IAST ?: return null
        if (!ast.isAST(F.Times)) return null
        val out = mutableListOf<IExpr>()
        for (i in 1 until ast.size) {
            val child = ast.get(i)
            val childOut = flatTimes(child)
            if (childOut != null) out.addAll(childOut) else out += child
        }
        return out
    }

    private fun timesOf(factors: List<IExpr>): IExpr = when (factors.size) {
        0 -> F.C1
        1 -> factors.first()
        else -> F.Times(*factors.toTypedArray())
    }

    private fun timesSum(terms: List<IExpr>): IExpr =
        if (terms.size == 1) terms.first() else F.Plus(*terms.toTypedArray())

    private fun integrateOf(engine: SymjaEngine, expr: IExpr, variable: String): IExpr? {
        val value = engine.evaluateOrNull(
            engine.parseOrNull("Integrate(($expr), $variable)")
        ) ?: return null
        val text = value.toString()
        if (text.contains("Integrate(")) return null
        // 特殊函数解（Erfi、Gamma、超几何…）对做题没意义，不拿来做步骤
        if (SPECIAL_FUNCTIONS.any { text.contains(it) }) return null
        if (engine.isInvalid(value)) return null
        return value
    }

    private val SPECIAL_FUNCTIONS = listOf(
        "Gamma(", "Erfi", "Erf(", "Hypergeometric", "ExpIntegral", "LogIntegral",
        "Fresnel", "Zeta", "Elliptic", "PolyLog", "ProductLog",
    )

    private fun derivativeOf(engine: SymjaEngine, expr: IExpr, variable: String): IExpr? {
        val value = engine.evaluateOrNull(
            engine.parseOrNull("Diff(($expr), $variable)")
        ) ?: return null
        if (value.toString().contains("Derivative(")) return null
        return value
    }

    /** 求导回验：F' 与被积函数在采样点上一致。 */
    private fun verifies(
        engine: SymjaEngine,
        integrand: IExpr,
        antiderivative: IExpr,
        variable: String,
    ): Boolean {
        val derivative = derivativeOf(engine, antiderivative, variable) ?: return false
        var checked = 0
        for (point in SAMPLE_POINTS) {
            val a = engine.signAt(integrand.toString(), variable, point) ?: continue
            val b = engine.signAt(derivative.toString(), variable, point) ?: continue
            checked++
            val tolerance = 1e-6 * (1.0 + kotlin.math.abs(a) + kotlin.math.abs(b))
            if (kotlin.math.abs(a - b) > tolerance) return false
        }
        return checked >= 2
    }

}
