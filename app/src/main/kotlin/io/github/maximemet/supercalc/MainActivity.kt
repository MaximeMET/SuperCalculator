package io.github.maximemet.supercalc

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityMainBinding
import io.github.maximemet.supercalc.editor.PlainTextKeyWriter
import io.github.maximemet.supercalc.engine.CalculationSession
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.engine.MethodConsts
import io.github.maximemet.supercalc.keyboard.KeyboardModel
import java.util.concurrent.Executors

/**
 * 计算页。
 *
 * 结构上对应参考实现的 `CalculatorFragment`：编辑器 + 运算按钮 + 键盘。
 * 引擎全部跑在单独一条线程上，因为 Symja 初始化要几秒，而且它本身不是线程安全的。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyWriter: PlainTextKeyWriter

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

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastText = ""
    private var restoring = false
    private var previewRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自己处理系统栏内边距。targetSdk 35 上系统会强制边到边，
        // 不处理的话工具条会钻到状态栏底下、键盘会被导航栏盖住。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyWriter = PlainTextKeyWriter(binding.editor)
        binding.editor.showSoftInputOnFocus = false

        setupInsets()
        setupToolbar()
        setupKeyboard()
        setupEditor()
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
        }
    }

    override fun onDestroy() {
        previewRunnable?.let { mainHandler.removeCallbacks(it) }
        engineExecutor.shutdown()
        super.onDestroy()
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
                runPreview(binding.editor.text.toString())
            }
        }
    }

    // ---------- 工具条 ----------

    private fun setupToolbar() {
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRedo.setOnClickListener { redo() }
        // 菜单和历史页还没做，先留空按钮，避免点了没反应让人以为卡住
        binding.btnMenu.setOnClickListener { toastPlaceholder("菜单") }
        binding.btnHistory.setOnClickListener { toastPlaceholder("历史记录") }
    }

    private fun toastPlaceholder(name: String) {
        android.widget.Toast.makeText(this, "$name（还没实现）", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // ---------- 编辑器 ----------

    private fun setupEditor() {
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (restoring) {
                    lastText = text
                    return
                }
                if (text != lastText) {
                    undoStack.addLast(lastText)
                    if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
                    redoStack.clear()
                    lastText = text
                }
                schedulePreview(text)
            }
        })

        binding.keyClear.setOnClickListener { setEditorText("") }
        binding.keyNewline.setOnClickListener { keyWriter.write(KeyboardModel.newlineCommand) }
        binding.keyLeft.setOnClickListener { moveCaret(-1) }
        binding.keyRight.setOnClickListener { moveCaret(1) }
        binding.keyBackspace.setOnClickListener { backspace() }
    }

    private fun moveCaret(delta: Int) {
        val position = (binding.editor.selectionStart + delta)
            .coerceIn(0, binding.editor.text.length)
        binding.editor.setSelection(position)
    }

    private fun backspace() {
        val start = binding.editor.selectionStart
        val end = binding.editor.selectionEnd
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (from != to) {
            binding.editor.text.replace(from, to, "")
            binding.editor.setSelection(from)
        } else if (from > 0) {
            binding.editor.text.replace(from - 1, from, "")
            binding.editor.setSelection(from - 1)
        }
    }

    private fun setEditorText(text: String) {
        restoring = true
        if (text != lastText) {
            undoStack.addLast(lastText)
            redoStack.clear()
        }
        binding.editor.setText(text)
        binding.editor.setSelection(text.length)
        lastText = text
        restoring = false
        schedulePreview(text)
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(lastText)
        restoring = true
        binding.editor.setText(previous)
        binding.editor.setSelection(previous.length)
        lastText = previous
        restoring = false
        schedulePreview(previous)
    }

    private fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(lastText)
        restoring = true
        binding.editor.setText(next)
        binding.editor.setSelection(next.length)
        lastText = next
        restoring = false
        schedulePreview(next)
    }

    // ---------- 键盘 ----------

    private fun setupKeyboard() {
        binding.keyboardScroll.setPages(KeyboardModel.pages) { key ->
            key.command?.let { keyWriter.write(it) }
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

    // ---------- 结果与方法按钮 ----------

    private fun schedulePreview(text: String) {
        previewRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { runPreview(text) }
        previewRunnable = runnable
        mainHandler.postDelayed(runnable, PREVIEW_DELAY_MS)
    }

    private fun runPreview(text: String) {
        val token = ++previewToken
        engineExecutor.execute {
            val current = session
            if (current == null) {
                return@execute
            }
            val formula = stripTrailingOperator(text)
            val preview = runCatching {
                current.setFormula(formula, text)
                current.autoResult()
            }.getOrDefault("")
            val methods = runCatching { current.availableMethods() }.getOrDefault(emptyList())
            mainHandler.post {
                if (token != previewToken) return@post
                showPreview(preview)
                renderMethods(methods)
            }
        }
    }

    /**
     * 参考实现的前端在把公式交给引擎之前，会先砍掉结尾悬空的运算符，
     * 免得刚敲一个 `+` 就报错。这里是同一条规则。
     */
    private fun stripTrailingOperator(text: String): String {
        var result = text
        if (result.isEmpty()) return result
        when (result.last()) {
            '+', '-', '*', '/' -> result = result.dropLast(1)
            '=' -> if (result.endsWith("==")) result = result.dropLast(2)
        }
        return result
    }

    private fun showPreview(preview: String) {
        if (preview.isEmpty()) {
            binding.result.text = ""
            return
        }
        // 预览串的格式是「精确结果 $$ 数值结果」，数值那段本身已经带等号了。
        val parts = preview.split(MethodConsts.DIVIDER)
        binding.result.text =
            if (parts.size > 1 && parts[1].isNotEmpty()) parts[1] else parts[0]
    }

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
        /** 参考实现里前端防抖写的是 500ms。 */
        const val PREVIEW_DELAY_MS = 500L
        const val MAX_UNDO = 100

        /** 参考实现的 keyboardHeightScreenPercent。 */
        const val KEYBOARD_HEIGHT_PERCENT = 50
    }
}
