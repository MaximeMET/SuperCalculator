package io.github.maximemet.supercalc.engine

/**
 * 极简 JSON 解析器，只为读「规则包」这类我们自己写的小文件。
 *
 * 引擎模块是纯 JVM，没有 org.json / 序列化库，也不想为几 KB 的规则文件引依赖。
 * 支持标准 JSON 的对象/数组/字符串/数字/布尔/null，够用即可；不支持的输入
 * 一律抛 [IllegalArgumentException]，由调用方兜底。
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "JSON 尾部有多余内容（位置 ${parser.position}）" }
        return value
    }

    private class Parser(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(position < text.length) { "JSON 意外结束" }
            return when (text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return out
                    }
                    else -> throw IllegalArgumentException("JSON 对象里缺少 , 或 }（位置 $position）")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return out
            }
            while (true) {
                out += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return out
                    }
                    else -> throw IllegalArgumentException("JSON 数组里缺少 , 或 ]（位置 $position）")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(position < text.length) { "JSON 字符串没有收尾引号" }
                val ch = text[position++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        require(position < text.length) { "JSON 转义不完整" }
                        when (val esc = text[position++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(position + 4 <= text.length) { "JSON \\u 转义不完整" }
                                val hex = text.substring(position, position + 4)
                                position += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("不认识的转义 \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            while (position < text.length &&
                (text[position].isDigit() || text[position] in "+-.eE")
            ) {
                position++
            }
            require(position > start) { "JSON 里不是合法值（位置 $start）" }
            return text.substring(start, position).toDouble()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, position)) { "JSON 里的 $literal 写错了（位置 $position）" }
            position += literal.length
            return value
        }

        private fun peek(): Char {
            require(position < text.length) { "JSON 意外结束" }
            return text[position]
        }

        private fun expect(ch: Char) {
            require(position < text.length && text[position] == ch) {
                "JSON 期望 '$ch'（位置 $position）"
            }
            position++
        }
    }

    // ---------- 取值助手 ----------

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw IllegalArgumentException("JSON 节点不是对象")

    fun asArray(value: Any?): List<Any?> =
        value as? List<Any?> ?: throw IllegalArgumentException("JSON 节点不是数组")

    fun asString(value: Any?, field: String): String =
        value as? String ?: throw IllegalArgumentException("JSON 字段 $field 不是字符串")
}
