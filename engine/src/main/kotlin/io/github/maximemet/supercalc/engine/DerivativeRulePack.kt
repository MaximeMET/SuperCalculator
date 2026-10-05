package io.github.maximemet.supercalc.engine

import org.matheclipse.core.interfaces.IExpr

/**
 * 「基本求导公式」规则包。
 *
 * 表放在 `engine/src/main/resources/rules/derivatives.json`，加一条公式不用改 Kotlin：
 *
 *     {"id": "sin", "match": "Sin(u_)", "result": "Cos(u_)",
 *      "latex": "\\cos\\left({u}\\right)", "note": "基本求导公式：(sin u)' = cos u"}
 *
 * 约定：
 *  - `u` 是「内层函数」——命中之后由 [DerivativeSteps] 决定要不要再接链式法则的 `u'`；
 *  - 其余占位符（`a_` 这类）必须是**与自变量无关的常数**，否则整条不认
 *    （`log_a u` 的底数含 x 就不是这个公式）；
 *  - `latex` 是写进步骤的公式模板，`{u}`/`{a}` 会被换成实际表达式；缺省时用引擎
 *    自己排的 LaTeX。之所以不用引擎排版，是要和原来的手写样式逐字符保持一致。
 *
 * 规则本身不"信就完了"：整棵求导树拼完，[DerivativeSteps] 仍会把结果和
 * `Diff(原式, x)` 做多采样点数值对拍，对不上就不出过程。
 */
object DerivativeRulePack {

    private const val RESOURCE = "/rules/derivatives.json"

    data class Rule(
        val id: String,
        val match: String,
        val result: String,
        val latex: String?,
        val note: String,
    )

    /** 一条命中的公式：内层表达式、外层导数、写进步骤的公式串。 */
    class Hit(
        val inner: IExpr,
        val derivative: IExpr,
        val latex: String,
    )

    private var cached: List<Rule>? = null

    /** 规则表：手动更新包里有就用更新包，否则用内置资源。读取失败返回空表。 */
    val rules: List<Rule>
        get() = cached ?: load().also { cached = it }

    /** 更新包变化后让缓存失效（见 [RulePacks]）。 */
    internal fun invalidate() {
        cached = null
    }

    private fun load(): List<Rule> {
        RulePacks.section("derivatives")?.let { items ->
            runCatching { parseRules(items) }.getOrNull()?.let { return it }
        }
        return try {
            val text = DerivativeRulePack::class.java.getResourceAsStream(RESOURCE)
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
                latex = (item["latex"] as? String)?.takeIf { it.isNotBlank() },
                note = MiniJson.asString(item["note"], "note"),
            )
        }

    /**
     * 把规则套到 [expr]（一个以 x 为自变量的函数节点）上；没命中返回 null。
     *
     * 命中条件：整串匹配 + `u` 绑到了东西 + 其余参数与 [x] 无关。
     */
    fun apply(engine: SymjaEngine, expr: IExpr, x: IExpr): Hit? {
        for (rule in rules) {
            val pattern = engine.parseOrNull(rule.match) ?: continue
            val bindings = LinkedHashMap<String, IExpr>()
            if (!RulePatterns.matchWhole(pattern, expr, bindings)) continue
            val inner = bindings["u"] ?: continue
            val constantsOk = bindings.all { (name, value) ->
                name == "u" || (value.isFree(x) && !value.equals(x))
            }
            if (!constantsOk) continue
            val derivative = engine.parseOrNull(RulePatterns.instantiate(rule.result, bindings))
                ?: continue
            val latex = rule.latex?.let { template ->
                RulePatterns.fillLatex(template, bindings, engine)
            } ?: engine.toExactLatex(derivative) ?: continue
            return Hit(inner, derivative, latex)
        }
        return null
    }
}
