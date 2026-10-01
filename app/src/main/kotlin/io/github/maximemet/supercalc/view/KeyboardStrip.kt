package io.github.maximemet.supercalc.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.keyboard.KeyItem
import io.github.maximemet.supercalc.keyboard.KeyboardModel
import io.github.maximemet.supercalc.keyboard.KeyboardPage

/**
 * 键盘主体。
 *
 * 参考实现里的 `WrappedVerticalViewPager` 并不是真正的 ViewPager，而是把四页
 * **首尾相接排成一条长条**，上下滑动连续浏览，左侧书签直接滚到对应页。这里保留
 * 同样的行为，用 ScrollView 实现。
 *
 * 行高也不是写死的 dp：参考实现算的是「键盘宽度 / 6 + 1dp」，屏幕越宽格子越高。
 * 宽度只有测量之后才知道，所以行高一旦变化就把四页整个重建一遍。
 */
class KeyboardStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var pages: List<KeyboardPage> = emptyList()
    private var onKey: ((KeyItem) -> Unit)? = null
    private var rowHeight = 0
    private val pageOffsets = mutableListOf<Int>()
    private val pageViews = mutableListOf<View>()
    private var pendingPage: Int? = null

    /** 当前页变化时回调，用来同步左侧书签的高亮。 */
    var onPageChanged: ((Int) -> Unit)? = null

    init {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            container,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    /** 铺开四页键盘。 */
    fun setPages(pages: List<KeyboardPage>, onKey: (KeyItem) -> Unit) {
        this.pages = pages
        this.onKey = onKey
        rebuild()
    }

    /** 滚到第 [index] 页的开头。宽度还没测量出来时先记下来，等测量完再滚。 */
    fun scrollToPage(index: Int) {
        if (pageOffsets.size > index && rowHeight > 0) {
            smoothScrollTo(0, pageOffsets[index])
        } else {
            pendingPage = index
        }
    }

    /** 当前停留在第几页（按滚动位置判断）。 */
    fun currentPage(pageCount: Int): Int {
        var result = 0
        for (i in 0 until minOf(pageCount, pageOffsets.size)) {
            if (scrollY >= pageOffsets[i]) result = i
        }
        return result
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 参考实现（WrappedVerticalViewPager.setSubViews）算的是
        // 「窗口宽度 × keyboardPagerWidthPercent / 100 / keyboardGridRatio + keyboard_small_divider」，
        // 用的是窗口宽度而不是本控件的宽度，格子高 = 1088/6 + 3px = 184px。
        val pagerWidth = resources.displayMetrics.widthPixels *
            PAGER_WIDTH_PERCENT / 100
        val newRowHeight = pagerWidth / KeyboardModel.ROW_HEIGHT_RATIO +
            resources.getDimensionPixelSize(R.dimen.space_mini)
        if (newRowHeight != rowHeight) {
            rowHeight = newRowHeight
            // onSizeChanged 是在 layout 过程中被调用的，这时候增删子视图触发的
            // requestLayout 会被系统丢掉，子视图永远不会被测量。所以必须挪到下一帧。
            post { rebuild() }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != oldt) onPageChanged?.invoke(currentPage(pages.size))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateOffsets()
        pendingPage?.let { index ->
            pendingPage = null
            scrollToPage(index)
        }
    }

    private fun rebuild() {
        val keyListener = onKey ?: return
        if (rowHeight <= 0 || pages.isEmpty()) return
        container.removeAllViews()
        pageViews.clear()
        pages.forEachIndexed { index, page ->
            // 栏目之间那条加粗的分隔线。参考实现是每页高度里留 keyboardPageGapHeight=4dp
            // 的空隙加一条 1px 细线，实机上几乎看不出来；用户要求「跨栏目要有横着的、
            // 加粗的分隔线」，所以这里画一条明显的：颜色沿用工具行下面那条，
            // 高度 1.5dp（本机约 4px）。
            if (index > 0) {
                container.addView(
                    View(context).apply {
                        setBackgroundColor(ContextCompat.getColor(context, R.color.gray_divider))
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.keyboard_page_divider_height),
                    ),
                )
            }
            val pageView = buildPage(page, keyListener)
            pageViews.add(pageView)
            container.addView(
                pageView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * 每页的起点用页视图自己的 top。
     *
     * 不能再用「前面所有子视图高度之和」：页与页之间现在夹着分隔线，
     * 累加会把起点算到分隔线上。
     */
    private fun updateOffsets() {
        pageOffsets.clear()
        pageViews.forEach { pageOffsets.add(it.top) }
    }

    private fun buildPage(page: KeyboardPage, keyListener: (KeyItem) -> Unit): View {
        val grid = buildGrid(page.keys, page.columns, keyListener)
        val labelRows = page.labelRows ?: return grid

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.gray_divider))
        }
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labelRows.forEach { (text, weight) ->
            labels.addView(
                TextView(context).apply {
                    this.text = text
                    gravity = Gravity.CENTER
                    setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_grid_bg))
                    setTextColor(ContextCompat.getColor(context, R.color.key_text))
                    textSize = 13f
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight * weight,
                ),
            )
        }
        wrapper.addView(
            labels,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f),
        )
        wrapper.addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.gray_divider))
            },
            LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.space_mini),
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        wrapper.addView(
            grid,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f),
        )
        return wrapper
    }

    private fun buildGrid(
        keys: List<KeyItem>,
        columns: Int,
        keyListener: (KeyItem) -> Unit,
    ): ViewGroup {
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 格子之间的细线是网格底色从缝隙里透出来的，颜色和 gray_divider 不同
            setBackgroundColor(ContextCompat.getColor(context, R.color.key_grid_divider))
        }
        val rows = (keys.size + columns - 1) / columns
        for (row in 0 until rows) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0 until columns) {
                rowView.addView(
                    buildKey(keys.getOrNull(row * columns + column), keyListener),
                    cellParams(firstInRow = column == 0),
                )
            }
            grid.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    // 行与行之间的那条细线：让网格底色从缝隙里透出来
                    if (row > 0) {
                        topMargin = resources.getDimensionPixelSize(R.dimen.space_mini)
                    }
                },
            )
        }
        return grid
    }

    private fun cellParams(firstInRow: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, rowHeight, 1f).apply {
            marginStart = if (firstInRow) 0 else resources.getDimensionPixelSize(R.dimen.space_mini)
        }

    private fun buildKey(key: KeyItem?, keyListener: (KeyItem) -> Unit): View {
        if (key == null || key.isEmpty) {
            return View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_grid_bg))
            }
        }
        val view = LayoutInflater.from(context).inflate(R.layout.item_key, null, false)
        val label = view.findViewById<TextView>(R.id.key_label)
        val iconRes = iconResId(key.icon)
        if (iconRes != 0) {
            view.findViewById<ImageView>(R.id.key_icon).apply {
                setImageResource(iconRes)
                visibility = View.VISIBLE
            }
            label.visibility = View.GONE
        } else {
            label.text = key.label
        }
        view.setOnClickListener { keyListener(key) }
        return view
    }

    /**
     * 图标名 → drawable 资源 id。
     *
     * 原版每个格子都是位图（`view_keyboard_item.xml` 里的 TintImageView），
     * 我们换成矢量 drawable：由 tools/make_keyboard_icons.py 从开源字体生成
     * （工具行/书签的几何图形脚本里自绘），尺寸沿用原图的 px/2 dp（xhdpi 固有尺寸）。
     */
    private fun iconResId(name: String): Int {
        if (name.isEmpty()) return 0
        return iconIds.getOrPut(name) {
            resources.getIdentifier(name, "drawable", context.packageName)
        }
    }

    private val iconIds = HashMap<String, Int>()

    private companion object {
        /** 参考实现的 keyboardPagerWidthPercent。 */
        const val PAGER_WIDTH_PERCENT = 85
    }
}
