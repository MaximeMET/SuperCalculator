package io.github.maximemet.supercalc.engine

import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import org.matheclipse.core.interfaces.IExpr

/** 一种情形：需要同时成立的条件，例如 `["x > 0", "x < 1"]`。 */
typealias InequalityBranch = List<String>

/**
 * 一元不等式求解。
 *
 * 参考实现用的是分支自带的 `SolveInequality` / `SolveSystemInequality`，
 * 上游 Symja 里没有对应的东西（`Reduce` 也是未实现状态），所以这里按可观测
 * 行为自己写一份：
 *
 *  1. 把不等式化成 `f(x) REL 0`
 *  2. 取全部临界点——分子的零点（等号可能成立的地方）和分母的零点（断点）
 *  3. 相邻临界点之间各取一个采样点判号
 *  4. 把连续成立的区间并起来，每段翻译成一组合取条件
 *
 * 返回「列表套列表」：外层是若干情形（或），内层是同时成立的条件（且）。
 * Symja 的 TeX 转换器会把它排成
 * `\left(\begin{array}{c}…\end{array}\right)`，与基准逐字符一致。
 *
 * 拿不准的输入一律返回 null，交回原来的路径，不猜。
 */
object InequalitySolver {

    /** 比较方向。统一拿 `f(x) REL 0` 来判。 */
    private enum class Rel(val strict: Boolean) {
        LT(true), LE(false), GT(true), GE(false);

        fun holds(v: Double): Boolean = when (this) {
            LT -> v < 0.0
            LE -> v <= 0.0
            GT -> v > 0.0
            GE -> v >= 0.0
        }
    }

    /** 一个不等式的内部准备结果（分子分母、方向、临界点）。 */
    private class Prepared(
        val rel: Rel,
        /** 化成 `f(x) REL 0` 之后的 f（已通分）。 */
        val f: String,
        val numerator: String,
        val denominator: String,
        val zeros: List<IExpr>,
        val poles: List<IExpr>,
        val critical: List<Pair<Double, IExpr>>,
    )

    /** 穿线法取的一个点。 */
    class Probe(val point: Double, val value: Double, val holds: Boolean)

    /** 分段结果：成立的连续区间 + 每个临界点上是否成立（决定端点开闭）。 */
    private class Segments(val runs: List<Pair<Int, Int>>, val nodeOk: BooleanArray)

    /** 单个不等式的分析结果（解题步骤要用）。 */
    class Analysis internal constructor(
        val input: String,
        val normalized: String,
        val numerator: String,
        val denominator: String,
        val zeros: List<IExpr>,
        val poles: List<IExpr>,
        val critical: List<IExpr>,
        val probes: List<Probe>,
        val branches: List<InequalityBranch>,
    )

    /** 求解，解不出来返回 null。 */
    fun solve(engine: SymjaEngine, formula: String, unknown: String): List<InequalityBranch>? {
        val prepared = prepare(engine, formula, unknown) ?: return null
        val segments = runsFor(engine, listOf(prepared), prepared.critical, unknown) ?: return null
        return branchesOf(prepared.critical, segments, unknown)
    }

    /**
     * 不等式组：逐个化成 `f_i(x) REL 0`，在**所有**临界点的并集上分段，
     * 每个区间要求全部不等式同时成立。临界点上同样要求全部成立
     * （任何一个不等式在该点无定义就整点不算）。
     */
    fun solveSystem(
        engine: SymjaEngine,
        inequalities: List<String>,
        unknown: String,
    ): List<InequalityBranch>? {
        if (inequalities.size < 2) return null
        val prepared = inequalities.map { prepare(engine, it, unknown) ?: return null }
        val critical = mergeCritical(prepared.map { it.critical })
        if (critical.isEmpty()) return null
        val segments = runsFor(engine, prepared, critical, unknown) ?: return null
        return branchesOf(critical, segments, unknown)
    }

    /** 单个不等式的分析（给解题步骤用）。 */
    fun analyze(engine: SymjaEngine, formula: String, unknown: String): Analysis? {
        val prepared = prepare(engine, formula, unknown) ?: return null
        val critical = prepared.critical
        if (critical.isEmpty()) return null
        val n = critical.size
        val cellOk = BooleanArray(n + 1)
        val probes = mutableListOf<Probe>()
        for (i in 0..n) {
            val probe = samplePoint(critical, i, n)
            val value = engine.signAt(prepared.f, unknown, probe) ?: return null
            val holds = prepared.rel.holds(value)
            cellOk[i] = holds
            probes += Probe(probe, value, holds)
        }
        val nodeOk = BooleanArray(n) { i ->
            engine.signAt(prepared.f, unknown, critical[i].first)
                ?.let { prepared.rel.holds(it) } ?: false
        }
        val runs = buildRuns(cellOk, nodeOk, n) ?: return null
        val segments = Segments(runs, nodeOk)
        return Analysis(
            input = formula,
            normalized = prepared.f,
            numerator = prepared.numerator,
            denominator = prepared.denominator,
            zeros = prepared.zeros,
            poles = prepared.poles,
            critical = critical.map { it.second },
            probes = probes,
            branches = branchesOf(critical, segments, unknown),
        )
    }

