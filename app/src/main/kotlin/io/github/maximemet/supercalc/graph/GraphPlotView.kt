package io.github.maximemet.supercalc.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.engine.ExtraLine
import io.github.maximemet.supercalc.engine.SpecialPoint

/**
 * 图像页的画布。
 *
 * 参考实现这里叠了两种东西：底下是画曲线的 `ScaleGraphView`，上面是按刻度铺的
 * `AxisLabelView`。我们合成一个 View，但**画法逐条对齐**：
 *
 *  - 浅灰虚线 `#848B90`：每格一条，虚线节奏 5/5、线宽 1px；
 *  - 深灰实线 `#6B7176`：0 刻度那条通线（横轴 2px 高、纵轴 2px 宽），
 *    外加每条刻度旁边 10px 长的小段；
 *  - 白色 20sp 刻度文字：纵轴刻度写在竖轴左边，横轴刻度写在横轴下面；
 *    0 那条只画线不写字（参考实现就是 `return` 掉了）；
 *  - 曲线 2dp 粗，三条函数依次用橙 `#EFB557`／蓝 `#62ACFF`／绿 `#4AD17E`；
 *  - 焦准线等额外线是 1dp 橙色虚线；特殊点是半径 3dp 的白色实心圆。
 */
class GraphPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 拖动回调：参数是「手指位移的反向」，和参考实现一致。 */
    var onTranslate: ((Float, Float) -> Unit)? = null

    /** 双指缩放回调：(缩放系数, 焦点 x, 焦点 y)。 */
    var onScale: ((Float, Float, Float) -> Unit)? = null

    /** 手势结束（抬手/缩放停止），参考实现在这时候整体重算一次曲线。 */
    var onGestureEnd: (() -> Unit)? = null

    private var scaleEndTime = -1L

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onScale?.invoke(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleEndTime = System.currentTimeMillis()
                onGestureEnd?.invoke()
            }
        },
    )

    private val dragDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                onTranslate?.invoke(-dx, -dy)
                return true
            }

            /** 参考实现的双击只消费事件，不做动作。 */
            override fun onDoubleTap(e: MotionEvent): Boolean = true
        },
    )

    /**
     * 参考实现的触摸分发：单指走拖动（双击也算单指），双指走缩放，
     * 并且从双指回到单指后的 [TOUCH_GAP] 毫秒内不认拖动，免得手抖被当成拖。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount < 2 &&
            (event.action == MotionEvent.ACTION_DOWN ||
                System.currentTimeMillis() - scaleEndTime > TOUCH_GAP)
        ) {
            scaleEndTime = -1L
            dragDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) onGestureEnd?.invoke()
        } else {
            scaleDetector.onTouchEvent(event)
        }
        return true
    }

    var axes: GraphAxes? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 曲线点，直接是屏幕坐标：`[x1, y1, x2, y2, ...]`，每条曲线一段。 */
    var curves: List<FloatArray> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 每条曲线用第几种颜色（和 [curves] 一一对应）。 */
    var curveColors: IntArray = IntArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var extraLines: List<ExtraLine> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var specialPoints: List<SpecialPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 曲线的临时变换矩阵。
     *
     * 参考实现把曲线画在 `mScaleMatrix` 下：双指缩放时曲线立刻跟着缩放，
     * 刻度则是另外算位置的；松开手以后整体重算一次、矩阵回到单位阵。
     */
    var curveMatrix: Matrix? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 曲线颜色，顺序取自参考实现。 */
    private val gridPaint = Paint().apply {
        color = GRID_COLOR
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimensionPixelOffset(R.dimen.graph_axis_width).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f, 5f, 5f), 1f)
    }

    private val solidPaint = Paint().apply {
        color = AXIS_COLOR
        style = Paint.Style.STROKE
        // 0 那条线是「两倍轴宽」的实线
        strokeWidth = 2f * resources.getDimensionPixelOffset(R.dimen.graph_axis_width)
    }

    private val labelPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = resources.getDimensionPixelSize(R.dimen.text_axis_label).toFloat()
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val curvePaints = CURVE_COLORS.map { color ->
        Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimensionPixelSize(R.dimen.graph_width).toFloat()
        }
    }

    private val extraPaint = Paint().apply {
        color = CURVE_COLORS[0]
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimensionPixelSize(R.dimen.graph_extra_line_width).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f, 5f, 5f), 1f)
    }

    private val pointPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    /** 刻度文字高度：参考实现按 `bottom - top` 取整后乘 3 当作 View 的高度。 */
    private val textHeight: Int
        get() {
            val metrics = labelPaint.fontMetrics
            return (metrics.bottom - metrics.top).toInt()
        }

    /**
     * 文字基线里的半高用的是**没取整**的 `bottom - top`。
     *
     * 这一点很关键：Skia 会把文字基线吸附到整数像素，这里差 0.3px 就可能让整行字
     * 上下跳一个像素——基准截图里刻度文字就落在「0.82 那一侧」，取整版本会矮 1px。
     */
    private val halfTextHeight: Float
        get() {
            val metrics = labelPaint.fontMetrics
            return (metrics.bottom - metrics.top) / 2f
        }

    private val textBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val axes = axes ?: return

        val save = canvas.save()
        val matrix = curveMatrix
        matrix?.let { canvas.concat(it) }
        drawCurves(canvas)
        // 参考实现只在「没在缩放」的时候画准线/渐近线
        if (matrix == null) drawExtraLines(canvas, axes)
        canvas.restoreToCount(save)
        drawHorizontalAxisLabels(canvas, axes)
        drawVerticalAxisLabels(canvas, axes)
        drawSpecialPoints(canvas, axes)
    }

    private fun drawCurves(canvas: Canvas) {
        curves.forEachIndexed { index, points ->
            if (points.size < 4) return@forEachIndexed
            val functionIndex = curveColors.getOrElse(index) { 0 }
            val paint = curvePaints[functionIndex.coerceIn(0, curvePaints.size - 1)]
            // 采到断点（定义域外/无定义）的地方用 NaN 标出来，这里断开重起一条线
            val path = Path()
            var pen = false
            var i = 0
            while (i + 1 < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (x.isNaN() || y.isNaN()) {
                    pen = false
                } else if (pen) {
                    path.lineTo(x, y)
                } else {
                    path.moveTo(x, y)
                    pen = true
                }
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawExtraLines(canvas: Canvas, axes: GraphAxes) {
        val left = -1f - 0f
        val right = axes.idealMax[0] + 1f
        val top = -1f
        val bottom = axes.idealMax[1] + 1f
        extraLines.forEach { line ->
            when (line) {
                is ExtraLine.Vertical -> {
                    val x = axes.toDisplayX(line.x.toFloat())
                    canvas.drawLine(x, top, x, bottom, extraPaint)
                }
                is ExtraLine.Horizontal -> {
                    val y = axes.toDisplayY(line.y.toFloat())
                    canvas.drawLine(left, y, right, y, extraPaint)
                }
                is ExtraLine.Slanted -> {
                    canvas.drawLine(
                        left,
                        axes.toDisplayY((line.k * axes.toCoordX(left) + line.b).toFloat()),
                        right,
                        axes.toDisplayY((line.k * axes.toCoordX(right) + line.b).toFloat()),
                        extraPaint,
                    )
                }
            }
        }
    }

    /** 纵轴刻度：整行虚线 + 轴上的小段 + 左侧文字；0 那条画通线并往下让 10px。 */
    private fun drawHorizontalAxisLabels(canvas: Canvas, axes: GraphAxes) {
        val width = width.toFloat()
        val height = textHeight * 3
        // 注意：参考实现这里 `height / 2` 是**整数除法**，所以刻度线会落在半个像素上，
        // 画出来是两行各 50% 的浅灰——这正是基准截图里的效果，不能改成浮点除。
        val halfView = (height / 2).toFloat()
        val halfText = halfTextHeight
        // 纵轴的刻度小段和文字都挂在竖直轴（0 刻度线）上，不是挂在各自的横线上
        val axisX = axes.zeroX
        axes.yLabels.forEach { label ->
            if (!label.visible) return@forEach
            val pos = label.pos
            val top = (pos - halfView).toInt()
            val lineY = top + halfView
            val text = label.text
            labelPaint.getTextBounds(text, 0, text.length, textBounds)
            if (text == "0") {
                canvas.drawLine(0f, lineY, width, lineY, solidPaint)
                canvas.drawText(
                    text,
                    axisX - (textBounds.width() + 10 + 10 + 2),
                    lineY + halfText + 10f,
                    labelPaint,
                )
            } else {
                val path = android.graphics.Path()
                path.moveTo(0f, lineY)
                path.lineTo(width, lineY)
                canvas.drawPath(path, gridPaint)
                canvas.drawLine(axisX - 10f, lineY, axisX, lineY, solidPaint)
                canvas.drawText(
                    text,
                    axisX - (textBounds.width() + 10 + 10 + 2),
                    lineY + halfText - labelPaint.fontMetrics.bottom,
                    labelPaint,
                )
            }
        }
    }

    /** 横轴刻度：整列虚线 + 轴上小段 + 下方文字；0 那条只画通线。 */
    private fun drawVerticalAxisLabels(canvas: Canvas, axes: GraphAxes) {
        val bottom = height.toFloat()
        axes.xLabels.forEach { label ->
            if (!label.visible) return@forEach
            val text = label.text
            labelPaint.getTextBounds(text, 0, text.length, textBounds)
            val viewWidth = maxOf(textBounds.width() + 4, 1)
            // 同样按整数除：竖线落在 half 像素上，和其他刻度线保持一致的观感
            val halfWidth = (viewWidth / 2).toFloat()
            val left = (label.pos - halfWidth).toInt()
            val lineX = left + halfWidth
            val axisY = axes.zeroY
            if (text == "0") {
                canvas.drawLine(lineX, 0f, lineX, bottom, solidPaint)
                return@forEach
            }
            val path = android.graphics.Path()
            path.moveTo(lineX, 0f)
            path.lineTo(lineX, bottom)
            canvas.drawPath(path, gridPaint)
            canvas.drawLine(lineX, axisY - 10f, lineX, axisY, solidPaint)
            canvas.drawText(
                text,
                lineX - textBounds.width() / 2f,
                axisY + 10f - labelPaint.fontMetrics.top,
                labelPaint,
            )
        }
    }

    private fun drawSpecialPoints(canvas: Canvas, axes: GraphAxes) {
        val radius = resources.getDimensionPixelSize(R.dimen.graph_point_radis).toFloat()
        specialPoints.forEach { point ->
            canvas.drawCircle(
                axes.toDisplayX(point.x.toFloat()),
                axes.toDisplayY(point.y.toFloat()),
                radius,
                pointPaint,
            )
        }
    }

    companion object {
        /** 参考实现里的三条曲线颜色：橙、蓝、绿。 */
        val CURVE_COLORS = intArrayOf(0xFFEFB557.toInt(), 0xFF62ACFF.toInt(), 0xFF4AD17E.toInt())

        /** 网格虚线（浅灰）。 */
        const val GRID_COLOR = 0xFF848B90.toInt()

        /** 0 刻度的通线与刻度小段（深灰）。 */
        const val AXIS_COLOR = 0xFF6B7176.toInt()

        /** 从双指回到单指之后多久内不认拖动，取自参考实现的 TouchUp2to1FingerGap。 */
        private const val TOUCH_GAP = 1000L
    }
}
