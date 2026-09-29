package io.github.maximemet.supercalc.engine

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * LaTeX 结果的文本后处理。
 *
 * 这几条规则看着琐碎，但直接决定「复刻版和原版显示是否一模一样」，
 * 所以每一条都按参考实现的行为等价重写，并用测试锁住。
 */
object LatexText {

    private val FLOATING_POINT = Regex("[0-9]+\\.[0-9]+")
    private val SCIENTIFIC_NOTATION = Regex("([0-9]+\\.?[0-9]+)E(-?[0-9]+)")
    private val ONE_ARG_FUNCTIONS = Regex("(log|ln|sin|cos|tan|arctan|arcsin|arccos)")

    /**
     * 把结果里的每个小数按 [fix] 位四舍五入，并去掉多余的尾零。
     *
     * 只处理 `数字.数字` 形式，整数不动——所以 `\frac{22}{7}` 这类
     * 结构不会被误伤。
     */
    fun toFixPoint(text: String, fix: Int): String =
        FLOATING_POINT.replace(text) { m ->
            val rounded = BigDecimal(m.value).setScale(fix, RoundingMode.HALF_UP)
            replaceTailZeros(rounded.toPlainString())
        }

    /**
     * 科学计数法改写成 LaTeX 乘法形式：`1.5E-7` -> `1.5\cdot10^{-7}`。
     *
     * 这样长小数不会在窄屏上被撑爆。
     */
    fun withoutScientificNotation(text: String): String =
        SCIENTIFIC_NOTATION.replace(text) { m ->
            "${m.groupValues[1]}\\cdot10^{${m.groupValues[2]}}"
        }

    /** 去掉引擎输出整体包裹的一对引号。 */
    fun replaceQuotes(str: String): String =
        if (str.length >= 2 && str.startsWith("\"") && str.endsWith("\"")) {
            str.substring(1, str.length - 1)
        } else {
            str
        }

    /**
     * 去掉小数尾部的零。
     *
     * `1.500` -> `1.5`；`1.000` -> `1.0`（保留一位，保持"这是小数"的观感）。
     */
    fun replaceTailZeros(str: String, trimPoint: Boolean = false): String {
        if (!str.contains(".") || str.contains("e")) return str
        var s = str.replace(Regex("0+$"), "")
        if (s == "-0.") s = "0."
        if (s.endsWith(".")) {
            return if (trimPoint) s.dropLast(1) else "${s}0"
        }
        return s
    }

    /**
     * 把单参数函数的花括号参数改成圆括号。
     *
     * 引擎输出 `\sin{x}`，渲染成 `\sin(x)` 更符合计算器的视觉习惯。
     */
    fun replaceFunctionBracket(str: String): String {
        val normalized = str.replace(Regex("\\s+"), " ")
        val sb = StringBuilder(normalized)
        val indices = mutableListOf<Int>()
        for (m in ONE_ARG_FUNCTIONS.findAll(normalized)) {
            var end = m.range.last + 1
            if (end < normalized.length && normalized[end] == '{') {
                indices.add(end)
                var depth = 1
                while (depth > 0) {
                    end++
                    if (end >= normalized.length) break
                    when (normalized[end]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    if (depth == 0) indices.add(end)
                }
            }
        }
        indices.forEach { i ->
            if (i in sb.indices) {
                when (sb[i]) {
                    '{' -> sb[i] = '('
                    '}' -> sb[i] = ')'
                }
            }
        }
        return sb.toString()
    }
}
