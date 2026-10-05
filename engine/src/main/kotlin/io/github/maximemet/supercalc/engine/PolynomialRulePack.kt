package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/**
 * 「多项式分解」公式法规则包。
 *
 * 表在 `engine/src/main/resources/rules/polynomials.json`，按顺序取第一条命中的规则。
 * 每条规则：`label` 步骤标签；`formula` 顺带显示的公式原文（纯文本，可空）；
 * `degree` 次数条件（可省）；`match` 因子形状模板（可省，`u_`/`v_` 通配符）；
 * `factorable` 置 true 时要求分解结果真的是乘积/幂（十字相乘用）。
 *
 * 匹配方式和积分/极限包不同：公式法要认的是"乘积长什么样"，而 Symja 的
 * Factor 输出本身就是标准形。所以先把数值内容因子提出来（2x³-16 → x³-8），
 * 再从 Factor 输出里收集候选 u/v（和的项、乘积整体、幂的底数，都带相反数），
 * 逐个代进模板，用引擎回验两条：模板实例 == Factor 输出、模板展开 == 原式。
 * 两条都过才算命中——仓库里写错的模板不会显示出来。候选枚举带形状预筛和
 * 数量上限，大因式分解不值得为标签烧时间，枚举不动就走兜底。
 */
object PolynomialRulePack {

    private const val RESOURCE = "/rules/polynomials.json"

    /** 候选超过这个数就不再枚举（返回空表，规则不命中）。 */
    private const val MAX_CANDIDATES = 12

    data class Rule(
        val id: String,
        val label: String,
        val formula: String?,
        val degree: Int?,
        val match: String?,
        val factorable: Boolean,
    )

    private var cached: List<Rule>? = null

    /** 规则表：手动更新包里有就用更新包，否则用内置资源。 */
    val rules: List<Rule>
        get() = cached ?: load().also { cached = it }

    /** 更新包变化后让缓存失效（见 [RulePacks]）。 */
    internal fun invalidate() {
        cached = null
    }

