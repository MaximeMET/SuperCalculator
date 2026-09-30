package io.github.maximemet.supercalc

import android.os.Bundle
import android.graphics.Matrix
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityGraphBinding
import io.github.maximemet.supercalc.engine.ConicType
import io.github.maximemet.supercalc.engine.ExtraLine
import io.github.maximemet.supercalc.engine.GraphFunction
import io.github.maximemet.supercalc.engine.GraphPlot
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.engine.SymjaEngine
import io.github.maximemet.supercalc.graph.GraphAxes
import java.util.concurrent.Executors

/**
 * 图像结果页。
 *
 * 对应参考实现的 `CalculatorGraphActivity`：进来时带着公式，页面把公式画成曲线。
 *
 * 几何全部由 [GraphAxes] 推出来（刻度间距、0 的位置、刻度回收），
 * 曲线按屏幕坐标每 25px 采一个点，和参考实现一样是「屏幕空间采样」。
 */
class GraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraphBinding
    private val engine by lazy { SymjaEngine() }
    private val executor = Executors.newSingleThreadExecutor()

    private var axes: GraphAxes? = null

    /** 采样好的曲线（屏幕坐标），拖动时平移它、缩放时按矩阵变换它。 */
    private var curves = ArrayList<Curve>()

    /** 缩放过程中的临时变换，松手重算后复位。 */
    private val curveMatrix = Matrix()
    private var scaling = false

    /** 当前图的准线 / 渐近线。 */
    private var extraLines: List<ExtraLine> = emptyList()

    /** 公式（Symja 形式，多函数用字面 `\n` 分隔）。 */
    private var symjaFormula: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        symjaFormula = intent.getStringExtra(EXTRA_SYMJA_FORMAT).orEmpty()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareGraph() }
        setupInsets()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.graphRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 底边留出导航栏：图区到这里为止，下面露出的是根布局的黑色底。
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            binding.statusBarScrim.layoutParams =
                binding.statusBarScrim.layoutParams.apply { height = bars.top }
            // 图区的几何要按「参考实现看到的窗口高度」来算：整屏去掉系统栏。
            binding.graphContainer.post { buildAxes(bars) }
            insets
        }
    }

    /**
     * 算出坐标轴，然后（后台）采样曲线。
     *
     * 参考实现把刻度位置做成子 View，这里用同一套算式算出来直接画。
     */
    private fun buildAxes(bars: androidx.core.graphics.Insets) {
        if (axes != null) return
        val toolbarHeight = binding.graphToolbar.height
        val metrics = resources.displayMetrics
        val windowWidth = metrics.widthPixels - bars.left - bars.right
        // 参考实现里的 Display.getSize() 给的是「去掉导航栏、含状态栏」的窗口高度
        val windowHeight = metrics.heightPixels - bars.bottom
        val containerHeight = binding.graphContainer.height
        if (toolbarHeight == 0 || containerHeight == 0) return
        val built = GraphAxes(windowWidth, windowHeight, toolbarHeight, containerHeight)
        axes = built
        binding.graphView.axes = built
        binding.graphView.onTranslate = { dx, dy -> handleTranslate(dx, dy) }
        binding.graphView.onScale = { factor, fx, fy -> handleScale(factor, fx, fy) }
        binding.graphView.onGestureEnd = { handleGestureEnd() }
        Log.d(
            TAG,
            "axes: w=$windowWidth h=$windowHeight toolbar=$toolbarHeight " +
                "container=$containerHeight posUnit=${built.posUnit} " +
                "zero=(${built.zeroX},${built.zeroY}) ideal=${built.idealPosUnit}",
        )
        plot()
    }

    /** 解析公式 -> 采样 -> 交给 View 绘制。 */
    private fun plot() {
        val axes = axes ?: return
        val formulas = Method.splitDrawFormula(symjaFormula)
        if (formulas.isEmpty()) {
            Toast.makeText(this, R.string.toast_graph_cannotdraw, Toast.LENGTH_SHORT).show()
            return
        }
        val view = binding.graphView
        executor.execute {
            val functions = formulas.take(MAX_FUNCTIONS)
                .map { GraphPlot.functions(engine, it) }
            if (functions.all { it.isEmpty() }) {
                runOnUiThread {
                    Toast.makeText(this, R.string.toast_graph_cannotdraw, Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val sampled = ArrayList<Curve>()
            functions.forEachIndexed { index, branches ->
                branches.forEach { branch -> sampled += sample(branch, index, axes) }
            }
            // 参考实现只给「单条函数」算特殊点/准线
            val extra = if (functions.size == 1) GraphPlot.extraInfo(engine, formulas.last()) else null
            val lines = extra?.lines.orEmpty()
            val points = if (extra == null || extra.type == ConicType.OTHER) emptyList() else extra.points
            runOnUiThread {
                curves = sampled
                this.extraLines = lines
                curveMatrix.reset()
                view.curveMatrix = null
                publish()
                view.specialPoints = points
            }
        }
    }

    /**
     * 屏幕空间采样：从左边界到右边界每 [SAMPLE_STEP] 像素取一个点。
     *
     * 和参考实现一样，取值落空时断开（用 NaN 标记，画的时候重新起笔），
     * 值太大/太小时夹到一根「很远但有限」的横线上，画出来就是几乎竖直的线。
     */
    private fun sample(function: GraphFunction, colorIndex: Int, axes: GraphAxes): Curve {
        val curve = Curve(function, colorIndex)
        var x = SAMPLE_START
        val end = axes.idealMax[0] + 50f
        while (x <= end) {
            curve.appendPoint(axes, x)
            x += SAMPLE_STEP
        }
        curve.left = SAMPLE_START
        curve.right = x - SAMPLE_STEP
        return curve
    }

    /** 把当前曲线与额外线交给 View。 */
    private fun publish() {
        val view = binding.graphView
        view.curves = curves.map { it.points.toFloatArray() }
        view.curveColors = curves.map { it.colorIndex }.toIntArray()
        view.extraLines = extraLines
        view.invalidate()
    }

    /**
     * 拖动：刻度整体平移，曲线跟着平移，只补两端新露出来的那一小条。
     *
     * 参考实现也是这么做的（`translateGraph` + `startSup` 只算新增区间），
     * 所以拖动时不会每帧重算整条曲线。
     */
    private fun handleTranslate(dx: Float, dy: Float) {
        val axes = axes ?: return
        if ((dx == 0f && dy == 0f) || curves.isEmpty()) return
        axes.translateBy(dx, dy)
        curves.forEach { curve ->
            curve.shift(dx, dy)
            curve.extendLeft(axes, SAMPLE_START)
            curve.extendRight(axes, axes.idealMax[0] + 50f)
        }
        publish()
    }

    /**
     * 双指缩放：刻度按焦点缩放（曲线靠矩阵跟着缩），松手后再整体重算。
     *
     * 参考实现的 `scale()` 就是这么分工的：`mScaleMatrix` 管曲线，
     * `dealScale` 管刻度，`adjustImage()` 在松手时重算全部。
     */
    private fun handleScale(factor: Float, focusX: Float, focusY: Float) {
        val axes = axes ?: return
        if (curves.isEmpty()) return
        axes.scaleBy(factor, focusX, focusY)
        curveMatrix.postScale(factor, factor, focusX, focusY)
        scaling = true
        binding.graphView.curveMatrix = Matrix(curveMatrix)
        publish()
        binding.graphView.invalidate()
    }

    /** 手势结束：按新的映射重算曲线，矩阵复位。 */
    private fun handleGestureEnd() {
        if (!scaling) return
        scaling = false
        replot()
    }

    /** 用当前坐标轴重算曲线（后台线程，算完回主线程替换）。 */
    private fun replot() {
        val axes = axes ?: return
        val functions = curves.map { it.function }
        if (functions.isEmpty()) return
        executor.execute {
            val sampled = functions.mapIndexed { index, function ->
                sample(function, curves[index].colorIndex, axes)
            }
            runOnUiThread {
                curves = ArrayList(sampled)
                curveMatrix.reset()
                binding.graphView.curveMatrix = null
                publish()
            }
        }
    }

    /** 分享按钮：参考实现是「截图 + 应用信息」，这里先只把公式和图片准备好留给后续步骤。 */
    private fun shareGraph() {
        Toast.makeText(this, R.string.share_not_ready, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "graph"

        /** 参考实现一次最多画 3 条函数。 */
        private const val MAX_FUNCTIONS = 3

        /** 采样步长（屏幕像素），参考实现写死 25。 */
        const val SAMPLE_STEP = 25f

        /** 采样起点，参考实现从 -150 开始（左侧多画一点，拖动时不会露空）。 */
        const val SAMPLE_START = -150f

        /** 纵向夹取范围，对应参考实现的「太大了就贴到很远的地方」。 */
        const val CLAMP = 1e6

        const val EXTRA_SYMJA_FORMAT = "symja_format"
        const val EXTRA_SYMJA_LATEX = "latex_formula"
    }
}

/**
 * 采样好的曲线：屏幕坐标点对 + 两端已经采到的位置。
 *
 * 断点（定义域外/无定义）用 NaN 占位，画的时候遇到 NaN 就重新起笔。
 */
private class Curve(val function: GraphFunction, val colorIndex: Int) {

    /** 成对的 x,y。 */
    val points = ArrayList<Float>(1024)
    var left = Float.NaN
    var right = Float.NaN

    /** 在屏幕 x 处采一个点追加到末尾。 */
    fun appendPoint(axes: GraphAxes, x: Float) {
        appendPointTo(axes, x, points)
    }

    fun shift(dx: Float, dy: Float) {
        for (i in points.indices step 2) {
            if (points[i].isNaN()) continue
            points[i] += dx
            points[i + 1] += dy
        }
        if (!left.isNaN()) left += dx
        if (!right.isNaN()) right += dx
    }

    /** 往左补采样，直到盖住 [target]。 */
    fun extendLeft(axes: GraphAxes, target: Float) {
        if (left.isNaN()) return
        var x = left
        val fresh = ArrayList<Float>(64)
        while (x - GraphActivity.SAMPLE_STEP >= target) {
            x -= GraphActivity.SAMPLE_STEP
            appendPointTo(axes, x, fresh)
        }
        if (fresh.isNotEmpty()) {
            points.addAll(0, fresh)
            left = x
        }
    }

    /** 往右补采样，直到盖住 [target]。 */
    fun extendRight(axes: GraphAxes, target: Float) {
        if (right.isNaN()) return
        var x = right
        while (x + GraphActivity.SAMPLE_STEP <= target) {
            x += GraphActivity.SAMPLE_STEP
            appendPoint(axes, x)
        }
        right = x
    }

    private fun appendPointTo(axes: GraphAxes, x: Float, into: ArrayList<Float>) {
        val value = function.valueAt(axes.toCoordX(x).toDouble())
        if (value == null || value.isNaN()) {
            into.add(Float.NaN)
            into.add(Float.NaN)
            return
        }
        val clamped = if (value < -GraphActivity.CLAMP) {
            -GraphActivity.CLAMP
        } else if (value > GraphActivity.CLAMP) {
            GraphActivity.CLAMP
        } else {
            value
        }
        into.add(x)
        into.add(axes.toDisplayY(clamped.toFloat()))
    }
}
