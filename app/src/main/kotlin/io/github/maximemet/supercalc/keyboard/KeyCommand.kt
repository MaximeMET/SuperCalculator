package io.github.maximemet.supercalc.keyboard

/**
 * 键盘按键背后的编辑器动作。
 *
 * 这份分类和每条 [KeyCommand.code] 都不是我们自己定的，而是从参考 App 的
 * 改版 MathQuill（`assets/edit/Mathbot Editor_files/bundle.min.js`）里逐条读出来的。
 * 那里每个按键命令长这样：
 *
 * ```js
 * y = { sin: { code: "\\sin{}" }, ... }
 * w.type = OP_CMD; w.actions = [a(w.code)]        // a() = write()
 * y.sin.actions.push(c(keyMapping.Left))          // c() = keystroke()
 * ```
 *
 * 也就是说：`write(命令串)` 之后再敲若干次左方向键，把光标退回到第一个空槽。
 * 四个类型对应四种不同的插入方式，不能合并。
 */
enum class CommandType {
    /** 直接把字符打进编辑器，不走任何命令。 */
    NOOP,

    /** 插入一个运算符/函数命令，光标按 [KeyCommand.cursorBack] 退回空槽。 */
    OP,

    /** 插入一个带块结构的模板，比如根号、分式、积分上下限。 */
    BLOCK,

    /** 光标移动、清空、撤销这类操作，不影响公式内容。 */
    CURSOR,

    /** 「公式」页的函数模板。 */
    FORMULA_BANK,
}

/**
 * 一条按键动作。
 *
 * @param type       动作类型
 * @param code       送进编辑器的命令串（MathQuill 语法）
 * @param display    参考实现里给这个命令准备的展示形态，没写就是没有
 * @param cursorBack 插入后光标要往左退几次
 */
data class KeyCommand(
    val type: CommandType,
    val code: String,
    val display: String? = null,
    val cursorBack: Int = 0,
)
