package io.github.maximemet.supercalc

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityGraphBinding
import io.github.maximemet.supercalc.engine.ConicType
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
            val curves = ArrayList<FloatArray>()
            val colors = ArrayList<Int>()
            functions.forEachIndexed { index, branches ->
                branches.forEach { branch ->
                    sample(branch, axes).forEach {
                        curves += it
                        colors += index
                    }
                }
            }
            // 参考实现只给「单条函数」算特殊点/准线
            val extra = if (functions.size == 1) GraphPlot.extraInfo(engine, formulas.last()) else null
            val lines = extra?.lines.orEmpty()
            val points = if (extra == null || extra.type == ConicType.OTHER) emptyList() else extra.points
            runOnUiThread {
                view.curves = curves
                view.curveColors = colors.toIntArray()
                view.extraLines = lines
                view.specialPoints = points
            }
        }
    }

    /**
     * 屏幕空间采样：从左边界到右边界每 [SAMPLE_STEP] 像素取一个点。
     *
     * 和参考实现一样，取值落空时断开（上一个点和下一个点之间不连线），
     * 值太大/太小时夹到一根「很远但有限」的横线上，画出来就是几乎竖直的线。
     */
    private fun sample(function: GraphFunction, axes: GraphAxes): List<FloatArray> {
        val segments = ArrayList<FloatArray>()
        var points = ArrayList<Float>(512)
        var x = SAMPLE_START
        val end = axes.idealMax[0] + 50f
        while (x <= end) {
            val coordX = axes.toCoordX(x)
            val value = function.valueAt(coordX.toDouble())
            if (value == null || value.isNaN()) {
                // 断点：这一段到此为止
                if (points.size >= 4) segments += points.toFloatArray()
                points = ArrayList(512)
                x += SAMPLE_STEP
                continue
            }
            val clamped = value.coerceIn(-CLAMP, CLAMP)
            points += x
            points += axes.toDisplayY(clamped.toFloat())
            x += SAMPLE_STEP
        }
        if (points.size >= 4) segments += points.toFloatArray()
        return segments
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
        private const val SAMPLE_STEP = 25f

        /** 采样起点，参考实现从 -150 开始（左侧多画一点，拖动时不会露空）。 */
        private const val SAMPLE_START = -150f

        /** 纵向夹取范围，对应参考实现的「太大了就贴到很远的地方」。 */
        private const val CLAMP = 1e6

        const val EXTRA_SYMJA_FORMAT = "symja_format"
        const val EXTRA_SYMJA_LATEX = "latex_formula"
    }
}