    /**
     * 把「上一行 + 当前行」拆成一条条不等式。
     *
     * 编辑器的换行符是字面量 `\n`（两个字符），行首可能带一个 `*` 占位标记，
     * 与 `Method.allFormula` 的处理保持一致。
     */
    fun systemInputs(lastFormula: String, formula: String): List<String> {
        val lines = NEWLINE_SEPARATOR.split(lastFormula)
            .map { it.trim().trimStart('*').trimEnd('*').trim() }
            .filter { it.isNotEmpty() }
        return lines + listOf(formula)
    }

    private val NEWLINE_SEPARATOR = Regex("""\\n""")

    /**
     * 输入里出现的未知数（按 x、y、z 的顺序取第一个）。
     *
     * 用词边界匹配：`exp(x)>1` 里要认出 `x`，而 `exp` 里的 e/x/p 不算。
     */
    fun unknownOf(text: String): String =
        listOf("x", "y", "z").firstOrNull { SYMBOL_PATTERN(it).containsMatchIn(text) }
            ?: EngineSettings.unknown

    private fun SYMBOL_PATTERN(name: String) = Regex("(?<![A-Za-z])$name(?![A-Za-z])")

    // ---------- 准备与判定 ----------

    private fun prepare(engine: SymjaEngine, formula: String, unknown: String): Prepared? {
        val expr = engine.parseOrNull(formula) ?: return null
        if (!expr.isAST()) return null
        val ast = expr as IAST
        if (ast.size != 3) return null

        val rel = when {
            ast.isAST(F.Less) -> Rel.LT
            ast.isAST(F.LessEqual) -> Rel.LE
            ast.isAST(F.Greater) -> Rel.GT
            ast.isAST(F.GreaterEqual) -> Rel.GE
            else -> return null
        }

        // 绝对值先平方化：|u| REL c  <=>  u² REL c²（c ≥ 0），
        // 这样就能落回下面那套多项式判号。上游的 Solve 不会解 Abs。
        rewriteAbs(engine, ast)?.let { return prepare(engine, it, unknown) }

        val f = "(${ast.arg1()}-(${ast.arg2()}))"

        val numerator = engine.stringOf("Numerator(Together($f))") ?: return null
        val denominator = engine.stringOf("Denominator(Together($f))") ?: return null

        val zeros = engine.solveZeros(numerator, unknown) ?: return null
        val poles = engine.solveZeros(denominator, unknown) ?: return null

        // 全部临界点：分子零点 + 分母零点，按数值排序去重
        val critical = mutableListOf<Pair<Double, IExpr>>()
        for (root in zeros + poles) {
            val v = engine.numericValueOf(root.toString()) ?: return null
            if (critical.none { kotlin.math.abs(it.first - v) < 1e-12 }) {
                critical.add(v to root)
            }
        }
        critical.sortBy { it.first }
        if (critical.isEmpty()) return null

        return Prepared(rel, f, numerator, denominator, zeros, poles, critical)
    }

    /** 在给定临界点上分段，返回成立的连续区间（cell 下标）。 */
    private fun runsFor(
        engine: SymjaEngine,
        preparedList: List<Prepared>,
        critical: List<Pair<Double, IExpr>>,
        unknown: String,
    ): Segments? {
        val n = critical.size
        // 区间 i 夹在临界点 i-1 与 i 之间（0 表示负无穷端，n 表示正无穷端）
        val cellOk = BooleanArray(n + 1)
        for (i in 0..n) {
            val probe = samplePoint(critical, i, n)
            for (prepared in preparedList) {
                val value = engine.signAt(prepared.f, unknown, probe) ?: return null
                if (!prepared.rel.holds(value)) {
                    cellOk[i] = false
                    break
                }
                cellOk[i] = true
            }
        }
        // 临界点上必须所有不等式都成立（分母零点处无定义，永远不成立）
        val nodeOk = BooleanArray(n) { i ->
            preparedList.all { prepared ->
                engine.signAt(prepared.f, unknown, critical[i].first)
                    ?.let { prepared.rel.holds(it) } ?: false
            }
        }
        val runs = buildRuns(cellOk, nodeOk, n) ?: return null
        return Segments(runs, nodeOk)
    }

