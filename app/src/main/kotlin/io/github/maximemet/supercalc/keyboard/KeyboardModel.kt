package io.github.maximemet.supercalc.keyboard

import io.github.maximemet.supercalc.keyboard.CommandType.BLOCK
import io.github.maximemet.supercalc.keyboard.CommandType.FORMULA_BANK
import io.github.maximemet.supercalc.keyboard.CommandType.NOOP
import io.github.maximemet.supercalc.keyboard.CommandType.OP

/**
 * 一个按键。
 *
 * @param symbol 参考实现里的按键标识（`assets/keyboard` 里的 symbol 字段），同时是埋点的键
 * @param icon   图标资源名。图标素材要全部替换，所以这里只留名字，不放图
 * @param label  键盘上显示的文字
 * @param command 按下之后对编辑器做什么
 */
data class KeyItem(
    val symbol: String,
    val icon: String,
    val label: String,
    val command: KeyCommand?,
) {
    val isEmpty: Boolean get() = symbol.isEmpty()
}

/**
 * 键盘上的一页。
 *
 * 前三页是 5 列网格；「公式」页只有 1 列，左边还挂一条文字说明栏，
 * 每个格子占几行由 [labelRows] 的权重决定（参考实现里是 LinearLayout 的 weight）。
 */
data class KeyboardPage(
    val keys: List<KeyItem>,
    val columns: Int,
    val labelRows: List<Pair<String, Int>>? = null,
)

/**
 * 四页键盘的完整数据。
 *
 * 顺序、空位、标签都照抄 `assets/keyboard`，命令串照抄改版 MathQuill 的命令表。
 * 页与页串联成一条长条，靠上下滑动浏览，左侧书签直接跳到对应页。
 */
object KeyboardModel {

    /** 每页的网格列数。参考实现写的是 `keyboardGridColumnCnt = 5`。 */
    const val COLUMNS = 5

    /** 行高系数：格子高度 = 键盘宽度 / 6。参考实现写的是 `keyboardGridRatio`。 */
    const val ROW_HEIGHT_RATIO = 6

    /** 公式页只有 1 列。 */
    const val FUNCTION_COLUMNS = 1

    private fun text(symbol: String, icon: String, label: String = symbol) =
        KeyItem(symbol, icon, label, KeyCommand(NOOP, symbol))

    private fun empty() = KeyItem("", "", "", null)

    private fun op(symbol: String, icon: String, label: String, code: String, cursorBack: Int = 0) =
        KeyItem(symbol, icon, label, KeyCommand(OP, code, cursorBack = cursorBack))

    /** 希腊字母：插入 `\theta` 这类命令（Symja 认这些符号名当变量）。 */
    private fun greek(name: String, icon: String) =
        KeyItem(name, icon, name, KeyCommand(OP, "\\$name"))

    private fun block(symbol: String, icon: String, label: String, code: String, cursorBack: Int = 0) =
        KeyItem(symbol, icon, label, KeyCommand(BLOCK, code, cursorBack = cursorBack))

    private fun bank(symbol: String, icon: String, display: String, code: String, cursorBack: Int) =
        KeyItem(symbol, icon, display, KeyCommand(FORMULA_BANK, code, display, cursorBack))

    /**
     * 工具栏上的「换行」键。
     *
     * 参考实现给它的命令是 `\newline{}`，而 mathquill 里这个命令的 symja 输出
     * 是字面量 `\n` 两个字符——引擎那边 [io.github.maximemet.supercalc.engine.EngineSettings.NEWLINE]
     * 用的也是它。
     */
    val newlineCommand = KeyCommand(CommandType.BLOCK, "\\newline{}")

