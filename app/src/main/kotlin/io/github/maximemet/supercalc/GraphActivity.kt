package io.github.maximemet.supercalc

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.graphics.Matrix
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityGraphBinding
import io.github.maximemet.supercalc.engine.ConicType
import io.github.maximemet.supercalc.engine.ExtraLine
import io.github.maximemet.supercalc.engine.GraphFunction
import io.github.maximemet.supercalc.engine.GraphExtra
import io.github.maximemet.supercalc.engine.GraphPlot
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.engine.SpecialPointKind
import io.github.maximemet.supercalc.engine.SymjaEngine
import io.github.maximemet.supercalc.graph.GraphAxes
import io.github.maximemet.supercalc.graph.GraphPoint
import io.github.maximemet.supercalc.graph.GraphPlotView
import io.github.maximemet.supercalc.view.ShareChooserDialog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

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

    /** 当前图上每条函数的定义，点气泡时要拿它判断「这个点落在哪几条曲线上」。 */
    private var functions: List<List<GraphFunction>> = emptyList()

    /**
     * 每条函数的「固定点」，按函数的显示顺序分组：
     * 焦点 / 中心点 / 最小值 / 最大值，然后是与 y 轴的交点。
     *
     * 参考实现把这几种点放在同一个桶（`intersectCalculed[i*2][i*2]`）里，
     * 点开气泡时**按这个顺序**逐条 `near()` 匹配、拼文字，所以顺序要保留。
     */
    private var fixedPoints: List<List<GraphPoint>> = emptyList()

    /** 图上所有能点的小白点：固定点 + 与 x 轴交点 + 函数之间的交点。 */
    private var tapPoints: List<GraphPoint> = emptyList()

    /** 图例里被点掉（暂时不画）的函数，下标 = 第几条函数。 */
    private val hidden = BooleanArray(MAX_FUNCTIONS)

    /** 公式的 LaTeX（图例上显示的就是它，逐行倒序）。 */
    private var formulaLatex: String = ""

    /** 左下角的图例。参考实现也是一个 WebView，加载 Mathbot Legend.html。 */
    private var legendView: WebView? = null

    /** 公式（Symja 形式，多函数用字面 `\n` 分隔）。 */
    private var symjaFormula: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        symjaFormula = intent.getStringExtra(EXTRA_SYMJA_FORMAT).orEmpty()
        formulaLatex = intent.getStringExtra(EXTRA_SYMJA_LATEX).orEmpty()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareGraph() }
        setupInsets()
        setupLegend()
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
        binding.graphView.onTap = { x, y -> handleGraphTap(x, y) }
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
            // 参考实现是**从最后一段往前**取函数的：公式里最后一行画成第 1 条（橙色）。
            // `{y=x+1 \n y=2x+3}` 里橙线是 2x+3、蓝线是 x+1，就是这么来的。
            val picked = formulas.takeLast(MAX_FUNCTIONS).reversed()
            val parsed = picked
                .map { GraphPlot.functions(engine, it) }
            if (parsed.all { it.isEmpty() }) {
                runOnUiThread {
                    Toast.makeText(this, R.string.toast_graph_cannotdraw, Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val zoomTimes = initialZoomTimes(parsed, axes)
            if (zoomTimes > 1) {
                axes.applyInitialZoom(zoomTimes)
                Log.d(
                    TAG,
                    "initial zoom x$zoomTimes labelUnit=(${axes.labelUnitX},${axes.labelUnitY}) " +
                        "zero=(${axes.zeroX},${axes.zeroY})",
                )
            }
            val sampled = ArrayList<Curve>()
            parsed.forEachIndexed { index, branches ->
                branches.forEach { branch -> sampled += sample(branch, index, axes) }
            }
            // 每条函数都算一次特殊点（焦点/中心/极值）；准线、渐近线只有单函数才画
            val extras = picked.map { runCatching { GraphPlot.extraInfo(engine, it) }.getOrNull() }
            val lines = if (parsed.size == 1) extras.firstOrNull()?.lines.orEmpty() else emptyList()
            // 每条函数一组固定点（顺序：焦点/中心/极值，再是 y 轴交点）
            val fixed = parsed.mapIndexed { index, branches ->
                fixedPointsOf(index, branches, extras.getOrNull(index)?.takeIf { it.type != ConicType.OTHER })
            }
            val (xAxis, pairs) = findXAxisAndPairIntersections(parsed, axes)
            // 画的顺序 = 参考实现加 View 的顺序：固定点、x 轴交点，最后是函数之间的交点
            val dots = fixed.flatten() + xAxis + pairs
            runOnUiThread {
                curves = sampled
                this.functions = parsed
                this.fixedPoints = fixed
                this.tapPoints = dots
                this.extraLines = lines
                curveMatrix.reset()
                view.curveMatrix = null
                publish()
                // 特殊点也走 intersections（它们本来就要能点），specialPoints 留空
                view.specialPoints = emptyList()
                applyVisibility()
                updateLegend()
                hideIntersectInfo()
            }
        }
    }

    // ---------------------------------------------------------------
    // 左下角的图例
    // ---------------------------------------------------------------

    /**
     * 图例页：`assets/matheditor/legend.html`，和参考实现的 Mathbot Legend.html 等价
     * （React 换成了一段手写 JS，DOM 结构与量尺寸的算法照抄）。
     *
     * 初始给 10dp×10dp，量出真实尺寸后再改成「CSS px × 3 (+1)」——
     * 参考实现是 `3 * dpUnit / density`，dpUnit 就是 1dp，所以这个 3 正好是密度。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupLegend() {
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowFileAccessFromFileURLs = true
        // 让页面里的 width=device-width 生效：量出来的 CSS px 才和原版一个口径
        web.settings.useWideViewPort = true
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isHorizontalScrollBarEnabled = false
        web.isVerticalScrollBarEnabled = false
        web.addJavascriptInterface(LegendBridge(), LEGEND_BRIDGE)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "legend JS ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                updateLegend()
            }
        }
        val initial = resources.getDimensionPixelSize(R.dimen.space_medium)
        binding.graphContainer.addView(
            web,
            FrameLayout.LayoutParams(initial, initial, Gravity.BOTTOM or Gravity.LEFT),
        )
        web.loadUrl(LEGEND_URL)
        legendView = web
    }

    /**
     * 参考实现 `getFunctionColorStr()`：把每一行公式倒过来（最后一行是第 1 条），
     * 配上颜色拼成 `公式&#RRGGBB$$…`；被点掉的条目**不带颜色**（前端画成灰色）。
     */
    private fun legendText(): String? {
        val latexs = formulaLatex.split(LATEX_NEWLINE)
        val maxLatex = latexs.size - 1
        val text = StringBuilder()
        var count = 0
        var i = 0
        while (count < MAX_FUNCTIONS && i <= maxLatex) {
            var line = latexs[maxLatex - i]
            if (!line.contains("=")) line = "y=$line"
            if (functions.getOrElse(i) { emptyList() }.isNotEmpty()) {
                if (hidden[count] && maxLatex > 0) {
                    text.append(line).append("$$")
                } else {
                    text.append(line)
                        .append("&#")
                        .append(String.format(Locale.US, "%X", GraphPlotView.CURVE_COLORS[count] and 0xFFFFFF))
                        .append("$$")
                }
                count++
            }
            i++
        }
        return if (count != functions.size) null else text.toString()
    }

    private fun updateLegend() {
        val web = legendView ?: return
        val text = legendText() ?: return
        Log.d(TAG, "legend:\"$text\"")
        web.evaluateJavascript("window.__Legend.setFormulaColor(${JSONObject.quote(text)})", null)
    }

    /** 图例报了尺寸：按参考实现的比值换算成像素摆好这个 WebView。 */
    private fun resizeLegend(width: Int, height: Int) {
        val web = legendView ?: return
        val ratio = 3f * resources.getDimension(R.dimen.dp_unit) / resources.displayMetrics.density
        val params = web.layoutParams as FrameLayout.LayoutParams
        params.width = (width * ratio).toInt() + resources.getInteger(R.integer.legend_width_adjust)
        params.height = (height * ratio).toInt()
        web.layoutParams = params
        Log.d(TAG, "legend $width x $height css px -> ${params.width} x ${params.height} px")
    }

    /**
     * 点图例：把这条曲线收起来 / 放出来。
     * 参考实现只改 `mGraphStat[]` 一个数组，重画曲线和交点的可见性都从它派生。
     */
    private fun toggleFunction(index: Int) {
        if (index !in hidden.indices) return
        hidden[index] = !hidden[index]
        applyVisibility()
        updateLegend()
    }

    /** 按 [hidden] 重画曲线与小圆点。 */
    private fun applyVisibility() {
        publish()
        binding.graphView.intersections = tapPoints.filter { it.owner < 0 || !hidden[it.owner] }
    }

    /** 图例页 <-> Android 的通道。参考实现叫 `__LegendCtrl`，方法名一致。 */
    private inner class LegendBridge {
        @JavascriptInterface
        fun onLegendComplete(width: Int, height: Int) {
            runOnUiThread { resizeLegend(width, height) }
        }

        @JavascriptInterface
        fun onClickLegend(index: Int) {
            runOnUiThread { toggleFunction(index) }
        }
    }

    /**
     * 一条函数的固定点：焦点/中心点/最小值/最大值，以及和 y 轴的交点 `(0, f(0))`。
     *
     * 参考实现 `parseExtraInfo()` 在解析函数时就把特殊点加进桶里，`initIntersects()`
     * 之后再加 y 轴交点，所以这里的顺序也是「先特殊点、后 y 轴交点」。
     */
    private fun fixedPointsOf(
        index: Int,
        branches: List<GraphFunction>,
        extra: GraphExtra?,
    ): List<GraphPoint> {
        val out = ArrayList<GraphPoint>()
        extra?.points?.forEach { point ->
            val name = when (point.kind) {
                SpecialPointKind.FOCUS -> "焦点"
                SpecialPointKind.CENTER -> "中心点"
                SpecialPointKind.MIN -> "最小值"
                SpecialPointKind.MAX -> "最大值"
            }
            out += GraphPoint(point.x, point.y, "\n${index + 1}的$name", owner = index)
        }
        branches.forEach { branch ->
            branch.valueAt(0.0)?.takeIf { it.isFinite() }?.let {
                out += GraphPoint(0.0, it, "\n${index + 1}与y轴交点", owner = index)
            }
        }
        return out
    }

    /**
     * 每条函数与 x 轴的交点，以及函数与函数之间的交点。
     *
     * 参考实现是逐个拿 Symja `Solve` 解出来的（`parseAddIntersect()`，和我们一样先解出 x
     * 再算 y）；这里改成在可见范围内找变号点、再二分细化——位置一致，还免去拼表达式字符串。
     *
     * 返回值的第二个元素（函数之间的交点）在参考实现里不进「固定点」桶，
     * 点开时文字由「点落在哪些曲线上」现推，所以单独放。
     */
    private fun findXAxisAndPairIntersections(
        functions: List<List<GraphFunction>>,
        axes: GraphAxes,
    ): Pair<List<GraphPoint>, List<GraphPoint>> {
        val xAxisPoints = ArrayList<GraphPoint>()
        val pairs = ArrayList<GraphPoint>()
        val left = axes.toCoordX(SAMPLE_START).toDouble()
        val right = axes.toCoordX(axes.idealMax[0]).toDouble()
        // 屏幕 4px 一步，细到不会漏掉挨得近的两个根
        val step = (4.0 / axes.ratioX).toDouble().let { if (it > 0) it else 1.0 }

        fun add(into: ArrayList<GraphPoint>, x: Double, y: Double, label: String, owner: Int) {
            if (!x.isFinite() || !y.isFinite()) return
            // 同一位置不重复叠点（参考实现的 insertSort 也会去重）
            val near = into.any { abs(it.x - x) < 1e-3 && abs(it.y - y) < 1e-3 }
            if (!near) into += GraphPoint(x, y, label, owner = owner)
        }

        fun scan(into: ArrayList<GraphPoint>, f: (Double) -> Double?, label: String, owner: Int) {
            var px = left
            var py = f(px)
            var x = left + step
            while (x <= right) {
                val y = f(x)
                if (py != null && y != null && (py > 0) != (y > 0)) {
                    bisect(px, x, f)?.let { root ->
                        val value = f(root) ?: 0.0
                        add(into, root, value, label, owner)
                    }
                }
                px = x
                py = y
                x += step
            }
        }

        functions.forEachIndexed { index, branches ->
            val first = branches.firstOrNull()
            if (first != null) {
                scan(xAxisPoints, first::valueAt, "\nx轴与${index + 1}交点", index)
                for (j in 0 until index) {
                    val other = functions[j].firstOrNull() ?: continue
                    // 两条函数的差变号 = 交点；细化后 y 取两条曲线的平均值
                    fun diff(x: Double): Double? {
                        val a = first.valueAt(x) ?: return null
                        val b = other.valueAt(x) ?: return null
                        return a - b
                    }
                    var px = left
                    var py = diff(px)
                    var x = left + step
                    while (x <= right) {
                        val y = diff(x)
                        if (py != null && y != null && (py > 0) != (y > 0)) {
                            bisect(px, x, ::diff)?.let { root ->
                                val a = first.valueAt(root)
                                val b = other.valueAt(root)
                                if (a != null && b != null) {
                                    add(
                                        pairs,
                                        root,
                                        (a + b) / 2.0,
                                        "\n-- ${j + 1}与${index + 1}交点",
                                        index,
                                    )
                                }
                            }
                        }
                        px = x
                        py = y
                        x += step
                    }
                }
            }
        }
        return xAxisPoints to pairs
    }

    /** 二分找零点：两端异号时调用。 */
    private fun bisect(left: Double, right: Double, f: (Double) -> Double?): Double? {
        var a = left
        var b = right
        var fa = f(a) ?: return null
        repeat(60) {
            val m = (a + b) / 2
            val fm = f(m) ?: return null
            if ((fa > 0) == (fm > 0)) {
                a = m
                fa = fm
            } else {
                b = m
            }
        }
        return (a + b) / 2
    }

    /**
     * 参考实现 `ScaleGraphView.initAll()` / `roughEstimateGraph()`：
     *
     * 默认视野是 `±scope`（`scope = 纵轴格数/4 × 每格数值` = 15/4 × 2 = 7.5），
     * 每条函数沿当前视野每 250 屏幕像素粗估一次 y 范围；只要有一条函数整个
     * 跑到视野外（最小值在视野上方、或最大值在视野下方），就把刻度单位
     * 按 2 的幂放大，视野跟着变大。
     */
    private fun initialZoomTimes(functions: List<List<GraphFunction>>, axes: GraphAxes): Int {
        var zoomTimes = 1
        var scope = (GraphAxes.Y_COUNT / 4f) * (-GraphAxes.DEFAULT_LABEL_UNIT_Y)
        var maxAxisY = scope
        var minAxisY = -scope
        // 参考实现里的 tooBigScreenCoor：粗估时的夹取范围，用 X 轴的参考点算（原版如此）
        val tooBig = run {
            val raw = (1e6f - axes.originX) / axes.ratioX + axes.labelX0
            (if (raw < 0f) -raw else raw) + 2f
        }
        functions.forEach { branches ->
            val branch = branches.firstOrNull() ?: return@forEach
            var ymin = Float.MAX_VALUE
            var ymax = -Float.MAX_VALUE
            var any = false
            fun scan(screenX: Float) {
                val value = branch.valueAt(axes.toCoordX(screenX).toDouble()) ?: return
                if (value.isNaN()) return
                val y = value.coerceIn(-tooBig.toDouble(), tooBig.toDouble()).toFloat()
                if (y < ymin) ymin = y
                if (y > ymax) ymax = y
                any = true
            }
            var x = SAMPLE_START
            while (x < axes.idealMax[0]) {
                scan(x)
                x += GraphAxes.ESTIMATE_STEP
            }
            scan(axes.idealMax[0])
            if (!any) {
                // 参考实现的兜底：估不出范围就当成 defaultBigGraphLabel（100）
                ymin = GraphAxes.DEFAULT_BIG_LABEL
                ymax = GraphAxes.DEFAULT_BIG_LABEL
            }
            if (ymin > maxAxisY) {
                zoomTimes = 1 shl (log2((ymin - 0f) / scope).toInt() + 1)
                scope *= zoomTimes
                maxAxisY = scope
                minAxisY = -scope
            } else if (ymax < minAxisY) {
                zoomTimes = 1 shl (log2((0f - ymax) / scope).toInt() + 1)
                scope *= zoomTimes
                maxAxisY = scope
                minAxisY = -scope
            }
        }
        return zoomTimes
    }

    private fun log2(value: Float): Float =
        (Math.log(value.toDouble()) / Math.log(2.0)).toFloat()

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
        // 图例里点掉的函数不画（参考实现画曲线那一步就是按 canShow 跳过的）
        val visible = curves.filter { it.colorIndex < 0 || !hidden[it.colorIndex] }
        view.curves = visible.map { it.points.toFloatArray() }
        view.curveColors = visible.map { it.colorIndex }.toIntArray()
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
        // 参考实现的 scale() 第一件事就是把气泡收起来
        hideIntersectInfo()
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

    // ---------------------------------------------------------------
    // 交点气泡
    // ---------------------------------------------------------------

    /**
     * 单击图区：命中交点就弹气泡，没命中就把气泡收起来。
     *
     * 参考实现给每个交点建了一个可点的小 View（`mPosOffset` 写死 50px、
     * `mTouchableDiameter` 30dp），盒子中心对着交点，但**不对称**：
     * 左上各 50px、右下各 65px（= 50 + 30dp/2）。叠在一起时后加的在上，
     * 也就是函数之间的交点盖住固定点。
     */
    private fun handleGraphTap(tx: Float, ty: Float) {
        val axes = axes ?: return
        var hit: GraphPoint? = null
        tapPoints.forEach { point ->
            if (!point.valid) return@forEach
            if (point.owner >= 0 && hidden[point.owner]) return@forEach
            val sx = axes.toDisplayX(point.x.toFloat())
            val sy = axes.toDisplayY(point.y.toFloat())
            if (tx >= sx - POINT_OFFSET && tx <= sx + POINT_TAIL &&
                ty >= sy - POINT_OFFSET && ty <= sy + POINT_TAIL
            ) {
                hit = point
            }
        }
        val point = hit
        if (point == null) {
            hideIntersectInfo()
            return
        }
        showIntersectInfo(axes, point)
    }

    /**
     * 气泡的文字与位置，逐条对应参考实现 `IntersectionView.onClick()`。
     *
     * 文字 = 坐标 + 附加说明。坐标按当前刻度精度格式化再去掉末尾的 0；附加说明：
     *  - 正好落在原点上 → 「原点」；
     *  - 附近有固定点（焦点/中心/极值/y 轴交点）→ 直接接上它的说明；
     *  - 点落在曲线上 → 「1,2的交点」；在 x 轴上时写成「2与x轴的交点」；
     *  - 什么都不是 → 说明这是缩放后残留的旧点，并把这个点**作废**（不再画、点不动）。
     *
     * 位置：左下角贴在交点下方 `2 × 半径` 处，横向按**上一段文字**的宽度居中——
     * 这是原版的写法（先读 `getWidth()` 再 `setText()`），第一次点开的偏移量
     * 就是占位文字「交点」的半个宽度，我们照抄。
     */
    private fun showIntersectInfo(axes: GraphAxes, point: GraphPoint) {
        val xPrecision = labelPrecision(axes.labelUnitX)
        val yPrecision = labelPrecision(axes.labelUnitY)
        val xTolerance = Math.pow(10.0, -xPrecision.toDouble()).toFloat()
        val yTolerance = Math.pow(10.0, -yPrecision.toDouble()).toFloat()
        val x = point.x.toFloat()
        val y = point.y.toFloat()

        val text = StringBuilder("(")
        var onXAxis = false
        var onYAxis = false
        if (xPrecision >= 0) {
            val v = trimTailZeros(String.format(Locale.US, "%.${xPrecision}f", x))
            text.append(v)
            if (v == "0") onYAxis = true
        } else if (x < xTolerance) {
            // 参考实现这里没取绝对值，负坐标也会落到「0」——原样照抄
            text.append("0")
            onYAxis = true
        } else {
            text.append(trimTailZeros(String.format(Locale.US, "%.2g", x)))
        }
        text.append(", ")
        if (yPrecision >= 0) {
            val v = trimTailZeros(String.format(Locale.US, "%.${yPrecision}f", y))
            text.append(v)
            if (v == "0") onXAxis = true
        } else if (y < yTolerance) {
            text.append("0")
            onXAxis = true
        } else {
            text.append(trimTailZeros(String.format(Locale.US, "%.2g", y)))
        }
        text.append(")")

        var hasOtherInfo = false
        if (onXAxis && onYAxis) {
            text.append("\n原点")
            hasOtherInfo = true
        }
        // 固定点：按函数顺序逐条比对，命中就把它的说明接上
        fixedPoints.forEach { group ->
            group.forEach { other ->
                if (abs(other.x - point.x) <= xTolerance && abs(other.y - point.y) <= yTolerance) {
                    hasOtherInfo = true
                    text.append(other.label)
                }
            }
        }
        // 「这个点在哪几条曲线上」：容差是纵向每格的 5%。
        // 参考实现的写法是 `(-0.05f) * getLabelUnit(Y)`——它的 labelUnitY 是负数
        // （y 轴向下为正），负负得正。我们这里 labelUnitY 同样是负的，照抄即可。
        val through = StringBuilder()
        val tolerance = -0.05 * axes.labelUnitY
        functions.forEachIndexed { index, branches ->
            val on = branches.any { branch ->
                branch.valueAt(point.x)?.let { abs(it - point.y) <= tolerance } ?: false
            }
            if (on) through.append(index + 1).append(",")
        }
        val length = through.length
        if (length > 0) {
            through.deleteCharAt(length - 1)
            if (onXAxis) through.append("与x轴")
            if (length > 2 || onXAxis) {
                text.append("\n").append(through).append("的交点")
                hasOtherInfo = true
            }
        }
        if (!hasOtherInfo) {
            text.append(point.label)
            text.append("\n因缩放精度误差位置不准了,点别处我就消失\n双指放大图像,交点会算的更准")
            point.valid = false
            binding.graphView.invalidate()
        }

        val info = binding.graphIntersectInfo
        val oldWidth = info.width
        info.text = text
        val radius = resources.getDimensionPixelSize(R.dimen.graph_point_radis)
        val sx = axes.toDisplayX(x)
        val sy = axes.toDisplayY(y)
        info.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins((sx - oldWidth / 2f).toInt(), (sy + 2 * radius).toInt(), 0, 0)
        }
        info.visibility = View.VISIBLE
        // 白圈画在画布里：参考实现在刻度线下面（见 GraphPlotView.highlight）
        binding.graphView.highlight = sx to sy
    }

    /** 对应参考实现 `CalculatorGraphActivity.hideIntersectInfoView()`：两个浮层一起收起来。 */
    private fun hideIntersectInfo() {
        binding.graphIntersectInfo.visibility = View.GONE
        binding.graphView.highlight = null
    }

    /** 参考实现 `IntersectionView.setAxisInfo()` 里那一行：刻度精度 = 2 - log10(每格数值)。 */
    private fun labelPrecision(unit: Float): Int =
        2 - Math.log10(abs(unit).toDouble()).toInt()

    /** 参考实现 `StringUtils.replaceTailZeros(str, true)`：去掉小数末尾的 0 和光秃秃的小数点。 */
    private fun trimTailZeros(value: String): String {
        if (!value.contains(".") || value.contains("e")) return value
        var out = value.trimEnd('0')
        if (out == "-0.") out = "0."
        if (out.endsWith(".")) out = out.dropLast(1)
        return out
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

    /**
     * 分享按钮。对应参考实现的 `action_share`：
     * 把图区截下来，四周补 75px 底色、底部接一条「应用信息」宣传图，存成图片，
     * 然后弹一个自己画的渠道选择框（`ImgTxtChooserDialog`），选谁就发给谁。
     *
     * 两个新系统必须改的地方：图片走 FileProvider（原版 `Uri.fromFile` 会崩）、
     * 宣传图不能再用原版那张带二维码的位图（M6 换素材），这里按同样尺寸画我们自己的一张。
     */
    private fun shareGraph() {
        val view = binding.graphContainer
        if (view.width <= 0 || view.height <= 0) return
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(shot))
        val composed = composeShareImage(shot)
        val file = File(File(cacheDir, "share").apply { mkdirs() }, SHARE_FILE_NAME)
        val saved = runCatching {
            FileOutputStream(file).use { composed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!saved) {
            Toast.makeText(this, R.string.share_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        ShareChooserDialog(this, uri).show()
    }

    /** 参考实现 `addAppInfoAndSave`：左右下各留 [SHARE_BORDER] 像素底色，底部接宣传图。 */
    private fun composeShareImage(shot: Bitmap): Bitmap {
        val banner = appInfoBanner(SHARE_BORDER * 2 + shot.width)
        val out = Bitmap.createBitmap(
            SHARE_BORDER * 2 + shot.width,
            SHARE_BORDER + shot.height + banner.height,
            Bitmap.Config.ARGB_8888,
        )
        out.eraseColor(ContextCompat.getColor(this, R.color.share_bg))
        val canvas = Canvas(out)
        canvas.drawBitmap(shot, SHARE_BORDER.toFloat(), SHARE_BORDER.toFloat(), null)
        canvas.drawBitmap(banner, 0f, (SHARE_BORDER + shot.height).toFloat(), null)
        return out
    }

    /**
     * 分享图底部那条应用信息。原版是一张 750×1039 的宣传位图（应用图标 + 二维码 +
     * 官网地址），属于原版素材，先按同样的长宽比画一张我们自己的，M6 换成正式素材。
     */
    private fun appInfoBanner(width: Int): Bitmap {
        val height = (width * BANNER_RATIO).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF333333.toInt()
            textSize = width * 0.075f
            textAlign = Paint.Align.CENTER
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF999999.toInt()
            textSize = width * 0.037f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(getString(R.string.app_name), width / 2f, height * 0.4f, name)
        canvas.drawText(getString(R.string.share_banner_slogon), width / 2f, height * 0.48f, small)
        canvas.drawText(SHARE_BANNER_URL, width / 2f, height * 0.78f, small)
        return bmp
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

        /** 参考实现 `IntersectionView.mPosOffset`：可点盒子的左上偏移（写死的像素值）。 */
        private const val POINT_OFFSET = 50f

        /** 可点盒子的右下偏移：50 + 30dp/2 = 65px（density 3 的那台设备上）。 */
        private const val POINT_TAIL = 65f

        const val EXTRA_SYMJA_FORMAT = "symja_format"
        const val EXTRA_SYMJA_LATEX = "latex_formula"

        /** 图例页与 Android 之间的通道名。编辑器用的是 "Android"，两个 WebView 各注册一份。 */
        private const val LEGEND_BRIDGE = "Android"

        private const val LEGEND_URL = "file:///android_asset/matheditor/legend.html"

        /** 公式里换行的 LaTeX 写法（`\newline` 命令原样输出）。 */
        private val LATEX_NEWLINE = Regex("""\\newline""")

        /** 分享图四周留的底色宽度，参考实现写死 75px。 */
        private const val SHARE_BORDER = 75

        /** 分享用的临时图片（放 cache，靠 FileProvider 给出去）。 */
        private const val SHARE_FILE_NAME = "graph-share.png"

        /** 底部宣传图的长宽比，取自参考实现那张 750×1039 的位图。 */
        private const val BANNER_RATIO = 1039f / 750f

        private const val SHARE_BANNER_URL = "github.com/MaximeMET/supercalc"
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
