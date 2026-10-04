package io.github.maximemet.supercalc.engine

/**
 * 结果页「解决过程」的一条步骤。
 *
 * [key] 给前端做样式 / 调试用，[label] 是显示在步骤上的中文名，
 * [lines] 逐行内容：以 `T:` 开头的是纯文本，其余按 LaTeX 排版。
 */
data class ProcessStep(val key: String, val label: String, val lines: List<String>)

/**
 * 步骤 -> 结果页 JSON（`{"steps":[{order,key,label,lines}, ...]}`）。
 *
 * 引擎模块是纯 JVM、不依赖 Android 的 `org.json`，所以这里自己转义；
 * 结果页那边拿到的是同一份契约。
 */
object ProcessSteps {

    /** 没有步骤时返回 null，结果页据此不显示过程区。 */
    fun toJson(steps: List<ProcessStep>): String? {
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
