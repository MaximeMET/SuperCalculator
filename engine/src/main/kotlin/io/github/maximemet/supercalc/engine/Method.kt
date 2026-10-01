package io.github.maximemet.supercalc.engine

/**
 * 用户可以点击触发的运算动作。
 *
 * 三个属性都来自参考实现并且**不能随意改动**：
 *  - [typeCode] 写进历史库的类型编号
 *  - [color]    按钮底色（ARGB 有符号 int）
 *  - [template] 送给 Symja 的表达式模板，null 表示直接送当前表达式
 */
enum class Method(
    val key: String,
    val label: String,
    val typeCode: Int,
    val color: Int,
    val template: String?,
) {
    Calc("Calc", "继续计算(耗时较长)", RecordType.CALC, -1274262, null),

    /**
     * 极限的「计算结果」按钮。
     *
     * 参考实现这一格挂的是 `RawMethod`（`SymjaManager.getMethods()` 里
     * `getMethod(NumericMethod.KEY, RawMethod.class)`），公式**原样**送引擎，
     * 外面不套 `N(...)`——这是能不能算出极限的关键：
     * Symja 的数值模式会把 `x->0` 里的 0 变成 0.0，`N(Limit(...))` 的极限规则
     * 匹配不上，`lim x→0 sin(x)/x` 会直接得到 NaN。
     * 类型码 19、按钮颜色沿用 RawMethod 的默认色（与定积分同色）。
     */
    Limit("limit", "计算结果", RecordType.LIMIT, -6191016, null),

    /**
     * 参考实现里的 `NumericMethod`（模板 `N(%s)`）。
     *
     * 注意：原版其实**从来没有实例化过它**——极限分支用的是 `NumericMethod.KEY`
     * 配 `RawMethod.class`，缓存里存下来的始终是那个 RawMethod（见 [Limit]）。
     * 这里保留只为和参考实现逐条对应，新代码不要用它。
     */
    Numeric("Numeric", "计算结果", RecordType.NO_USE, -1274262, MethodConsts.SYMJA_NUMERIC),
    Integrate("Integrate", "积分", RecordType.INTEGRATE, -9591553, MethodConsts.SYMJA_INTEGRATE),
    Derivative("Diff", "求导", RecordType.DIFF, -16558, MethodConsts.SYMJA_DIFF),
    Expand("Expand", "多项式展开", RecordType.EXPAND, -8137275, MethodConsts.SYMJA_EXPAND),
    Decompose("Factor", "多项式分解", RecordType.FACTOR, -4427, MethodConsts.SYMJA_FACTOR),
    Solve("Solve", "求解方程", RecordType.SOLVE, -8066823, MethodConsts.SYMJA_SOLVE),
    Solve2("Solve2", "求解方程组", RecordType.SOLVE2, -13784321, MethodConsts.SYMJA_SOLVE2),
    SolveIneq("SolveIneq", "解不等式", RecordType.SOLVEINEQ, -12202626, MethodConsts.SYMJA_SOLVEINEQ),
    SolveIneq2("SolveIneq2", "解不等式组", RecordType.SOLVEINEQ2, -13784321, MethodConsts.SYMJA_SOLVEINEQ2),
    DInte("DInte", "定积分", RecordType.DINTE, -6191016, MethodConsts.SYMJA_DINTE),
    Draw("Draw", "绘制图像", RecordType.DRAW, -1665884, null),
    ;

    /** 模板里的 `%s` 个数：2 个表示要带上未知数参数。 */
    private val placeholderCount: Int get() = template?.count { it == '%' } ?: 0

    val needsUnknown: Boolean get() = placeholderCount > 1

    /**
     * 生成送给 Symja 的表达式。
     *
     * 模板为 null 的动作（继续计算、绘制图像）直接使用当前表达式。
     */
    fun buildFormula(formula: String, unknown: String): String = when (this) {
        Numeric -> MethodConsts.numeric(formula, EngineSettings.precision)
        // RawMethod 语义：原样送。（模板为 null 时下面 else 分支也会走到这里，
        // 单独写出来是为了钉住「极限不能套 N()」这条规矩。）
        Limit -> formula
        else -> when (placeholderCount) {
            0 -> formula
            1 -> String.format(template!!, formula)
            else -> String.format(template!!, formula, unknown)
        }
    }

    companion object {
        /**
         * 「绘制图像」交给绘图页的公式串。
         *
         * 参考实现写的是 `上一行 + "\n" + 当前行`——注意这里的 `\n` 是**字面反斜杠加 n**，
         * 不是换行符。它被当作多函数分隔符用：绘图页按它切开，逐段当函数画。
         * 单行输入时前面会多出一段空串，切完丢掉即可。
         */
        fun drawFormula(formula: String, lastFormula: String = ""): String =
            lastFormula + "\\n" + formula

        /** 绘图页用的正则：切开 [drawFormula] 的结果，丢掉空段。 */
        private val DRAW_SEPARATOR = Regex("""[*]*\\n[*]*""")

        /** 把 [drawFormula] 的结果切回成要画的函数列表。 */
        fun splitDrawFormula(text: String): List<String> =
            DRAW_SEPARATOR.split(text).filter { it.isNotEmpty() }

        /**
         * 换行分隔符：`\newline` 命令的 symja 输出，就是「反斜杠 + n」两个字符。
         *
         * 对应参考实现的 `SettingParams.getNewlineStr()`。
         */
        const val NEWLINE = "\\n"

        private val NEWLINE_SEPARATOR = Regex("""\\n""")

        /**
         * 方程组/不等式组用的完整输入。
         *
         * 参考实现 `AbstractMethod.getAllFormula()`：把换行之前的每一段去掉首尾标记，
         * 用 `, ` 连起来，末尾接上当前行。
         */
        fun allFormula(lastFormula: String, formula: String): String {
            val builder = StringBuilder()
            NEWLINE_SEPARATOR.split(lastFormula).forEach { raw ->
                var field = raw
                // 参考实现这里剥的是 Marker.ANY_MARKER，也就是 '*'（编辑器给行首插的
                // 占位标记）。我们自己的编辑器不产出它，保留这一步只为逐条对应原版处理。
                if (field.startsWith(MARKER)) field = field.substring(1)
                if (field.endsWith("*")) field = field.dropLast(1)
                if (field.isNotEmpty()) builder.append(field).append(", ")
            }
            return builder.append(formula).toString()
        }

        /**
         * 参考实现 `AbstractMethod.getUnknowns()`：按 x、y、z 的顺序列出输入里出现的未知数。
         */
        fun unknowns(input: String): String =
            listOf("x", "y", "z").filter { input.contains(it) }
                .joinToString(separator = ",", prefix = "{", postfix = "}")

        /** 参考实现 `Marker.ANY_MARKER`：行首占位标记。 */
        private const val MARKER = "*"

        /** 定积分只给数值解。 */
        val numericOnly = listOf(Numeric)

        /** 高次多项式：可分解、可展开、可积分、可求导。 */
        val polynomialMethods = listOf(Decompose, Expand, Integrate, Derivative)

        /** 含未知数的函数式：可积分、可求导。 */
        val functionMethods = listOf(Integrate, Derivative)

        /** 等式：可求解。 */
        val solveMethods = listOf(Solve)
    }
}
