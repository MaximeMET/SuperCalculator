package io.github.maximemet.supercalc.fragment

import android.app.Activity
import android.content.Intent
import android.graphics.LightingColorFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import io.github.maximemet.supercalc.GraphActivity
import io.github.maximemet.supercalc.MainActivity
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.ResultActivity
import io.github.maximemet.supercalc.databinding.FragmentCalculatorBinding
import io.github.maximemet.supercalc.editor.EditorBridge
import io.github.maximemet.supercalc.editor.MathEditor
import io.github.maximemet.supercalc.engine.CalculationSession
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.history.HistoryStore
import io.github.maximemet.supercalc.history.HistoryType
import io.github.maximemet.supercalc.keyboard.KeyboardModel
import io.github.maximemet.supercalc.settings.AppSettings
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
class CalculatorFragment : Fragment() {

    private var _binding: FragmentCalculatorBinding? = null
    private val binding: FragmentCalculatorBinding get() = _binding!!

    private lateinit var editor: MathEditor

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "supercalc-engine")
    }

    /** 引擎就绪前一直是 null，界面会提示"引擎启动中"。 */
    @Volatile
    private var session: CalculationSession? = null

    /** 键盘上那个剪贴板槽里存的 latex：点一下会填回公式。 */
    private var clipboardLatex = ""

    /**
     * 自动结果的版本号：引擎算完回来时对不上就说明公式已经变了，直接丢弃。
     * 递增既发生在 JS 桥线程（[engineAutoResult]），也发生在主线程（公式清空时），
     * 所以用原子计数，避免两边同时自增互相覆盖、让旧结果蒙混过关。
     */
    private val previewToken = AtomicInteger(0)

    /** 公式为空时左下角那行示例。 */
    private var currentExample: Example? = null

    /** 当前这条示例在 [EMPTY_EXAMPLES] 里的下标，左右滑动时在它上面加减。 */
    private var exampleIndex = 0

    /** 示例行当前是不是显示着（用来只在「显示 → 隐藏」那一帧重抽）。 */
    private var exampleTipVisible = false

    /** 编辑器那侧回传的最近状态，工具条和示例行都读它。 */
    private var formulaEmpty = true
    private var undoAvailable = false
    private var redoAvailable = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCalculatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEditor()
        setupKeyboard()
        setupExamples()
        startEngine()
        // insets 可能比 Fragment 的视图先到，这里补一次
        if (pendingWindowHeight > 0) applyInsets(pendingBottomInset, pendingWindowHeight)
    }

    override fun onDestroy() {
        engineExecutor.shutdown()
        _binding?.editor?.let { (it as WebView).destroy() }
        _binding = null
        super.onDestroy()
    }

    // ---------- 给外面用的几个口子 ----------

    /** 主界面拿到 insets 后转给键盘：底部要避开导航栏，高度按窗口高度的一半。 */
    fun applyInsets(bottomInset: Int, windowHeight: Int) {
        pendingBottomInset = bottomInset
        pendingWindowHeight = windowHeight
        if (_binding == null) return
        binding.mathKeyboard.layoutParams =
            (binding.mathKeyboard.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = bottomInset
            }
        applyKeyboardHeight(windowHeight - bottomInset)
    }

    private var pendingBottomInset = 0
    private var pendingWindowHeight = 0

    /** 设置页改了字体大小 / 举例展示之后通知过来。 */
    fun onSettingsChanged() {
        if (_binding == null) return
        (binding.editor as WebView).settings.textZoom = AppSettings.fontZoom
        editor.reflow()
        updateExampleVisibility(formulaEmpty)
    }

    /** 历史页点了一条记录。 */
    fun setFormulaFromHistory(latex: String) {
        editor.setLatex(latex.replace("\\newline", "\\newline "))
    }

    val canUndo: Boolean get() = undoAvailable
    val canRedo: Boolean get() = redoAvailable

    fun undo() = editor.undo()

    fun redo() = editor.redo()

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
        if (available <= 0 || _binding == null) return
        val target = available * KEYBOARD_HEIGHT_PERCENT / 100
        val params = binding.mathKeyboard.layoutParams
        if (params.height != target) {
            params.height = target
            binding.mathKeyboard.layoutParams = params
            editor.reflow()
        }
    }

    // ---------- 编辑器 ----------

    private fun setupEditor() {
        (binding.editor as WebView).settings.textZoom = AppSettings.fontZoom
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
                        undoAvailable = canUndo
                        redoAvailable = canRedo
                        onUndoRedoChanged?.invoke(canUndo, canRedo)
                    }
                },
                onCopyNumericResult = ::putNumericResultOnClipboardKey,
                onSetResult = { latex ->
                    // 用户把结果当公式接着算了：参考实现在这里补一条 type=0 的记录
                    Log.d(TAG, "结果被当成公式复用: $latex")
                    saveRecord(lastFormula, lastLatex, HistoryType.NORMAL)
                },
                onFormulaEmpty = { empty ->
                    mainHandler.post {
                        formulaEmpty = empty
                        if (empty) {
                            // 公式清空时方法按钮不能等引擎回包再收：引擎一来一回要几百毫秒，
                            // 而示例行这一帧就出来了，两行会叠在一起。
                            // 同一帧里先作废在途结果、清掉按钮，再显示示例行。
                            previewToken.incrementAndGet()
                            renderMethods(emptyList())
                        }
                        updateExampleVisibility(empty)
                    }
                },
                onLog = { message -> Log.w(TAG, "编辑器: $message") },
            ),
        )
        editor.onReady = { editorAvailable(true) }
        editorAvailable(false)

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

    /** 撤销/重做按钮的可用状态由外面（工具条）决定。 */
    var onUndoRedoChanged: ((Boolean, Boolean) -> Unit)? = null

    private fun editorAvailable(available: Boolean) {
        binding.keyboardScroll.alpha = if (available) 1f else 0.4f
    }

    // ---------- 引擎 ----------

    /** 最近一次交给引擎的公式（记历史用）。 */
    private var lastFormula: String = ""
    private var lastLatex: String = ""

    private fun startEngine() {
        editor.setStatus(getString(R.string.engine_starting))
        engineExecutor.execute {
            val started = runCatching { CalculationSession() }
            val loaded = started.getOrNull()
            val error = started.exceptionOrNull()
            mainHandler.post {
                if (_binding == null) return@post
                if (loaded == null) {
                    editor.setStatus("引擎启动失败：${error?.toString() ?: "unknown"}")
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
            if (_binding != null) binding.keyClipboard.text = symja
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
        val token = previewToken.incrementAndGet()
        val formula = fixImplicitProduct(stripTrailingOperator(symja))
        val task = engineExecutor.submit(
            Callable {
                current.setFormula(formula, latex)
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
                    if (token != previewToken.get() || _binding == null) return@post
                    onMethodsChanged?.invoke(methods)
                    // 原版在方法按钮里出现「继续计算」时会顺带弹一句提示
                    if (Method.Calc in methods) {
                        Toast.makeText(
                            requireContext(),
                            R.string.toast_result_too_slow,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                // 记历史：参考实现在 getResult 拿到非空、且不是单个分隔符时记一条 type=5
                if (preview.isNotEmpty() && preview != DIVIDER) {
                    lastFormula = formula
                    lastLatex = latex
                    saveRecord(formula, latex, HistoryType.HAS_RESULT, preview.replace(DIVIDER, ""))
                }
                preview
            },
        )
        return runCatching { task.get(ENGINE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault("")
    }

    /** 方法按钮列表变化时通知工具条外的东西（其实是 MainActivity 在用）。 */
    var onMethodsChanged: ((List<Method>) -> Unit)? = null

    private fun saveRecord(
        formula: String,
        latex: String,
        type: Int,
        result: String? = null,
    ) {
        if (formula.isEmpty() || latex.isEmpty()) return
        val ctx = context ?: return
        runCatching { HistoryStore.get(ctx).add(formula, latex, type, result) }
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
        // 参考实现是整组先设常态图标，再把当前页那张换成按下态（KeyboardConsts 里那两组 drawable）
        val normal = intArrayOf(
            R.drawable.ic_keyboard_book1,
            R.drawable.ic_keyboard_book2,
            R.drawable.ic_keyboard_book3,
            R.drawable.ic_keyboard_book4,
        )
        val pressed = intArrayOf(
            R.drawable.ic_dart_pressed_1,
            R.drawable.ic_dart_pressed_2,
            R.drawable.ic_dart_pressed_3,
            R.drawable.ic_dart_pressed_4,
        )
        darts.forEachIndexed { index, dart ->
            dart.setImageResource(if (index == selected) pressed[index] else normal[index])
        }
    }

    // ---------- 方法按钮 ----------

    fun renderMethods(methods: List<Method>) {
        if (_binding == null) return
        val container = binding.calculatorOps
        container.removeAllViews()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        for (method in methods) {
            val button = Button(requireContext(), null, 0)
            button.setText(method.label)
            // 原版：胶囊 drawable 上套 LightingColorFilter(-1, method.color) 给边框上色，
            // 文字直接用同一个颜色。每个方法的颜色是规格的一部分，不能统一成黑色。
            val capsule = ContextCompat.getDrawable(requireContext(), R.drawable.bg_method_button)!!.mutate()
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

    fun runMethod(method: Method) {
        // 「绘制图像」不进结果页：原版直接把公式交给图像页，记一条 type=25 的历史就跳走
        if (method == Method.Draw) {
            val current = session ?: return
            if (current.formula.isEmpty()) return
            saveRecord(current.formula, current.latex, method.typeCode)
            startActivity(
                Intent(requireContext(), GraphActivity::class.java)
                    .putExtra(
                        GraphActivity.EXTRA_SYMJA_FORMAT,
                        Method.drawFormula(current.formula, current.lastFormula),
                    )
                    .putExtra(GraphActivity.EXTRA_SYMJA_LATEX, current.latex)
            )
            return
        }
        val token = previewToken.incrementAndGet()
        editor.setStatus(getString(R.string.calculating))
        engineExecutor.execute {
            val current = session
            if (current == null) return@execute
            val output = runCatching { current.evaluate(method) }.getOrNull()
            val latex = current.latex
            val formula = current.formula
            // 参考实现在方法任务的 onPostExecute 里记历史（不带结果）
            saveRecord(formula, latex, method.typeCode)
            mainHandler.post {
                if (token != previewToken.get() || _binding == null) return@post
                editor.setStatus("")
                if (output == null) {
                    Toast.makeText(requireContext(), R.string.no_result, Toast.LENGTH_SHORT).show()
                    return@post
                }
                startResultPage(method, latex, output)
            }
        }
    }

    /** 打开运算结果页（参考实现的 CalculatorResultActivity，请求码 1024）。 */
    private fun startResultPage(method: Method, latex: String, result: String) {
        val intent = Intent(requireContext(), ResultActivity::class.java)
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
                if (formula.isNotEmpty()) editor.setLatex(formula)
            }
        }
    }

    // ---------- 空公式时的示例行 ----------

    /**
     * 空公式时底下那行示例。
     *
     * 参考实现这行是一个横向 ViewPager，装的九张预渲染位图，文案和算式都烤在图里。
     * 位图素材要全部换掉，所以这里按图里的内容自己写：左边白字「标签：算式 ⇒ 答案」，
     * 右边橙字「全部举例」。
     *
     * 算式不在这里排版 —— 交给编辑器 WebView 里的 MathQuill 静态域渲染（见
     * [MathEditor.setExampleTip]）。直接把文字排出来没有真正的数学排版：双下标会挤在
     * 一起、分数只能用斜杠，和原版位图差着一眼。
     */
    private fun setupExamples() {
        binding.viewEmpty.setOnClickListener {
            currentExample?.let { editor.setLatex(it.latex) }
        }
        // 原版这行是 ViewPager：点一下把示例填进编辑器，左右滑翻下一条/上一条
        // （CalculatorFragment$3.onPageSelected 会同步 mCurEmptyTipIdx）。
        // 自己判手势：过 touch slop 就算翻页（ViewPager 也是这样，不要求甩得够快），
        // 没过就是点击。
        binding.viewEmpty.setOnTouchListener { view, event -> handleExampleTouch(view, event) }
        // 算式要居中在「全部举例」左边那块空白里，所以得知道按钮多宽；按钮宽度
        // 要等布局完成，这里挂一次布局回调，顺带把示例推到编辑器页。
        binding.tvExample.doOnLayout { showRandomExample() }
        // 「全部举例」= 切到教程页（参考实现点它走的就是抽屉的 nav_tutorial）
        binding.tvExample.setOnClickListener {
            (activity as? MainActivity)?.openTutorial()
        }
        updateExampleVisibility(formulaEmpty)
    }

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchDragged = false

    /** 示例行上的手势：轻点填公式，横向拖动翻页（原版 ViewPager 的手感）。 */
    private fun handleExampleTouch(view: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                touchDragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchDragged) {
                    touchDragged = Math.abs(event.x - touchStartX) > slop ||
                        Math.abs(event.y - touchStartY) > slop
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (touchDragged && Math.abs(dx) > Math.abs(dy)) {
                    // 手指往左滑 = 看下一条（和 ViewPager 翻页方向一致）
                    stepExample(if (dx < 0) 1 else -1)
                } else if (!touchDragged) {
                    view.performClick()
                }
                touchDragged = false
            }
            MotionEvent.ACTION_CANCEL -> touchDragged = false
        }
        return true
    }

    /** 随机抽一条（原版 randTipId + setCurrentItem）。 */
    private fun showRandomExample() {
        exampleIndex = EMPTY_EXAMPLES.indices.random()
        applyExample()
    }

    /** 翻到相邻的一条，绕回到另一端。 */
    private fun stepExample(delta: Int) {
        val count = EMPTY_EXAMPLES.size
        exampleIndex = ((exampleIndex + delta) % count + count) % count
        applyExample()
    }

    private fun applyExample() {
        val example = EMPTY_EXAMPLES[exampleIndex]
        currentExample = example
        pushExampleTip(example)
    }

    /** 把示例推给编辑器页（算式渲染 + 右边留出「全部举例」的宽度）。 */
    private fun pushExampleTip(example: Example) {
        // 算式要居中在「全部举例」左边那块空白里，所以把按钮宽度（px → dp）传过去，
        // 让编辑器页把右边的位置留出来。
        val insetDp = binding.tvExample.width / resources.displayMetrics.density
        editor.setExampleTip(example.label, example.tipLatex, insetDp)
    }

    /**
     * 「举例展示」开关关掉之后这行整个不显示。
     *
     * 隐藏的瞬间要重新抽一条 —— 原版 hideEmptyTip() 里就是
     * `mCurEmptyTipIdx = randTipId()`，所以清空公式之后看到的示例会换一条；
     * 只在 Fragment 创建时抽一次的话，整个进程里都盯着同一条不动。
     * 抽完不立刻排版：那时候行还是 GONE，量不出宽度，等下次显示时再推。
     */
    private fun updateExampleVisibility(editorEmpty: Boolean) {
        if (_binding == null) return
        val visible = editorEmpty && AppSettings.exampleVisible
        if (exampleTipVisible && !visible) {
            // 只换下标，不在这里排版：这会儿行还是隐藏的，量不出宽度
            exampleIndex = EMPTY_EXAMPLES.indices.random()
            currentExample = EMPTY_EXAMPLES[exampleIndex]
        }
        exampleTipVisible = visible
        binding.viewEmptyContainer.visibility = if (visible) View.VISIBLE else View.GONE
        editor.setExampleTipVisible(visible)
        if (visible) currentExample?.let { pushExampleTip(it) }
    }

    /**
     * 一条示例。
     *
     * [label] 是中文小标题，[tipLatex] 是「算式 ⇒ 结果」——用 MathQuill 静态域渲染，
     * 和编辑器里的公式是同一套排版。[latex] 是点一下要填进编辑器的公式。
     */
    data class Example(val label: String, val tipLatex: String, val latex: String)

    private companion object {
        const val TAG = "CalculatorFragment"

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

        /** 结果串里精确解和数值解之间的分隔符，参考实现里就是 `$$`。 */
        const val DIVIDER = "$$"

        /**
         * 空公式时那九条示例。
         *
         * 九条内容和参考实现那九张位图一一对应（顺序也一样），只是算式改用 LaTeX：
         * 位图里积分有上下限、分数是堆叠的，用文字直接排只能排出 ∫₀¹ 和 5/12，
         * 所以算式交给 MathQuill 静态域渲染（字号和整体缩放见 editor.css / fitExampleTip）。
         *
         * 写法上要注意两处，都是这套 MathQuill 的脾气（改之前先用
         * work/tmp/make_example_probe.py 的解析检查过一遍）：
         *   * `\int` / `\lim` 是 editor.js 里注册的**自定义命令**，槽位形状固定，
         *     所以积分必须写完整的 `\int_{下限}^{上限}{被积函数}d{x}`；
         *   * 单独写 `\{` 解析器不认（要 `\lbrace`）；两行高的那种括号也不能用
         *     `\left\{`（它是把字符纵向拉伸，拉出来没有腰），要用 editor.js 里
         *     注册的自绘括号 `\sysbrace{上行\newline 下行}`。
         *   * 数学模式里的逗号和空格都会被吞掉（`a,b` 渲染成 `ab`），要写成
         *     `\text{, }`。
         */
        val EMPTY_EXAMPLES = listOf(
            Example("求导：", "x^{3}\\Rightarrow 3x^{2}", "x^3"),
            Example("化简：", "\\frac{5}{12}-\\frac{1}{8}=\\frac{7}{24}", "\\frac{5}{12}-\\frac{1}{8}"),
            Example("定积分数值解：", "\\int_{0}^{1}{x}d{x}=0.5", "\\int_{0}^{1}{x}d{x}"),
            Example("绘制图像：", "y=x^{2}+2x\\Rightarrow\\parabola", "x^2+2x"),
            Example(
                "求解方程组：",
                "\\sysbrace{30x+15y=675\\newline 42x+20y=940}" +
                    "\\sysarrow\\sysbrace{x\\to 20\\newline y\\to 5}",
                "30x+15y=675\\newline 42x+20y=940",
            ),
            Example("求解方程：", "x^{2}+2x+1=0\\Rightarrow x\\to -1", "x^2+2x+1=0"),
            Example("多项式分解：", "x^{4}-1\\Rightarrow(x-1)(x+1)(x^{2}+1)", "x^4-1"),
            Example(
                "多项式展开：",
                "(1+x^{2})(1+x^{4})\\Rightarrow 1+x^{2}+x^{4}+x^{6}",
                "(1+x^2)(1+x^4)",
            ),
            Example("积分：", "x\\Rightarrow\\frac{x^{2}}{2}+C", "x"),
        )
    }
}
