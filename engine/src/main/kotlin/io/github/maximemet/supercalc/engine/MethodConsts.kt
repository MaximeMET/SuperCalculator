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
     * 数值化模板。
     *
     * 就用最朴素的两参数形式。锁定到 2016 版 Symja 之后，有理数会按机器精度展开，
     * `1/3` 得到 `0.3333333333333333`，再经 [LatexText.toFixPoint] 按「保留小数位」
     * 截断成 `0.3333333333`——与基准 App 实测值逐字符一致。
     *
     * 曾经的教训：为了让新版 Symja 也产出同样位数，这里写过 `N(%s,%d)` 的精度 hack，
     * 结果在正确版本上反而输出 `3.3333333333e-1` 这种科学计数法。
     * 换版本才是正解，改模板不是。
     */
    fun numeric(formula: String, precision: Int): String = "N(%s)".format(formula)
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
