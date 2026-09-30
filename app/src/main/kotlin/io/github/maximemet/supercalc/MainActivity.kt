package io.github.maximemet.supercalc

import android.app.Activity
import android.content.Intent
import android.graphics.LightingColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityMainBinding
import io.github.maximemet.supercalc.editor.EditorBridge
import io.github.maximemet.supercalc.editor.MathEditor
import io.github.maximemet.supercalc.engine.CalculationSession
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.keyboard.KeyboardModel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 计算页。
 *
 * 结构上对应参考实现的 `CalculatorFragment`：编辑器 + 运算按钮 + 键盘。
 *
 * 公式状态（公式树、光标、撤销栈）全在 WebView 里的 MathQuill 那一侧，
 * 这边只在两条线上打交道：
 *   * 键盘/工具条 → [MathEditor] 下发命令；
 *   * 编辑器算好引擎输入 → [EditorBridge.autoResult] 同步要结果。
 *
 * 引擎全部跑在单独一条线程上，因为 Symja 初始化要几秒，而且它本身不是线程安全的。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: MathEditor

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "supercalc-engine")
    }

    /** 引擎就绪前一直是 null，界面会提示"引擎启动中"。 */
    @Volatile
    private var session: CalculationSession? = null
    private var engineError: String? = null

    /** 键盘上那个剪贴板槽里存的 latex：点一下会填回公式。 */
    private var clipboardLatex = ""

    @Volatile
    private var previewToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自己处理系统栏内边距。targetSdk 35 上系统会强制边到边，
        // 不处理的话工具条会钻到状态栏底下、键盘会被导航栏盖住。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEditor()
        setupInsets()
        setupToolbar()
        setupKeyboard()
        setupExamples()
        startEngine()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 左右还是靠 root 的 padding；上下要分色，所以交给两条色带 + 边距
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.statusBarScrim.layoutParams =
                binding.statusBarScrim.layoutParams.apply { height = bars.top }
            binding.navBarScrim.layoutParams =
                binding.navBarScrim.layoutParams.apply { height = bars.bottom }
            binding.toolbar.layoutParams =
                (binding.toolbar.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    topMargin = bars.top
                }
            binding.mathKeyboard.layoutParams =
                (binding.mathKeyboard.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    bottomMargin = bars.bottom
                }
            applyKeyboardHeight(view.height - bars.bottom)
            insets
        }
    }

    /**
     * 键盘高度 = 窗口高度 / 2。
     *
     * 参考实现是 `KeyboardLayout.onMeasure` 里
     * `DeviceUtils.getWindowHeight() * keyboardHeightScreenPercent / 100`，
     * 外加 `initKeyboard()` 里又写了一遍 `height / 2`。那个 `getWindowHeight()`
     * 走的是 `Display.getSize()`，**不含导航栏、但含状态栏**——所以这里减掉的只有
     * 底部 inset。之前按「可用高度（去掉上下两条）的一半」算，同一台机器上
     * 键盘矮 36px，整块键盘的行高会跟着全错。
     */
    private fun applyKeyboardHeight(available: Int) {
        if (available <= 0) return
        val target = available * KEYBOARD_HEIGHT_PERCENT / 100
        val params = binding.mathKeyboard.layoutParams
        if (params.height != target) {
            params.height = target
            binding.mathKeyboard.layoutParams = params
            editor.reflow()
        }
    }

    override fun onDestroy() {
        engineExecutor.shutdown()
        (binding.editor as WebView).destroy()
        super.onDestroy()
    }

    // ---------- 编辑器 ----------

    private fun setupEditor() {
        editor = MathEditor(
            binding.editor,
            EditorBridge(
                autoResult = ::engineAutoResult,
                onEditorReady = {
                    mainHandler.post {
                        // 先从队列里把开局的按键补上（markReady 会顺带通知 onReady）
                        editor.markReady()
                        // 引擎可能比编辑器晚就绪，就绪后补算一次当前公式
                        if (session != null) editor.refresh()
                    }
                },
                onHistoryChanged = { canUndo, canRedo ->
                    mainHandler.post {
                        binding.btnUndo.isEnabled = canUndo
                        binding.btnRedo.isEnabled = canRedo
                    }
                },
                onCopyNumericResult = ::putNumericResultOnClipboardKey,
                onSetResult = { latex ->
                    // 用户把结果当公式接着算了。原版在这里往历史库里补一条记录，
                    // 历史页还没做（M3），先只落日志。
                    Log.d(TAG, "结果被当成公式复用: $latex")
                },
                onFormulaEmpty = { empty ->
                    mainHandler.post {
                        binding.viewEmptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                },
                onLog = { message -> Log.w(TAG, "编辑器: $message") },
            ),
        )
        editor.onReady = { editorAvailable(true) }
        editorAvailable(false)
        binding.btnUndo.isEnabled = false
        binding.btnRedo.isEnabled = false

        binding.keyClear.setOnClickListener { editor.clear() }
        binding.keyNewline.setOnClickListener { editor.writeCommand(KeyboardModel.newlineCommand) }
        binding.keyLeft.setOnClickListener { editor.keystroke(KEY_LEFT) }
        binding.keyRight.setOnClickListener { editor.keystroke(KEY_RIGHT) }
        binding.keyBackspace.setOnClickListener { editor.keystroke(KEY_BACKSPACE) }
        binding.editor.setOnClickListener { editor.focus() }
        // 键盘上那个剪贴板槽：点一下把上次点方块按钮存下的 latex 填回公式
        binding.keyClipboard.setOnClickListener {
            val latex = clipboardLatex
            if (latex.isNotEmpty()) editor.writeLatex(latex)
        }
    }

    private fun editorAvailable(available: Boolean) {
        binding.keyboardScroll.alpha = if (available) 1f else 0.4f
    }

    // ---------- 引擎 ----------

    private fun startEngine() {
        editor.setStatus(getString(R.string.engine_starting))
        engineExecutor.execute {
            val started = runCatching { CalculationSession() }
            val loaded = started.getOrNull()
            val error = started.exceptionOrNull()
            mainHandler.post {
                if (loaded == null) {
                    engineError = error?.toString() ?: "unknown"
                    editor.setStatus("引擎启动失败：$engineError")
                    return@post
                }
                session = loaded
                editor.setStatus("")
                editor.refresh()
            }
        }
    }

    /**
     * 数值结果右边那个方块按钮：把结果放进键盘上的剪贴板槽。
     *
     * 原版就是这么做的（`copyNumericResult`）：槽里显示纯文本，
     * 点槽的时候是把它对应的 latex 写回公式——不是系统剪贴板。
     */
    private fun putNumericResultOnClipboardKey(symja: String, latex: String) {
        clipboardLatex = latex
        mainHandler.post {
            binding.keyClipboard.text = symja
        }
    }

    /**
     * 编辑器要结果：这条调用是从 WebView 的 JS 线程进来的，**必须同步返回**，
     * 因为编辑器那边的渲染是紧接着这次调用做的。所以这里丢给引擎线程算完再等它。
     *
     * 顺带把运算按钮也定了。参考实现里 `needCalc` 的来路很绕，说到底就一句话：
     * 自动结果 5 秒没算完才给「继续计算(耗时较长)」——
     * setFormula 时先把标志置 true，getResult 只有**超时**才把它改回 false，
     * 方法解析读的就是这个标志。所以 `1/3` 这种秒出的公式根本不会出现那个按钮。
     */
    private fun engineAutoResult(symja: String, latex: String): String {
        val current = session ?: return ""
        val token = ++previewToken
        val task = engineExecutor.submit(
            Callable {
                current.setFormula(fixImplicitProduct(stripTrailingOperator(symja)), latex)
                val startedAt = SystemClock.elapsedRealtime()
                var needCalc = false
                val preview = try {
                    current.autoResult()
                } catch (t: Throwable) {
                    // 原版 getResult 里抛异常同样走「没算完」这条路
                    Log.w(TAG, "自动结果失败", t)
                    needCalc = true
                    ""
                }
                if (SystemClock.elapsedRealtime() - startedAt > AUTO_RESULT_TIMEOUT_MS) {
                    needCalc = true
                }
                val methods = runCatching { current.availableMethods(needCalc) }
                    .getOrDefault(emptyList())
                mainHandler.post {
                    if (token != previewToken) return@post
                    renderMethods(methods)
                    // 原版在方法按钮里出现「继续计算」时会顺带弹一句提示
                    if (Method.Calc in methods) {
                        android.widget.Toast
                            .makeText(
                                this,
                                R.string.toast_result_too_slow,
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                preview
            },
        )
        return runCatching { task.get(ENGINE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault("")
    }

    /**
     * 参考实现的前端在把公式交给引擎之前，会先砍掉结尾悬空的运算符，
     * 免得刚敲一个 `+` 就报错。这里是同一条规则。
     */
    private fun stripTrailingOperator(formula: String): String {
        var result = formula
        if (result.isEmpty()) return result
        when (result.last()) {
            '+', '-', '*', '/' -> result = result.dropLast(1)
            '=' -> if (result.endsWith("==")) result = result.dropLast(2)
        }
        return result
    }

    /**
     * 参考实现的前端在 setFormula 里还会做一次 `)(` → `)*(` 的替换。
     *
     * 命令层已经按各自的模板补过乘号了，但括号挨着括号这种情况（两个括号命令
     * 连着敲）命令层管不到，得在这里兜一下——Symja 不认 `(x+1)(x-1)`。
     */
    private fun fixImplicitProduct(formula: String): String = formula.replace(")(", ")*(")

    // ---------- 工具条 ----------

    private fun setupToolbar() {
        binding.btnUndo.setOnClickListener { editor.undo() }
        binding.btnRedo.setOnClickListener { editor.redo() }
        // 菜单和历史页还没做，先留空按钮，避免点了没反应让人以为卡住
        binding.btnMenu.setOnClickListener { toastPlaceholder("菜单") }
        binding.btnHistory.setOnClickListener { toastPlaceholder("历史记录") }
    }

    private fun toastPlaceholder(name: String) {
        android.widget.Toast.makeText(this, "$name（还没实现）", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // ---------- 键盘 ----------

    private fun setupKeyboard() {
        binding.keyboardScroll.setPages(KeyboardModel.pages) { key ->
            key.command?.let { editor.writeCommand(it) }
        }
        binding.keyboardScroll.onPageChanged = { position -> updateDarts(position) }

        val darts = listOf(binding.dart1, binding.dart2, binding.dart3, binding.dart4)
        darts.forEachIndexed { index, dart ->
            dart.setOnClickListener { binding.keyboardScroll.scrollToPage(index) }
        }
        updateDarts(0)
    }

    private fun updateDarts(selected: Int) {
        val darts = listOf(binding.dart1, binding.dart2, binding.dart3, binding.dart4)
        val selectedBackground = ContextCompat.getDrawable(this, R.drawable.bg_dart_selected)
        val normalBackground = ContextCompat.getDrawable(this, R.drawable.bg_dart_normal)
        val selectedText = ContextCompat.getColor(this, android.R.color.white)
        val normalText = ContextCompat.getColor(this, R.color.dart_normal_text)
        darts.forEachIndexed { index, dart ->
            val active = index == selected
            dart.background = if (active) selectedBackground else normalBackground
            dart.setTextColor(if (active) selectedText else normalText)
        }
    }

    // ---------- 方法按钮 ----------

    /**
     * 空公式时左下角那行示例：点一下把公式填进编辑器。
     *
     * 原版是 9 张位图（`ic_emptytip_0..8`）+ 一张 latex 表，每次随机挑一张。
     * 位图属于必须替换的素材，这里直接用文字写，例题也换成我们自己的写法；
     * 公式本身还是原来那 9 个（它们是功能覆盖点，不是美术素材）。
     */
    /**
     * 空公式时底下那行示例。
     *
     * 参考实现这行是一个横向 ViewPager，装的九张预渲染位图；文案和算式都烤在图里。
     * 位图素材要全部换掉，所以这里按图里的内容自己写：左边白字「标签：算式 ⇒ 答案」，
     * 右边橙字「全部举例」。
     */
    private fun setupExamples() {
        val example = emptyExamples.random()
        binding.viewEmpty.text = example.text
        binding.viewEmpty.setOnClickListener { editor.setLatex(example.latex) }
    }

    private data class Example(val text: String, val latex: String)

    private val emptyExamples = listOf(
        Example("求导：x³ ⇒ 3x²", "x^3"),
        Example("化简：5/12 − 1/8 = 7/24", "\\frac{5}{12}-\\frac{1}{8}"),
        Example("定积分数值解：∫₀¹x dx = 0.5", "\\int_{0}^{1}{x}d{x}"),
        Example("绘制图像：y = x² + 2x ⇒ ∪", "x^2+2x"),
        Example(
            "求解方程组：{30x + 15y = 675, 42x + 20y = 940} ⇒ {x→20, y→5}",
            "30x+15y=675\\newline 42x+20y=940",
        ),
        Example("求解方程：x² + 2x + 1 = 0 ⇒ x → −1", "x^2+2x+1=0"),
        Example("多项式分解：x⁴ − 1 ⇒ (x − 1)(x + 1)(x² + 1)", "x^4-1"),
        Example("多项式展开：(1 + x²)(1 + x⁴) ⇒ 1 + x² + x⁴ + x⁶", "(1+x^2)(1+x^4)"),
        Example("积分：x ⇒ x²/2 + C", "x"),
    )

    private fun renderMethods(methods: List<Method>) {
        val container = binding.calculatorOps
        container.removeAllViews()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        for (method in methods) {
            val button = Button(this, null, 0)
            button.setText(method.label)
            // 原版：胶囊 drawable 上套 LightingColorFilter(-1, method.color) 给边框上色，
            // 文字直接用同一个颜色。每个方法的颜色是规格的一部分，不能统一成黑色。
            val capsule = ContextCompat.getDrawable(this, R.drawable.bg_method_button)!!.mutate()
            capsule.colorFilter = LightingColorFilter(-1, method.color)
            button.background = capsule
            button.setTextColor(method.color)
            button.textSize = 13f
            button.gravity = Gravity.CENTER
            button.minHeight = resources.getDimensionPixelSize(R.dimen.capsule_button_height)
            button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
            button.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            button.setOnClickListener { runMethod(method) }
            container.addView(button)
        }
    }

    private fun runMethod(method: Method) {
        val token = ++previewToken
        editor.setStatus(getString(R.string.calculating))
        engineExecutor.execute {
            val current = session
            if (current == null) {
                return@execute
            }
            val output = runCatching { current.evaluate(method) }.getOrNull()
            val latex = current.latex
            mainHandler.post {
                if (token != previewToken) return@post
                editor.setStatus("")
                if (output == null) {
                    // 参考实现在这里是 toast_result_error，我们沿用自己那条文案
                    Toast.makeText(this, R.string.no_result, Toast.LENGTH_SHORT).show()
                    return@post
                }
                startResultPage(method, latex, output)
            }
        }
    }

    /** 打开运算结果页（参考实现的 CalculatorResultActivity，请求码 1024）。 */
    private fun startResultPage(method: Method, latex: String, result: String) {
        val intent = Intent(this, ResultActivity::class.java)
            .putExtra(ResultActivity.EXTRA_LATEX, latex)
            .putExtra(ResultActivity.EXTRA_METHOD, method.label)
            .putExtra(ResultActivity.EXTRA_METHOD_KEY, method.key)
            .putExtra(ResultActivity.EXTRA_RESULT, result)
            .putExtra(ResultActivity.EXTRA_NEW_ENABLED, ResultActivity.allowsReuse(method))
        @Suppress("DEPRECATION")
        startActivityForResult(intent, ResultActivity.REQUEST_CODE)
    }

    /**
     * 结果页回来的三条命令。
     *
     * 参考实现：CONTINUE(2) 什么都不做；CLEAR(1) 清空公式；NEW(3) 把结果当新公式填回去。
     */
    @Deprecated("参考实现走的就是 startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ResultActivity.REQUEST_CODE || resultCode != Activity.RESULT_OK) {
            return
        }
        when (data?.getIntExtra(ResultActivity.EXTRA_BACK_CMD, ResultActivity.CMD_RESUME)) {
            ResultActivity.CMD_CLEAR -> editor.clear()
            ResultActivity.CMD_NEW -> {
                val formula = data?.getStringExtra(ResultActivity.EXTRA_BACK_FORMULA).orEmpty()
                if (formula.isNotEmpty()) {
                    editor.setLatex(formula)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"

        /**
         * 等引擎的时间上限。参考实现的 JS 接口是同步等的，超时 5000ms 就放弃，
         * 并把「算不完」这个状态回传给方法按钮。
         */
        const val ENGINE_TIMEOUT_MS = 5000L

        /** 自动结果超过这个时间才算「耗时较长」。参考实现里 getResult 的 5 秒上限。 */
        const val AUTO_RESULT_TIMEOUT_MS = 5000L

        const val KEY_LEFT = "Left"
        const val KEY_RIGHT = "Right"
        const val KEY_BACKSPACE = "Backspace"

        /** 参考实现的 keyboardHeightScreenPercent。 */
        const val KEYBOARD_HEIGHT_PERCENT = 50
    }
}
