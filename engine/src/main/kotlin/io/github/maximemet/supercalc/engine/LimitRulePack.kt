package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IExpr

/**
 * 「等价无穷小替换」规则包：`u→0` 时 `match` 形状可以换成 `result` 模板。
 *
 * 表放在 `engine/src/main/resources/rules/equivalents.json`，加一条规则不用改代码。
 * 用法见 [LimitSteps]：命中之后**内层 u 还必须确实趋于 0**（引擎判极限），
 * 替换完的极限也要和最终值对拍——验不过就不展示。
 */
object LimitRulePack {

    private const val RESOURCE = "/rules/equivalents.json"

    data class Rule(
        val id: String,
        val match: String,
        val result: String,
        val note: String,
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
        RulePacks.section("equivalents")?.let { items ->
            runCatching { parseRules(items) }.getOrNull()?.let { return it }
        }
        return try {
            val text = LimitRulePack::class.java.getResourceAsStream(RESOURCE)
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
     * 命中一条规则：返回 (绑定的 u, 替换后的表达式, 文案)，没命中返回 null。
     *
     * `u` 之外的占位符（`a_`、`b_` 这类参数）必须与极限变量无关：`a^u-1 ~ u·ln a`
     * 只在 `a` 是常数时成立，`(1+x)^x-1` 这种底数也带 x 的形状直接不认。
     */
    fun apply(
        engine: SymjaEngine,
        expr: IExpr,
        rule: Rule,
        variable: String,
    ): Triple<IExpr, IExpr, String>? {
        val pattern = engine.parseOrNull(rule.match) ?: return null
        val bindings = LinkedHashMap<String, IExpr>()
        if (!RulePatterns.matchWhole(pattern, expr, bindings)) return null
        val bound = bindings["u"] ?: return null
        val x = engine.symbol(variable)
        for ((name, value) in bindings) {
            if (name == "u") continue
            if (!value.isFree(x)) return null
        }
        val replacementCode = RulePatterns.instantiate(rule.result, bindings)
        val replacement = engine.parseOrNull(replacementCode) ?: return null
        return Triple(bound, replacement, RulePatterns.fillNote(rule.note, bindings))
    }
}
