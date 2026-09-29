package io.github.maximemet.supercalc.engine

/**
 * 送到 Symja 求值的表达式模板。
 *
 * 这些模板决定了「输入什么 => 引擎算什么」，一旦改动就会影响结果，
 * 因此与参考实现保持逐字一致。
 */
object MethodConsts {

    /** 结果分隔符：`精确结果 $$ 数值结果`。WebView 侧据此分段。 */
    const val DIVIDER = "$$"

    const val SYMJA_DIFF = "Diff(%s,%s)"
    const val SYMJA_EXPAND = "Expand(%s)"
    const val SYMJA_FACTOR = "Factor(%s)"
    const val SYMJA_INTEGRATE = "Integrate(%s,%s)"
    const val SYMJA_LATEX = "TexForm(%s)"
    const val SYMJA_NUMERIC = "N(%s)"
    const val SYMJA_SOLVE = "Solve(%s,%s)"
    const val SYMJA_SOLVE2 = "Solve({%s},%s)"
    const val SYMJA_SOLVEINEQ = "SolveInequality(%s,%s)"
    const val SYMJA_SOLVEINEQ2 = "SolveSystemInequality({%s},%s)"

    /** 定积分原样送入（表达式里已经带 NIntegrate 了）。 */
    const val SYMJA_DINTE = "%s"

    /**
     * 数值化模板（带精度）。
     *
     * 参考实现用的是 `N(%s)`——那是 2016 年版 Symja 的行为，有理数会按机器精度展开：
     * 基准 App 里 `1/3` 实测输出 `0.3333333333`（10 位，跟随「保留小数位」设置）。
     *
     * 现代 Symja 对有理数默认只给 6 位有效数字（`N(1/3)` → `0.333333`），
     * 直接用会让「保留小数位」这个设置完全失效。所以这里显式索要位数，
     * 取 `precision + 6` 与 17 位中的较大值——17 位足以复现机器精度下的四舍五入结果。
     */
    fun numeric(formula: String, precision: Int): String =
        "N(%s,%d)".format(formula, maxOf(precision + 6, 17))
}

/**
 * 历史记录里的操作类型编号，与数据库中 `supercalc.type` 列一一对应。
 *
 * 保持这些数值不变，才能直接读取旧版 App 的历史数据库。
 */
object RecordType {
    const val NORMAL = 0
    const val ON_STOP = 1
    const val ON_NEWLINE = 2
    const val HAS_RESULT = 5
    const val INTEGRATE = 10
    const val DIFF = 11
    const val EXPAND = 12
    const val FACTOR = 13
    const val DINTE = 14
    const val CALC = 15
    const val LIMIT = 19
    const val SOLVE = 21
    const val SOLVE2 = 22
    const val SOLVEINEQ = 23
    const val SOLVEINEQ2 = 24
    const val DRAW = 25
    const val NO_USE = 99
    const val GAME24 = 101
}
