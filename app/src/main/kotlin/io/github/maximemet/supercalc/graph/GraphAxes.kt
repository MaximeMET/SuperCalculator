package io.github.maximemet.supercalc.graph

/**
 * 图像页的坐标轴模型。
 *
 * 参考实现把每条刻度做成一个铺满全屏的 View，位置/文字由下面这套算式推出来，
 * 这里照搬同一套算式（数值对不上界面就会和基准截图对不上）：
 *
 *  - 刻度间距 `posUnit` = (屏幕宽×2/8 + 窗口高×2/15) / 2，**两个方向用同一个值**；
 *  - 缩放下限 `idealPosUnit` = 把 posUnit 归到「整百减五十」之后的阈值（只用于缩放判断）；
 *  - 横轴 8 格、标签从 -4 起每格 2；纵轴 15 格、标签从 8 起每格 -2；
 *  - 0 刻度未必在正中：`moveZero()` 会把 0 对应的位置算出来并夹到可视区内。
 */
class GraphAxes(
    val screenWidth: Int,
    val windowHeight: Int,
    val toolbarHeight: Int,
    val containerHeight: Int,
) {

    /** 一条刻度：像素位置 [pos] 与它显示的数字 [label]。 */
    class Label(var pos: Float, var label: Float) {
        var visible = true
        /** 显示用的文字：`%.4g` 再去掉尾部多余的 0。 */
        val text: String get() = GraphAxes.formatLabel(label)
    }

    val xLabels = ArrayList<Label>(X_COUNT)
    val yLabels = ArrayList<Label>(Y_COUNT)

    /** 每格的像素数（横/纵相同）。 */
    var posUnit = 0f
        private set

    /** 缩放阈值，只用于判断「要不要重新分配刻度」。 */
    var idealPosUnit = 0f
        private set

    var labelUnitX = DEFAULT_LABEL_UNIT_X
        private set
    var labelUnitY = DEFAULT_LABEL_UNIT_Y
        private set

    private var labelMinX = X_LABEL_MIN
    private var labelMinY = Y_LABEL_MIN

    /** 可视区（容器坐标）。 */
    val idealMin = floatArrayOf(5f, 5f)
    val idealMax = floatArrayOf(0f, 0f)

    /** 0 刻度在容器里的位置，纵轴竖线与横轴横线都画在这里。 */
    var zeroX = 0f
        private set
    var zeroY = 0f
        private set

    /** 当前哪一条刻度排在最前（越界回收时会变）。 */
    private var minIdxX = 0
    private var minIdxY = 0

    init {
        idealMax[0] = screenWidth.toFloat()
        idealMax[1] = ((windowHeight - 10) - toolbarHeight).toFloat()
        reset()
    }

    /** 重新按屏幕尺寸铺一套刻度并归位到 0。 */
    fun reset() {
        posUnit = ((screenWidth * 2 / X_COUNT) + (windowHeight * 2 / Y_COUNT)) / 2f
        val raw = (posUnit / 100f).toInt()
        idealPosUnit = if (raw <= 1 || raw > 10) posUnit * 0.7f else raw * 100f - 50f

        xLabels.clear()
        yLabels.clear()
        minIdxX = 0
        minIdxY = 0
        for (i in 0 until X_COUNT) {
            xLabels += Label(5f + i * posUnit, labelMinX + i * labelUnitX)
        }
        for (i in 0 until Y_COUNT) {
            yLabels += Label(5f + i * posUnit, labelMinY + i * labelUnitY)
        }
        moveZero()
        refreshVisibility()
    }

    /**
     * 开局自动缩放：参考实现 `ScaleGraphView.initAll()` 算出的 `zoomTimes`。
     *
     * 倍数大于 1 时，两个方向的刻度步长一起乘以它，起点也跟着挪：
     * 参考实现里 X 的起点用的是 **Y 的步长**（`(cntX/4) * labelUnitY`），
     * 所以纵轴标签从 `96` 起、横轴标签从 `-64` 起——照抄。
     */
    fun applyInitialZoom(zoomTimes: Int) {
        if (zoomTimes <= 1) return
        labelUnitX = DEFAULT_LABEL_UNIT_X * zoomTimes
        labelUnitY = DEFAULT_LABEL_UNIT_Y * zoomTimes
        labelMinX = (X_COUNT / 4) * labelUnitY
        labelMinY = (Y_COUNT / 4) * (-labelUnitY)
        reset()
    }

    /**
     * 把 0 挪到「0 这条刻度应该在的位置」，并夹在可视区里。
     *
     * 两个方向分别算：横轴用横轴的中间刻度推，纵轴用纵轴的中间刻度推。
     */
    fun moveZero() {
        val refX = sorted(X, xLabels.size / 2)
        zeroX = (refX.pos - refX.label * posUnit / labelUnitX)
            .coerceIn(idealMin[0], idealMax[0])
        val refY = sorted(Y, yLabels.size / 2)
        zeroY = (refY.pos - refY.label * posUnit / labelUnitY)
            .coerceIn(idealMin[1], idealMax[1])
    }

    /**
     * 把跑出可视区的刻度收回来。
     *
     * 参考实现的做法是把越界的刻度整体平移一个「整屏跨度」，同时把数字也平移相应的量，
     * 这样缩放/拖动以后刻度的数字仍然是连续的；平移后仍然看不见的刻度直接隐藏。
     */
    fun refreshVisibility() {
        refreshVisibility(X)
        refreshVisibility(Y)
    }

    /**
     * 拖动：所有刻度沿各自方向平移。
     *
     * 参考实现是给每个刻度 View 加偏移（`moveD`），再重新算 0 的位置、回收越界刻度。
     */
    fun translateBy(dx: Float, dy: Float) {
        if (dx != 0f) xLabels.forEach { it.pos += dx }
        if (dy != 0f) yLabels.forEach { it.pos += dy }
        moveZero()
        refreshVisibility()
    }

    /**
     * 双指缩放：先按焦点缩放所有刻度，再在越界时换一档步长。
     *
     * 参考实现（`dealScale`）的思路是「屏幕上的网格间距始终保持在
     * [idealPosUnit, 2×idealPosUnit) 之间」，越界就把刻度值减半/加倍，
     * 同时重排刻度位置。这里用同样的规则，只是重排得更直接：
     * 保持 0 那条刻度的位置不动，按新间距把刻度铺回去。
     */
    fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        scaleAxis(X, focusX, factor)
        scaleAxis(Y, focusY, factor)
        // 两轴的格子间距是同一个值（参考实现里初始就相等，缩放系数也一样），
        // 所以换挡只做一次，两个方向的数值步长一起变。
        posUnit *= factor
        if (posUnit <= 0f || !posUnit.isFinite()) {
            reset()
            return
        }
        var guard = 0
        while (posUnit >= 2f * idealPosUnit && guard++ < 16) {
            posUnit /= 2f
            labelUnitX /= 2f
            labelUnitY /= 2f
        }
        guard = 0
        while (posUnit < idealPosUnit && guard++ < 16) {
            posUnit *= 2f
            labelUnitX *= 2f
            labelUnitY *= 2f
        }
        relayout(X)
        relayout(Y)
        moveZero()
        refreshVisibility()
    }

    private fun scaleAxis(axis: Int, focus: Float, factor: Float) {
        labels(axis).forEach { it.pos = focus + (it.pos - focus) * factor }
        setZero(axis, focus + (currentZero(axis) - focus) * factor)
    }

    /** 按当前 [posUnit] 把刻度重新铺一遍，保持 0 刻度（[zeroX]/[zeroY]）不动。 */
    private fun relayout(axis: Int) {
        val labels = labels(axis)
        val labelUnit = if (axis == X) labelUnitX else labelUnitY
        val zero = if (axis == X) zeroX else zeroY
        val anchorIndex = labels.size / 2
        labels.forEachIndexed { index, label ->
            val offset = (index - anchorIndex).toFloat()
            label.pos = zero + offset * posUnit
            // 注意：0 这条必须写成**正零**。`0f * (-2f)` 在浮点里是 `-0.0`，
            // 而刻度文字是 `%.4g`，会格式化成 `-0`——那样 0 刻度就认不出来，
            // 横轴不会画成实线，文字也会偏上（参考实机在这个位置是实线 + "0"）。
            label.label = if (offset == 0f) 0f else offset * labelUnit
        }
        if (axis == X) minIdxX = 0 else minIdxY = 0
    }

    private fun currentZero(axis: Int): Float = if (axis == X) zeroX else zeroY

    private fun setZero(axis: Int, value: Float) {
        if (axis == X) zeroX = value else zeroY = value
    }

    private fun refreshVisibility(axis: Int) {
        val labels = labels(axis)
        val unit = posUnit
        val labelUnit = if (axis == X) labelUnitX else labelUnitY
        val adjust = labels.size * unit
        val labelAdjust = labels.size * labelUnit
        var state = 1
        for (i in labels.size - 1 downTo 0) {
            val label = sorted(axis, i)
            var pos = label.pos
            var text = label.label
            var hide = false
            when (state) {
                0 -> if (pos < idealMin[axis]) state--
                -1 -> {
                    pos += adjust
                    if (pos > idealMax[axis]) hide = true else text += labelAdjust
                }
                else -> if (pos <= idealMax[axis]) {
                    state--
                } else {
                    pos -= adjust
                    if (pos < idealMin[axis]) hide = true else text -= labelAdjust
                }
            }
            label.visible = !hide
            if (!hide) {
                label.pos = pos
                label.label = text
            }
        }
        adjustMinIndex(axis)
    }

    /** 把下标挪到位置最小的那条刻度上（越界回收之后顺序会变）。 */
    fun adjustMinIndex(axis: Int) {
        val labels = labels(axis)
        var min = 0
        var minPos = labels[0].pos
        for (i in labels.indices) {
            if (labels[i].pos < minPos) {
                minPos = labels[i].pos
                min = i
            }
        }
        if (axis == X) minIdxX = min else minIdxY = min
    }

    private fun sorted(axis: Int, index: Int): Label {
        val labels = labels(axis)
        val min = if (axis == X) minIdxX else minIdxY
        return labels[(min + index) % labels.size]
    }

    private fun labels(axis: Int): ArrayList<Label> = if (axis == X) xLabels else yLabels

    // ---------- 坐标换算 ----------

    /** 屏幕像素 -> 数学坐标。 */
    fun toCoordX(px: Float): Float = (px - originX) / ratioX + labelX0

    fun toCoordY(py: Float): Float = (py - originY) / ratioY + labelY0

    /** 数学坐标 -> 屏幕像素。 */
    fun toDisplayX(x: Float): Float = (x - labelX0) * ratioX + originX

    fun toDisplayY(y: Float): Float = (y - labelY0) * ratioY + originY

    /** 排在最前的那条刻度决定的参考点（换算的基准）。 */
    val originX: Float get() = sorted(X, 0).pos
    val labelX0: Float get() = sorted(X, 0).label
    val originY: Float get() = sorted(Y, 0).pos
    val labelY0: Float get() = sorted(Y, 0).label
    val ratioX: Float get() = posUnit / labelUnitX
    val ratioY: Float get() = posUnit / labelUnitY

    companion object {
        const val X = 0
        const val Y = 1
        const val X_COUNT = 8
        const val Y_COUNT = 15
        const val X_LABEL_MIN = -4f
        const val Y_LABEL_MIN = 8f
        const val DEFAULT_LABEL_UNIT_X = 2f
        const val DEFAULT_LABEL_UNIT_Y = -2f

        /** 参考实现里的 `R.integer.defaultBigGraphLabel`：粗估不到范围时的兜底值。 */
        const val DEFAULT_BIG_LABEL = 100f

        /** 参考实现 `roughEstimateGraph()` 的采样间距（屏幕像素）。 */
        const val ESTIMATE_STEP = 250f

        /** 刻度文字：`%.4g` 去尾零，和参考实现一致。 */
        fun formatLabel(value: Float): String {
            val text = String.format("%.4g", value)
            if (!text.contains('.') || text.contains('e') || text.contains('E')) return text
            return text.trimEnd('0').trimEnd('.')
        }
    }
}