    /** 把区间并成若干段，翻译成条件。 */
    private fun branchesOf(
        critical: List<Pair<Double, IExpr>>,
        segments: Segments,
        unknown: String,
    ): List<InequalityBranch> {
        val n = critical.size
        val nodeOk = segments.nodeOk
        return segments.runs.map { (from, to) ->
            val conditions = mutableListOf<String>()
            if (from > 0) {
                conditions += bound(unknown, critical[from - 1].second, nodeOk[from - 1], lower = true)
            }
            if (to < n) {
                conditions += bound(unknown, critical[to].second, nodeOk[to], lower = false)
            }
            conditions
        }
    }

    /** 全部不等式的临界点并集，按数值排序去重。 */
    private fun mergeCritical(all: List<List<Pair<Double, IExpr>>>): List<Pair<Double, IExpr>> {
        val merged = mutableListOf<Pair<Double, IExpr>>()
        for (list in all) {
            for (item in list) {
                if (merged.none { kotlin.math.abs(it.first - item.first) < 1e-12 }) {
                    merged.add(item)
                }
            }
        }
        return merged.sortedBy { it.first }
    }

    /** 把解渲染成 LaTeX（借 Symja 的列表排版）。 */
    fun render(engine: SymjaEngine, branches: List<InequalityBranch>): String =
        engine.evaluateAsLatex(
            "{" + branches.joinToString(",") { "{" + it.joinToString(",") + "}" } + "}"
        )

    // ---------- 内部 ----------

    /** 形如 `|u| REL c` 或 `c REL |u|` 时，改写成两边平方后的不等式；否则返回 null。 */
    private fun rewriteAbs(engine: SymjaEngine, ast: IAST): String? {
        val left = ast.arg1()
        val right = ast.arg2()

        val absPart: IExpr
        val constant: IExpr
        val op: String
        if (left.isAST(F.Abs) && right.isNumber) {
            absPart = left
            constant = right
            op = operatorOf(ast)
        } else if (right.isAST(F.Abs) && left.isNumber) {
            absPart = right
            constant = left
            op = mirrorOperatorOf(ast)
        } else {
            return null
        }

        val c = engine.numericValueOf(constant.toString()) ?: return null
        if (c < 0.0) return null

        val inner = (absPart as IAST).arg1()
        return "(($inner)^2) $op (($constant)^2)"
    }

    private fun operatorOf(ast: IAST): String = when {
        ast.isAST(F.Less) -> "<"
        ast.isAST(F.LessEqual) -> "<="
        ast.isAST(F.Greater) -> ">"
        else -> ">="
    }

    /** `c REL |u|` 翻转成 `|u| REL' c`。 */
    private fun mirrorOperatorOf(ast: IAST): String = when {
        ast.isAST(F.Less) -> ">"
        ast.isAST(F.LessEqual) -> ">="
        ast.isAST(F.Greater) -> "<"
        else -> "<="
    }

    private fun samplePoint(critical: List<Pair<Double, IExpr>>, cell: Int, n: Int): Double = when {
        n == 1 && cell == 0 -> critical[0].first - 1.0
        n == 1 -> critical[0].first + 1.0
        cell == 0 -> critical[0].first - 1.0
        cell == n -> critical[n - 1].first + 1.0
        else -> (critical[cell - 1].first + critical[cell].first) / 2.0
    }

    /**
     * 把连续成立的区间并成若干段。
     *
     * 相邻两段之间如果那个临界点本身也满足（比如 `x^2+2x+1>=0` 的 -1），
     * 就继续并。并成全集时返回 null。
     */
    private fun buildRuns(cellOk: BooleanArray, nodeOk: BooleanArray, n: Int): List<Pair<Int, Int>>? {
        val runs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i <= n) {
            if (!cellOk[i]) {
                // 孤立点（只有临界点满足，左右都不满足）不处理
                i++
                continue
            }
            var j = i
            while (j + 1 <= n && cellOk[j + 1] && nodeOk[j]) j++
            runs.add(i to j)
            i = j + 1
        }
        if (runs.isEmpty()) return null
        if (runs.size == 1 && runs[0] == (0 to n)) return null // 全集
        return runs
    }

    /** 端点开闭由该点上不等式是否成立决定（成立才能取等号）。 */
    private fun bound(unknown: String, root: IExpr, closed: Boolean, lower: Boolean): String = when {
        lower && closed -> "$unknown >= $root"
        lower -> "$unknown > $root"
        closed -> "$unknown <= $root"
        else -> "$unknown < $root"
    }
}
