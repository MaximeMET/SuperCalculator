package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr
import org.matheclipse.core.interfaces.IPattern

/**
 * 「基本积分表」规则包。
 *
 * 表本身放在 `engine/src/main/resources/rules/integrals.json`，不写死在代码里：
 * 往仓库加一条规则，App 下次打包就多认一类题，不用改 Kotlin。
 *
 * 一条规则长这样：
 *
 *     {"id": "sec", "match": "Sec(u_)", "result": "Log(Sec(u_)+Tan(u_))",
 *      "note": "基本积分公式：∫sec x dx = ln|sec x + tan x|"}
 *
 * [match] 里的 `u_` 是通配符（整串匹配，不做子表达式替换）；[result] 是把
 * 通配符代进去之后的原函数模板。**只做直接套公式**：绑定的表达式必须就是
 * 积分变量本身（或与它无关的常量），复合情形（f(kx+b) 之类）留给换元 / 分部，
 * 免得基本表抢在它们前面把过程变浅。
 *
 * 数据里的规则不是"信就完了"：[IntegrateSteps] 拿到 [result] 之后照样做
 * 数值求导回验（F' 与被积函数在采样点上一致），验不过的规则直接跳过——
 * 仓库里写错的公式不会污染所有人的结果页。
 */
object IntegralRulePack {

    private const val RESOURCE = "/rules/integrals.json"

    data class Rule(
        val id: String,
        val match: String,
        val result: String,
        val note: String,
    )

    private var cached: List<Rule>? = null

    /**
     * 规则表：手动更新包里有就用更新包，否则用内置资源。
     * 读取失败返回空表：宁可不标注，也不能崩。
     */
    val rules: List<Rule>
        get() = cached ?: load().also { cached = it }

    /** 更新包变化后让缓存失效（见 [RulePacks]）。 */
    internal fun invalidate() {
        cached = null
    }

    private fun load(): List<Rule> {
        RulePacks.section("integrals")?.let { items ->
            runCatching { parseRules(items) }.getOrNull()?.let { return it }
        }
        return try {
            val text = IntegralRulePack::class.java.getResourceAsStream(RESOURCE)
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
                match = MiniJson.asString(item["match"], "match"),
                result = MiniJson.asString(item["result"], "result"),
                note = MiniJson.asString(item["note"], "note"),
            )
        }

    /**
     * 把规则套到 [expr] 上；整串匹配不上返回 null。
     *
     * 通配符绑定的表达式必须与 `x` 无关，或者就是 `x` 本身——
     * 这一条把"直接套公式"和"换元 / 分部"分开。
     */
    fun apply(engine: SymjaEngine, expr: IExpr, rule: Rule, variable: String): IExpr? {
        val pattern = engine.parseOrNull(rule.match) ?: return null
        val bindings = LinkedHashMap<String, IExpr>()
        if (!matchWhole(pattern, expr, bindings)) return null
        val x = engine.symbol(variable)
        for (bound in bindings.values) {
            if (!bound.equals(x) && !bound.isFree(x)) return null
        }
        val code = instantiate(rule.result, bindings)
        return engine.parseOrNull(code)
    }

    /** 整串匹配：`u_` 绑定子表达式，其余节点要求头与参数逐个相同。 */
    internal fun matchWhole(pattern: IExpr, expr: IExpr, bindings: MutableMap<String, IExpr>): Boolean =
        RulePatterns.matchWhole(pattern, expr, bindings)

    /** 把模板里的 `u_` 换成绑定的表达式（字符串层替换，结果包一层括号）。 */
    private fun instantiate(template: String, bindings: Map<String, IExpr>): String =
        RulePatterns.instantiate(template, bindings)
}