    private fun load(): List<Rule> {
        RulePacks.section("polynomials")?.let { items ->
            runCatching { parseRules(items) }.getOrNull()?.let { return it }
        }
        return try {
            val text = PolynomialRulePack::class.java.getResourceAsStream(RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return emptyList()
            parse(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun parse(text: String): List<Rule> {
        val root = MiniJson.asObject(MiniJson.parse(text))
        val array = MiniJson.asArray(root["rules"] ?: emptyList<Any?>())
        return parseRules(array)
    }

    private fun parseRules(array: List<Any?>): List<Rule> =
        array.map { node ->
            val item = MiniJson.asObject(node)
            Rule(
                id = MiniJson.asString(item["id"], "id"),
                label = MiniJson.asString(item["label"], "label"),
                formula = (item["formula"] as? String)?.takeIf { it.isNotBlank() },
                degree = (item["degree"] as? Number)?.toInt(),
                match = (item["match"] as? String)?.takeIf { it.isNotBlank() },
                factorable = item["factorable"] == true,
            )
        }

    /**
     * 找出 [expr] 的分解用的是哪条公式；都没命中返回 null（调用方回落到"因式分解"）。
     *
     * [factored] 是**同一个表达式**的引擎分解输出（提过公因式时是对括号里那一份）。
     */
    fun technique(engine: SymjaEngine, expr: IExpr, factored: IExpr, x: IExpr): Rule? =
        technique(engine, expr, factored, x, rules)

    /** 指定规则表（测试用）：按顺序取第一条命中的。 */
    internal fun technique(
        engine: SymjaEngine,
        expr: IExpr,
        factored: IExpr,
        x: IExpr,
        ruleList: List<Rule>,
    ): Rule? {
        val degree = degreeOf(engine, expr, x)
        // 模板规则的 u/v 次数上界：三次公式里 u、v 最高一次（u²·v 那项）。
        // 将来加了更高次的模板，上界跟着涨，不用改代码。
        val templateDegree = ruleList.filter { it.match != null }.mapNotNull { it.degree }.maxOrNull() ?: 3
        val candidateMaxDegree = maxOf(1, templateDegree - 2)

        // 数值内容因子先提出来：2x³-16 的公式应该按 x³-8 认。
        var primitive = expr
        var target = factored
        var candidates: List<IExpr>? = null
        fun prepare() {
            if (candidates != null) return
            val content = contentOf(engine, expr, x)
            if (content != null && content.toString() != "1") {
                divide(engine, expr, content)?.let { divided ->
                    primitive = divided
                    target = engine.decompose(divided.toString()) ?: factored
                }
            }
            candidates = candidatesOf(engine, target, x, candidateMaxDegree)
        }

        for (rule in ruleList) {
            if (rule.degree != null && rule.degree != degree) continue
            if (rule.factorable && !(factored.isTimes() || factored.isPower())) continue
            val template = rule.match ?: return rule
            prepare()
            val list = candidates ?: emptyList()
            if (list.size < 2) continue
            if (matchesTemplate(engine, template, primitive, target, list)) return rule
        }
        return null
    }

    /** 模板整串配对：枚举候选 u/v，代入后过两条回验。 */
    private fun matchesTemplate(
        engine: SymjaEngine,
        template: String,
        primitive: IExpr,
        target: IExpr,
        candidates: List<IExpr>,
    ): Boolean {
        val templateExpr = engine.parseOrNull(template) ?: return false
        if (!sameShape(templateExpr, target)) return false
        for (u in candidates) {
            for (v in candidates) {
                if (u.toString() == v.toString()) continue
                val code = RulePatterns.instantiate(template, mapOf("u" to u, "v" to v))
                val subst = engine.parseOrNull(code) ?: continue
                if (!sameExpr(engine, subst, target)) continue
                if (!sameExpr(engine, subst, primitive)) continue
                return true
            }
        }
        return false
    }

    /**
     * 形状预筛：模板是乘积就要求目标也是乘积且因子个数一致，各因子的项数也要对上；
     * 模板是幂就要求目标也是幂。挡掉绝大多数不可能的组合，免得白跑候选循环。
     */
    private fun sameShape(template: IExpr, target: IExpr): Boolean = when {
        template.isTimes() -> target.isTimes() &&
            factorShape(template as IAST) == factorShape(target as IAST)
        template.isPower() -> target.isPower()
        else -> true
    }

    /** 乘积的各因子形状：和式记项数（p2/p3…），其余记 x。排序后按多重集比。 */
    private fun factorShape(times: IAST): List<String> =
        (1 until times.size).map { index ->
            val arg = times.get(index)
            if (arg.isPlus()) "p" + plusTerms(arg as IAST) else "x"
        }.sorted()

    /**
     * Plus 的项数。
     *
     * 带通配符的模板走解析器时会出现嵌套 Plus——`u_^2-u_*v_+v_^2` 解析出来是
     * `(u_^2-u_*v_)+v_^2`，不摊平就会把三项数成两项。
     */
    private fun plusTerms(plus: IAST): Int =
        (1 until plus.size).sumOf { index ->
            val arg = plus.get(index)
            if (arg.isPlus()) plusTerms(arg as IAST) else 1
        }

    private fun degreeOf(engine: SymjaEngine, expr: IExpr, x: IExpr): Int? =
        engine.evaluateOrNull(engine.parseOrNull("Exponent(($expr), $x)"))
            ?.toString()?.toIntOrNull()

    /** 系数的最大公因数（有理数也支持）；含参数系数就不提。 */
    private fun contentOf(engine: SymjaEngine, expr: IExpr, x: IExpr): IExpr? {
        val list = engine.evaluateOrNull(engine.parseOrNull("CoefficientList(($expr), $x)")) as? IAST
            ?: return null
        val coefficients = (1 until list.size).map { list.get(it) }
        if (coefficients.isEmpty() || coefficients.any { !it.isNumber }) return null
        val gcd = engine.evaluateOrNull(engine.parseOrNull("GCD(${coefficients.joinToString(",")})"))
            ?: return null
        return gcd.takeIf { it.isNumber && !it.isZero }
    }

    private fun divide(engine: SymjaEngine, expr: IExpr, by: IExpr): IExpr? =
        engine.evaluateOrNull(engine.parseOrNull("($expr)/($by)"))

    /**
     * 候选 u/v：和的整项、幂的底数、光杆符号，每个都带上相反数。
     *
     * 只收次数不超过 [maxDegree] 的（模板带次数条件，u/v 不可能更高次），
     * 数字只在"单独成项"时才算候选——`4x²-6x+9` 里的 4、6 是系数，不是 u/v；
     * 而 `(2x+3)` 里的 3 是。这样候选表里不会塞满噪声。
     *
     * 例：`(2x-3)(2x+3)` 给出 {2x,-2x,3,-3,x,-x}；
     * `(2x+3)(4x²-6x+9)` 给出 {2x,-2x,3,-3,x,-x,6x,-6x,9,-9}。
     */
    private fun candidatesOf(engine: SymjaEngine, expr: IExpr, x: IExpr, maxDegree: Int): List<IExpr> {
        val out = ArrayList<IExpr>()
        val seen = HashSet<String>()
        fun add(node: IExpr) {
            val degree = degreeOf(engine, node, x) ?: 0
            if (degree > maxDegree) return
            val text = node.toString()
            if (seen.add(text)) out += node
            val negated = F.eval(F.Negate(node))
            val negatedText = negated.toString()
            if (negatedText != text && seen.add(negatedText)) out += negated
        }

        fun walk(node: IExpr, standalone: Boolean, isRoot: Boolean) {
            if (out.size > MAX_CANDIDATES) return
            when {
                node.isTimes() -> {
                    if (standalone && !isRoot) add(node)
                    (node as IAST).let { ast ->
                        (1 until ast.size).forEach { walk(ast.get(it), false, false) }
                    }
                }
                node.isPower() -> {
                    val ast = node as IAST
                    if (!isRoot) add(node)
                    val exponent = if (ast.size == 3) ast.arg2() else null
                    if (exponent != null && exponent.isInteger && !exponent.isNegative()) {
                        walk(ast.arg1(), false, false)
                    } else {
                        add(node)
                    }
                }
                node.isPlus() -> (node as IAST).let { ast ->
                    (1 until ast.size).forEach { walk(ast.get(it), true, false) }
                }
                node.isNumber -> if (standalone || isRoot) add(node)
                else -> add(node)
            }
        }
        walk(expr, true, true)
        return if (out.size > MAX_CANDIDATES) emptyList() else out
    }

    /** 两个表达式是否恒等：先看差求值，再退回 Expand 展开差（乘积形状不展开不归零）。 */
    private fun sameExpr(engine: SymjaEngine, a: IExpr, b: IExpr): Boolean {
        if (F.eval(F.Subtract(a, b)).isZero) return true
        val expanded = engine.evaluateOrNull(engine.parseOrNull("Expand(($a)-($b))")) ?: return false
        return expanded.isZero
    }
}
