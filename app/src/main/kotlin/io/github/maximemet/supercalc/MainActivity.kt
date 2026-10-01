package io.github.maximemet.supercalc

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.databinding.ActivityMainBinding
import io.github.maximemet.supercalc.databinding.ItemDrawerBinding
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.fragment.CalculatorFragment
import io.github.maximemet.supercalc.fragment.FeedbackFragment
import io.github.maximemet.supercalc.fragment.HistoryFragment
import io.github.maximemet.supercalc.fragment.SettingsFragment
import io.github.maximemet.supercalc.fragment.TutorialFragment
import io.github.maximemet.supercalc.settings.AppSettings

/**
 * 主界面外壳。
 *
 * 结构照参考实现的 `MainActivity`：一个抽屉 + 一条公共工具条 + 一个内容容器，
 * 页面之间用 Fragment 的 hide/show 切换（不销毁，公式状态因此得以保留）。
 *
 * 和原版一致的两处小脾气：
 *   * 抽屉项点下去先关抽屉，250ms 之后再切页面；
 *   * 返回键：历史页回计算页，其它页连按两次退出。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentItem = ITEM_CALCULATOR
    private var lastBackPressedTime = -3000L

    private val fragments = mutableMapOf<Int, Fragment>()
    private lateinit var calculator: CalculatorFragment

    /** 抽屉里每一项：标题 + 图标 (+ 页面)。没有页面的就是纯动作（比如分享）。 */
    private data class NavItem(
        val id: Int,
        val titleRes: Int,
        val iconRes: Int,
        val fragment: (() -> Fragment)? = null,
    )

    private val mainItems by lazy {
        listOf(
            NavItem(ITEM_CALCULATOR, R.string.navigation_calculator, R.drawable.ic_drawer_calculator),
            NavItem(ITEM_TUTORIAL, R.string.navigation_tutorial, R.drawable.ic_drawer_tutorial),
            NavItem(ITEM_SETTINGS, R.string.navigation_settings, R.drawable.ic_drawer_settings),
            NavItem(
                ITEM_FEEDBACK,
                R.string.navigation_feedback,
                R.drawable.ic_drawer_feedback,
            ) { FeedbackFragment() },
            NavItem(ITEM_SHARE, R.string.navigation_shareme, R.drawable.ic_drawer_share),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自己处理系统栏内边距。targetSdk 35 上系统会强制边到边，
        // 不处理的话工具条会钻到状态栏底下、键盘会被导航栏盖住。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppSettings.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDrawer()
        setupToolbar()
        // 计算页永远是第一个建的：insets 和工具条都要用到它
        calculator = CalculatorFragment()
        fragments[ITEM_CALCULATOR] = calculator
        wireCalculator(calculator)
        setupInsets()
        showPage(ITEM_CALCULATOR, animate = false)
    }

    // ---------- 系统栏 ----------

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.statusBarScrim.layoutParams =
                binding.statusBarScrim.layoutParams.apply { height = bars.top }
            binding.navBarScrim.layoutParams =
                binding.navBarScrim.layoutParams.apply { height = bars.bottom }
            binding.toolbar.layoutParams =
                (binding.toolbar.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                    topMargin = bars.top
                }
            // 抽屉的标题栏也要让开状态栏，白色背景仍然铺到最上面
            binding.drawerView.drawerContainer.setPadding(0, bars.top, 0, 0)
            lastBottomInset = bars.bottom
            calculator.applyInsets(bars.bottom, view.height)
            insets
        }
    }

    // ---------- 抽屉 ----------

    private fun setupDrawer() {
        val inflater = LayoutInflater.from(this)
        for (item in mainItems) {
            binding.drawerView.drawerGroupMain.addView(
                buildDrawerRow(inflater, binding.drawerView.drawerGroupMain, item),
            )
        }
        selectDrawerItem(currentItem)
    }

    private fun buildDrawerRow(
        inflater: LayoutInflater,
        parent: android.widget.LinearLayout,
        item: NavItem,
    ): View {
        // 必须带上 parent：不然 inflate 出来的根视图没有 LayoutParams，
        // 56dp 的行高会被丢掉（图标就会挤在一起）
        val row = ItemDrawerBinding.inflate(inflater, parent, false)
        row.drawerItemIcon.setImageResource(item.iconRes)
        row.drawerItemTitle.setText(item.titleRes)
        row.root.setOnClickListener {
            // 参考实现：先关抽屉，250ms 之后再切
            binding.drawerLayout.closeDrawer(binding.drawerView.drawerContainer)
            mainHandler.postDelayed({ onDrawerItemClicked(item) }, 250L)
        }
        row.root.tag = item.id
        return row.root
    }

    private fun onDrawerItemClicked(item: NavItem) {
        // 「推荐给朋友」不是页面，选了它之后抽屉里的高亮要留在原来那一项
        if (item.id == ITEM_SHARE) {
            selectDrawerItem(currentItem)
            shareApp()
            return
        }
        selectDrawerItem(item.id)
        showPage(item.id)
    }

    private fun selectDrawerItem(id: Int) {
        val group = binding.drawerView.drawerGroupMain
        for (i in 0 until group.childCount) {
            val row = group.getChildAt(i)
            row.isSelected = row.tag == id
            row.setBackgroundResource(
                if (row.tag == id) R.color.navigation_item_active else R.color.white,
            )
        }
        currentItem = id
    }

    // ---------- 工具条 ----------

    private fun setupToolbar() {
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(binding.drawerView.drawerContainer) }
        binding.btnBack.setOnClickListener { goBackFromHistory() }
        binding.btnOverflow.setOnClickListener { view -> showHistoryMenu(view) }
        binding.btnUndo.setOnClickListener { calculator.undo() }
        binding.btnRedo.setOnClickListener { calculator.redo() }
        binding.btnHistory.setOnClickListener { openHistory() }
    }

    private fun openHistory() {
        showPage(ITEM_HISTORY)
    }

    /**
     * 计算页那行「全部举例」。
     *
     * 参考实现里这个按钮就是切到抽屉的 nav_tutorial（教程页，里面是全部例题），
     * 所以这里也走同一套「选中抽屉项 + 切页」，抽屉高亮跟着一起动。
     */
    fun openTutorial() {
        selectDrawerItem(ITEM_TUTORIAL)
        showPage(ITEM_TUTORIAL)
    }

    private fun goBackFromHistory() {
        if (currentItem != ITEM_HISTORY) return
        selectDrawerItem(ITEM_CALCULATOR)
        showPage(ITEM_CALCULATOR)
    }

    /** 历史页右上角那个 ⋮：参考实现里就是「清空」和「分享」两项。 */
    @SuppressLint("PrivateResource")
    private fun showHistoryMenu(anchor: View) {
        // 参考实现的溢出菜单是「主题色的底 + 白字」（ActionBarPopupThemeOverlay）
        val themed = android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_SuperCalc_Popup)
        val popup = androidx.appcompat.widget.PopupMenu(themed, anchor)
        popup.menu.add(0, MENU_CLEAR, 0, R.string.result_op_clear)
        popup.menu.add(0, MENU_SHARE, 1, R.string.op_share)
        popup.setOnMenuItemClickListener { item ->
            val fragment = fragments[ITEM_HISTORY] as? HistoryFragment ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                MENU_CLEAR -> fragment.clearHistory()
                MENU_SHARE -> fragment.shareHistory()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    // ---------- 页面切换 ----------

    private fun showPage(id: Int, animate: Boolean = true) {
        val fragment = fragments.getOrPut(id) { createFragment(id) }

        val transaction = supportFragmentManager.beginTransaction()
        for ((otherId, other) in fragments) {
            if (otherId != id) transaction.hide(other)
        }
        if (!fragment.isAdded) {
            transaction.add(R.id.content_frame, fragment, id.toString())
        }
        transaction.show(fragment)
        transaction.commit()

        fragments[id] = fragment
        currentItem = id
        updateToolbar(id)
        if (fragment is CalculatorFragment) {
            fragment.applyInsets(lastBottomInset, binding.root.height)
        }
        if (fragment is HistoryFragment) {
            fragment.reload()
        }
    }

    private fun createFragment(id: Int): Fragment = when (id) {
        ITEM_TUTORIAL -> TutorialFragment()
        ITEM_SETTINGS -> SettingsFragment()
        ITEM_FEEDBACK -> FeedbackFragment()
        ITEM_HISTORY -> HistoryFragment()
        else -> calculator
    }

    private var lastBottomInset = 0

    private fun updateToolbar(id: Int) {
        val title = when (id) {
            ITEM_TUTORIAL -> R.string.navigation_tutorial
            ITEM_SETTINGS -> R.string.navigation_settings
            ITEM_FEEDBACK -> R.string.navigation_feedback
            ITEM_HISTORY -> R.string.navigation_history
            else -> R.string.title_calculate
        }
        binding.toolbarTitle.setText(title)

        val isHistory = id == ITEM_HISTORY
        val isCalculator = id == ITEM_CALCULATOR
        binding.btnMenu.visibility = if (isHistory) View.GONE else View.VISIBLE
        binding.btnBack.visibility = if (isHistory) View.VISIBLE else View.GONE
        binding.btnOverflow.visibility = if (isHistory) View.VISIBLE else View.GONE
        binding.btnHistory.visibility = if (isCalculator) View.VISIBLE else View.GONE
        binding.btnUndo.visibility = if (isCalculator) View.VISIBLE else View.GONE
        binding.btnRedo.visibility = if (isCalculator) View.VISIBLE else View.GONE
    }

    /** 计算页要的工具条状态（撤销/重做可点与否），由 Fragment 那边回传。 */
    private fun wireCalculator(fragment: CalculatorFragment) {
        fragment.onUndoRedoChanged = { canUndo, canRedo ->
            binding.btnUndo.isEnabled = canUndo
            binding.btnRedo.isEnabled = canRedo
        }
        fragment.onMethodsChanged = { methods: List<Method> -> fragment.renderMethods(methods) }
        binding.btnUndo.isEnabled = false
        binding.btnRedo.isEnabled = false
    }

    /** 设置页改了东西：转发给计算页，让它把字号和示例行重新刷一遍。 */
    fun onSettingsChanged() {
        calculator.onSettingsChanged()
    }

    /** 历史页 / 教程页点了公式：填进编辑器并切回计算页。 */
    fun openFormulaFromHistory(latex: String) {
        calculator.setFormulaFromHistory(latex)
        selectDrawerItem(ITEM_CALCULATOR)
        showPage(ITEM_CALCULATOR)
    }

    // ---------- 返回键 ----------

    @Deprecated("参考实现就是直接覆盖 onBackPressed")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(binding.drawerView.drawerContainer)) {
            binding.drawerLayout.closeDrawer(binding.drawerView.drawerContainer)
            return
        }
        if (currentItem == ITEM_HISTORY) {
            goBackFromHistory()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPressedTime < 3000) {
            finish()
            return
        }
        lastBackPressedTime = now
        toast(getString(R.string.exit_twice))
    }

    // ---------- 杂项 ----------

    protected fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "Share")
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_slogon)))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ITEM_CALCULATOR = 1
        const val ITEM_TUTORIAL = 2
        const val ITEM_SETTINGS = 3
        const val ITEM_FEEDBACK = 4
        const val ITEM_SHARE = 5
        const val ITEM_HISTORY = 6

        private const val MENU_CLEAR = 101
        private const val MENU_SHARE = 102
    }
}