    /** 第 1 页：基本运算。 */
    val page1: List<KeyItem> = listOf(
        op("log", "ic_keyboard_log", "log", "\\log_{}{}", cursorBack = 2),
        block("^", "ic_keyboard_exp", "^", "^"),
        block("sqrt", "ic_keyboard_sqrt", "√", "\\sqrt[]{}", cursorBack = 2),
        // 长按 frac 有额外行为（参考实现的 mLongKeySymbolSet 里只有它一个）
        block("frac", "ic_keyboard_frac", "／", "/"),
        op("/", "ic_keyboard_divide", "÷", "\\slash"),

        text("x", "ic_keyboard_x"),
        text("7", "ic_keyboard_7"),
        text("8", "ic_keyboard_8"),
        text("9", "ic_keyboard_9"),
        op("*", "ic_keyboard_multiply", "×", "\\times"),

        text("y", "ic_keyboard_y"),
        text("4", "ic_keyboard_4"),
        text("5", "ic_keyboard_5"),
        text("6", "ic_keyboard_6"),
        text("-", "ic_keyboard_minus", "−"),

        text("z", "ic_keyboard_z"),
        text("1", "ic_keyboard_1"),
        text("2", "ic_keyboard_2"),
        text("3", "ic_keyboard_3"),
        text("+", "ic_keyboard_plus"),

        block("(", "ic_keyboard_left_paren", "(", "("),
        block(")", "ic_keyboard_right_paren", ")", ")"),
        text("0", "ic_keyboard_0"),
        text(".", "ic_keyboard_dot"),
        text("=", "ic_keyboard_equal"),

        block("log2", "ic_keyboard_log2", "log₂", "\\log_{2}{}", cursorBack = 1),
        block("log10", "ic_keyboard_log10", "log₁₀", "\\log_{10}{}", cursorBack = 1),
        op("ln", "ic_keyboard_ln", "ln", "\\ln{}", cursorBack = 1),
        op("degree", "ic_keyboard_degree", "°", "\\degree"),
        block("dms", "ic_keyboard_dms", "°′″", "\\dms{}{}{}", cursorBack = 3),

        op("less", "ic_keyboard_less", "<", "<"),
        op("le", "ic_keyboard_le", "≤", "≤"),
        op("greater", "ic_keyboard_greater", ">", ">"),
        op("ge", "ic_keyboard_ge", "≥", "≥"),
        op("infinity", "ic_keyboard_inf", "∞", "\\infty"),

        op("pi", "ic_keyboard_pi", "π", "\\pi"),
        text("e", "ic_keyboard_e"),
        text("i", "ic_keyboard_i"),
        empty(),
        empty(),
    )

    /** 第 2 页：函数与高级运算。 */
    val page2: List<KeyItem> = listOf(
        op("sin", "ic_keyboard_sin", "sin", "\\sin{}", cursorBack = 1),
        op("cos", "ic_keyboard_cos", "cos", "\\cos{}", cursorBack = 1),
        op("tan", "ic_keyboard_tan", "tan", "\\tan{}", cursorBack = 1),
        op("lcm", "ic_keyboard_lcm", "公倍", "\\lcm({},{})", cursorBack = 2),
        op("gcd", "ic_keyboard_gcd", "公约", "\\gcd({},{})", cursorBack = 2),

        op("arcsin", "ic_keyboard_arcsin", "arcsin", "\\arcsin{}", cursorBack = 1),
        op("arccos", "ic_keyboard_arccos", "arccos", "\\arccos{}", cursorBack = 1),
        op("arctan", "ic_keyboard_arctan", "arctan", "\\arctan{}", cursorBack = 1),
        op("abs", "ic_keyboard_abs", "abs", "\\left|{}\\right|", cursorBack = 1),
        text("!", "ic_keyboard_factorial", "!"),

        block("int", "ic_keyboard_int", "∫", "\\int_{}^{}{}d{x}", cursorBack = 3),
        block("lim", "ic_keyboard_lim", "lim", "\\lim_{}^{}{}", cursorBack = 3),
        block("AP", "ic_keyboard_ap", "AP", "\\ap{}{}", cursorBack = 2),
        block("CP", "ic_keyboard_cp", "CP", "\\cp{}{}", cursorBack = 2),
        empty(),
    )

