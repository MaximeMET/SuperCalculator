package io.github.maximemet.supercalc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
        startEngine()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            applyKeyboardHeight(view.height - bars.top - bars.bottom)
            insets
        }
    }

    /**
     * 键盘占窗口高度的一半。
     *
     * 参考实现的 `KeyboardLayout.onMeasure` 直接改写了测量结果：
     * `高度 = 窗口高度 * keyboardHeightScreenPercent / 100`，那个百分比就是 50。
     * 所以键盘不是固定 400dp——屏幕越高键盘越高。
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
    }

    private fun editorAvailable(available: Boolean) {
        binding.keyboardScroll.alpha = if (available) 1f else 0.4f
    }

    // ---------- 引擎 ----------

    private fun startEngine() {
        binding.result.text = getString(R.string.engine_starting)
        engineExecutor.execute {
            val started = runCatching { CalculationSession() }
            val loaded = started.getOrNull()
            val error = started.exceptionOrNull()
            mainHandler.post {
                if (loaded == null) {
                    engineError = error?.toString() ?: "unknown"
                    binding.result.text = "引擎启动失败：$engineError"
                    return@post
                }
                session = loaded
                binding.result.text = ""
                editor.refresh()
            }
        }
    }

    /**
     * 编辑器要结果：这条调用是从 WebView 的 JS 线程进来的，**必须同步返回**，
     * 因为编辑器那边的渲染是紧接着这次调用做的。所以这里丢给引擎线程算完再等它。
     */
    private fun engineAutoResult(symja: String, latex: String): String {
        val current = session ?: return ""
        val token = ++previewToken
        val task = engineExecutor.submit(
            Callable {
                val preview = runCatching {
                    current.setFormula(stripTrailingOperator(symja), latex)
                    current.autoResult()
                }.getOrDefault("")
                val methods = runCatching { current.availableMethods() }.getOrDefault(emptyList())
                mainHandler.post {
                    if (token == previewToken) renderMethods(methods)
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

    private fun renderMethods(methods: List<Method>) {
        val container = binding.calculatorOps
        container.removeAllViews()
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        for (method in methods) {
            val button = Button(this, null, 0)
            button.setText(method.label)
            button.setBackgroundResource(R.drawable.bg_method_button)
            button.setTextColor(ContextCompat.getColor(this, R.color.capsule_button_text))
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
        binding.result.text = getString(R.string.calculating)
        engineExecutor.execute {
            val current = session
            if (current == null) {
                return@execute
            }
            val output = runCatching { current.evaluate(method) }.getOrNull()
            mainHandler.post {
                if (token != previewToken) return@post
                binding.result.text = output ?: getString(R.string.no_result)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"

        /** 等引擎的时间上限。超时说明引擎卡了，先给个空结果别把 JS 线程吊死。 */
        const val ENGINE_TIMEOUT_MS = 3000L

        const val KEY_LEFT = "Left"
        const val KEY_RIGHT = "Right"
        const val KEY_BACKSPACE = "Backspace"

        /** 参考实现的 keyboardHeightScreenPercent。 */
        const val KEYBOARD_HEIGHT_PERCENT = 50
    }
}
