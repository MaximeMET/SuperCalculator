package io.github.maximemet.supercalc.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 一行放 [numPerRow] 个、超了自动换行的布局。
 *
 * 对应参考实现的 `MultipleLineLayout`：每行 4 个，格子等宽，
 * 行距写死 40px（原版就是个裸的 40，不是 dp）。分享对话框的应用网格用它。
 *
 * 原版的 `onMeasure` 里 `Math.ceil(有效子视图数 / 每行个数)` 是**整数除法**——
 * 5 个渠道时算出 1 行，第二行会被裁掉一截。这里是按浮点算的（等价于原版想写的样子），
 * 属于修 bug，其余排版规则照抄。
 */
class MultipleLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    var numPerRow: Int = 4
        set(value) {
            field = value
            requestLayout()
        }

    private val visibleCount: Int
        get() = (0 until childCount).count { getChildAt(it).visibility != View.GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val rows = if (numPerRow <= 0) 0 else (visibleCount + numPerRow - 1) / numPerRow
        val cellWidth = (widthSize - numPerRow * VIEW_MARGIN) / numPerRow
        val cellSpec = MeasureSpec.makeMeasureSpec(cellWidth.coerceAtLeast(0), MeasureSpec.AT_MOST)
        var childHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(cellSpec, heightMeasureSpec)
            childHeight = maxOf(childHeight, child.measuredHeight)
        }
        setMeasuredDimension(widthSize, (childHeight + VIEW_MARGIN) * rows)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childWidth = (right - left) / numPerRow
        var row = 0
        var position = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val width = child.measuredWidth
            val height = child.measuredHeight
            val x = (position - numPerRow * row) * childWidth + (childWidth - width) / 2
            val y = (row + 1) * (height + VIEW_MARGIN)
            child.layout(x, y - height, x + width, y)
            position++
            if (position >= (row + 1) * numPerRow) row++
        }
    }

    private companion object {
        /** 原版写死的就是 40px（不是 dp）。 */
        const val VIEW_MARGIN = 40
    }
}
