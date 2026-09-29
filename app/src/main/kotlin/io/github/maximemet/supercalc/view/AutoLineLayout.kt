package io.github.maximemet.supercalc.view

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import io.github.maximemet.supercalc.R
import kotlin.math.max

/**
 * 固定列数的自动换行布局。
 *
 * 参考实现里的 `AutoLineLayout` 就是干这个的：把子视图按 [columns] 列排开，
 * 每列等宽，超出的部分换到下一行。运算按钮排出来就是整齐的 4 列。
 */
class AutoLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns: Int = 4
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var rowSpace: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var columnSpace: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var rowHeight = 0

    init {
        context.obtainStyledAttributes(attrs, R.styleable.AutoLineLayout).apply {
            columns = getInt(R.styleable.AutoLineLayout_columns, columns)
            rowSpace = getDimensionPixelSize(R.styleable.AutoLineLayout_rowSpace, rowSpace)
            columnSpace = getDimensionPixelSize(R.styleable.AutoLineLayout_columnSpace, columnSpace)
            recycle()
        }
    }

    private val rowCount: Int
        get() = if (columns <= 0) 0 else (childCount + columns - 1) / columns

    private fun cellWidth(available: Int): Int {
        if (columns <= 0) return available
        val gaps = columnSpace * (columns - 1)
        return max(0, (available - gaps) / columns)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val cell = cellWidth(available)

        var maxRowHeight = 0
        val cellSpec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        val freeSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(cellSpec, freeSpec)
            maxRowHeight = max(maxRowHeight, child.measuredHeight)
        }
        rowHeight = maxRowHeight

        val rows = rowCount
        val gaps = if (rows > 1) rowSpace * (rows - 1) else 0
        val height = paddingTop + paddingBottom + rows * maxRowHeight + gaps
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (columns <= 0) return
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val cell = cellWidth(available)

        var index = 0
        for (row in 0 until rowCount) {
            val top = paddingTop + row * (rowHeight + rowSpace)
            for (column in 0 until columns) {
                if (index >= childCount) return
                val child = getChildAt(index)
                val left = paddingLeft + column * (cell + columnSpace)
                child.layout(left, top, left + cell, top + child.measuredHeight)
                index++
            }
        }
    }
}
