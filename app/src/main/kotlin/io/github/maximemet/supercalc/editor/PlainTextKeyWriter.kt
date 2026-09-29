package io.github.maximemet.supercalc.editor

import android.widget.EditText
import io.github.maximemet.supercalc.keyboard.KeyCommand

/**
 * 把键盘命令写进当前这个「纯文本占位编辑器」。
 *
 * 参考实现里命令串是 MathQuill 语法（`\sin{}`、`\frac`…），MathQuill 自己会把
 * 它渲染成公式；我们这一步还没接 WebView 编辑器，所以先把每条命令翻译成
 * Symja 能认的等效文本，光标落到第一个空槽。
 *
 * 这张表是过渡用的：等 MathQuill 换上来之后，[KeyCommand.code] 会原样交给编辑器，
 * 这里只需要保留 [caretBack] 那部分语义。
 */
class PlainTextKeyWriter(private val editor: EditText) {

    private data class Plain(val text: String, val caretBack: Int)

    fun write(command: KeyCommand) {
        if (command.code == "\\newline{}") {
            insert("\n", 0)
            return
        }
        val plain = TABLE[command.code]
        if (plain != null) {
            insert(plain.text, plain.caretBack)
            return
        }
        // 表里没有的（数字、字母、比较号这些）就是直接打字
        insert(command.code, command.cursorBack)
    }

    /** 插入文本并把光标往回退 [caretBack] 个字符。 */
    private fun insert(text: String, caretBack: Int) {
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        editor.text.replace(from, to, text)
        val caret = (from + text.length - caretBack).coerceIn(from, from + text.length)
        editor.setSelection(caret)
    }

    companion object {
        private val TABLE: Map<String, Plain> = buildMap {
            fun put(code: String, text: String, caretBack: Int) {
                this[code] = Plain(text, caretBack)
            }

            // 二元运算符
            put("\\times", "*", 0)
            put("\\slash", "/", 0)
            put("\\degree", "°", 0)
            put("\\infty", "infinity", 0)
            put("\\pi", "pi", 0)

            // 一元函数：插进去一个空括号，光标停在括号里
            for (name in listOf("sin", "cos", "tan", "arcsin", "arccos", "arctan", "ln")) {
                put("\\$name{}", "$name()", 1)
            }
            put("\\left|{}\\right|", "abs()", 1)

            // 带前缀参数的
            put("\\log_{}{}", "log(,)", 2)
            put("\\log_{2}{}", "log(2,)", 1)
            put("\\log_{10}{}", "log(10,)", 1)

            // 双参数函数
            put("\\gcd({},{})", "gcd(,)", 2)
            put("\\lcm({},{})", "lcm(,)", 2)
            put("\\ap{}{}", "ap(,)", 2)
            put("\\cp{}{}", "cp(,)", 2)

            // 结构模板
            put("^", "^", 0)
            put("/", "/", 0)
            put("(", "(", 0)
            put(")", ")", 0)
            put("\\sqrt[]{}", "sqrt()", 1)
            put("\\dms{}{}{}", "dms(,,)", 4)
            put("\\int_{}^{}{}d{x}", "NIntegrate(, {x, 0, 1})", 13)
            put("\\lim_{}^{}{}", "Limit(, x -> 0)", 10)

            // 公式页
            put("\\fLinear{}{}", "fLinear(,)", 2)
            put("\\fInverse{}", "fInverse()", 1)
            put("\\fnQuadratic{}{}{}", "fnQuadratic(,,)", 3)
            put("\\fQuadratic{}{}{}", "fQuadratic(,,)", 3)
            put("\\fExponential{}", "fExponential()", 1)
            put("\\fLog{}", "fLog()", 1)
            put("\\fsCircle{}{}{}", "fsCircle(,,)", 3)
            put("\\fCircle{}{}{}", "fCircle(,,)", 3)
            put("\\fsEllipse{}{}", "fsEllipse(,)", 2)
            put("\\fEllipse{}{}{}{}", "fEllipse(,,,)", 4)
            put("\\fsHyperbola{}{}", "fsHyperbola(,)", 2)
            put("\\fHyperbola{}{}{}{}", "fHyperbola(,,,)", 4)
            put("\\fParabola{}", "fParabola()", 1)
        }
    }
}