    /**
     * 第 3 页：变量。
     *
     * 除 a b c h k p 外补上常用的拉丁字母（s、u、v）与希腊字母（θ φ λ μ σ ω）——
     * 两排空着不好看，物理/高数里这些字母也常用。全部走 Symja 的符号名，
     * 可以当参数参与求导、积分、极限。
     */
    val page3: List<KeyItem> = listOf(
        text("a", "ic_keyboard_a"),
        text("b", "ic_keyboard_b"),
        text("c", "ic_keyboard_c"),
        text("h", "ic_keyboard_h"),
        text("k", "ic_keyboard_k"),
        text("p", "ic_keyboard_p"),
        text("s", "ic_keyboard_s"),
        text("u", "ic_keyboard_u"),
        text("v", "ic_keyboard_v"),
        greek("theta", "ic_keyboard_theta"),
        greek("phi", "ic_keyboard_phi"),
        greek("lambda", "ic_keyboard_lambda"),
        greek("mu", "ic_keyboard_mu"),
        greek("sigma", "ic_keyboard_sigma"),
        greek("omega", "ic_keyboard_omega"),
    )

    /** 第 4 页：函数模板。图标是公式文字，由 tools/make_keyboard_icons.py 生成。 */
    val page4: List<KeyItem> = listOf(
        bank("fLinear", "ic_keyboard_f_linear", "y=kx+b", "\\fLinear{}{}", 2),
        bank("fInverse", "ic_keyboard_f_inverse", "y=k/x", "\\fInverse{}", 1),
        bank("fnQuadratic", "ic_keyboard_f_normalquadratic", "y=ax²+bx+c", "\\fnQuadratic{}{}{}", 3),
        bank("fQuadratic", "ic_keyboard_f_quadratic", "y=a(x-h)²+k", "\\fQuadratic{}{}{}", 3),
        bank("fExponential", "ic_keyboard_f_exp", "y=a^x", "\\fExponential{}", 1),
        bank("fLog", "ic_keyboard_f_log", "y=logₐx", "\\fLog{}", 1),
        bank("fsCircle", "ic_keyboard_f_std_cir", "(x-a)²+(y-b)²=r²", "\\fsCircle{}{}{}", 3),
        bank("fCircle", "ic_keyboard_f_circle", "x²+y²+ax+by+c=0", "\\fCircle{}{}{}", 3),
        bank("fsEllipse", "ic_keyboard_f_std_ell", "x²/a²+y²/b²=1", "\\fsEllipse{}{}", 2),
        bank("fEllipse", "ic_keyboard_f_ellipse", "(x-k)²/a²+(y-h)²/b²=1", "\\fEllipse{}{}{}{}", 4),
        bank("fsHyperbola", "ic_keyboard_f_std_hyper", "x²/a²−y²/b²=1", "\\fsHyperbola{}{}", 2),
        bank("fHyperbola", "ic_keyboard_f_hyperbola", "(x-k)²/a²−(y-h)²/b²=1", "\\fHyperbola{}{}{}{}", 4),
        bank("fParabola", "ic_keyboard_f_parabola", "y²=2px", "\\fParabola{}", 1),
    )

    /**
     * 「公式」页左边的文字说明栏。
     *
     * 第二个数字是行权重：圆、椭圆、双曲线各占两行（都有一条标准式和一条一般式），
     * 权重加起来正好等于 13 个格子。顺序和文案照抄参考实现的 `math_keyboard_page4func.xml`。
     */
    private val functionLabels = listOf(
        "一元一次函数" to 1,
        "反比例函数" to 1,
        "一元二次函数" to 2,
        "指数函数" to 1,
        "对数函数" to 1,
        "圆" to 2,
        "椭圆" to 2,
        "双曲线" to 2,
        "抛物线" to 1,
    )

    val pages: List<KeyboardPage> = listOf(
        KeyboardPage(page1, COLUMNS),
        KeyboardPage(page2, COLUMNS),
        KeyboardPage(page3, COLUMNS),
        KeyboardPage(page4, FUNCTION_COLUMNS, functionLabels),
    )
}
