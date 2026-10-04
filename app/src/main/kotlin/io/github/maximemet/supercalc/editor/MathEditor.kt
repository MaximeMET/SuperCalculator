package io.github.maximemet.supercalc.editor

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.maximemet.supercalc.keyboard.CommandType
import io.github.maximemet.supercalc.keyboard.KeyCommand
import org.json.JSONObject

/**
 * 公式编辑器：包着 `assets/matheditor/editor.html` 的 WebView。
 *
 * 键盘、工具条、撤销重做全部走 JS 那一侧的 `window.SuperCalcEditor`，
 * Kotlin 这边只负责「发命令」和「把结果算出来」，不碰公式状态。
 * 这么分是有依据的：参考实现里编辑状态（公式树、光标、撤销栈）也都在 WebView 里，
 * Android 侧只有一个 `setFormulaAndroid(symja, latex)` 的入口。
 *
 * 页面加载完之前按下的键会先排队，等 `onEditorReady` 之后一起补上——
 * WebView 冷启动要几百毫秒，不排队的话开局敲的几下就丢了。
 */
class MathEditor(
    private val webView: WebView,
    bridge: EditorBridge,
) {

    /** 编辑器就绪。在这之前不要指望能读到公式。 */
    var onReady: (() -> Unit)? = null

    private val pending = ArrayDeque<String>()
    private var ready = false

    init {
        @Suppress("SetJavaScriptEnabled")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 页面是同目录下的本地文件，要放行 file:// 之间的读取
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            // 编辑器是定高区域，让它自己滚，别把整个页面撑开
            loadWithOverviewMode = false
            useWideViewPort = true
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        // 编辑器里的异常只能从这里看到：不开这个，JS 报错是完全静默的
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "JS ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "编辑器页加载完成")
            }
        }
        webView.loadUrl(EDITOR_URL)
    }

    fun markReady() {
        ready = true
        while (pending.isNotEmpty()) {
            webView.evaluateJavascript(pending.removeFirst(), null)
        }
        onReady?.invoke()
    }

    /**
     * 执行一条按键动作。
     *
     * [symbol] 是按键标识（`KeyItem.symbol`，和原版 `assets/keyboard` 里的一致）。
     * 编辑器拿它做原版同款的前置过滤——三角函数里敲数字自动补度数、
     * ° 去重、退格收拾多余的 °，见 editor.js 的 filterCommand。
     */
    fun writeCommand(command: KeyCommand, symbol: String) {
        val typed = if (isTypedText(command)) "true" else "false"
        val code = JSONObject.quote(command.code)
        call(
            "SuperCalcEditor.writeCommand($code, ${command.cursorBack}, $typed, " +
                "${JSONObject.quote(symbol)})",
        )
    }

    fun keystroke(name: String, times: Int = 1) {
        call("SuperCalcEditor.keystroke(${JSONObject.quote(name)}, $times)")
    }

    fun clear() = call("SuperCalcEditor.clear()")

    fun undo() = call("SuperCalcEditor.undo()")

    fun redo() = call("SuperCalcEditor.redo()")

    fun focus() = call("SuperCalcEditor.focus()")

    /** 重新算一次当前公式（引擎比编辑器晚就绪时用）。 */
    fun refresh() = call("SuperCalcEditor.refresh()")

    /** 把一段 latex 追加进公式（键盘上那个剪贴板槽点一下时用）。 */
    fun writeLatex(latex: String) =
        call("SuperCalcEditor.writeLatex(${JSONObject.quote(latex)})")

    /** 直接把公式设成一段 latex（点示例时用）。 */
    fun setLatex(latex: String) =
        call("SuperCalcEditor.setLatex(${JSONObject.quote(latex)})")

    /** 在公式下面显示一行状态文字；传空串就收起来。 */
    fun setStatus(text: String) =
        call("SuperCalcEditor.setStatus(${JSONObject.quote(text)})")

    /**
     * 空公式时底下那行示例。
     *
     * 渲染在编辑器页里（MathQuill 静态域），所以这里只递 label + 算式 latex，
     * [insetDp] 是右边「全部举例」按钮的宽度，算式在它左边的空白里居中。
     */
    fun setExampleTip(label: String, latex: String, insetDp: Float) = call(
        "SuperCalcEditor.setExampleTip(${JSONObject.quote(label)}, " +
            "${JSONObject.quote(latex)}, $insetDp)",
    )

    /** 显示 / 收起那行示例。 */
    fun setExampleTipVisible(visible: Boolean) =
        call("SuperCalcEditor.setExampleTipVisible($visible)")

    /** 布局变化（键盘高度、旋转）之后让 MathQuill 重排一次。 */
    fun reflow() = call("SuperCalcEditor.reflow()")

    private fun call(js: String) {
        if (!ready) {
            pending.addLast(js)
            return
        }
        webView.evaluateJavascript(js, null)
    }

    companion object {
        private const val TAG = "MathEditor"
        const val BRIDGE_NAME = "Android"
        const val EDITOR_URL = "file:///android_asset/matheditor/editor.html"

        /**
         * 哪些键是「敲一个字符」（`typedText`）而不是「插入一段 LaTeX」（`write`）。
         *
         * 依据是原版 `bundle.min.js` 里的分组：数字/字母/比较号这些 NOOP 键走
         * `u()`=typedText，函数和模板键走 `a()`=write；而分式、括号、乘方
         * 四个键虽然被归进 BLOCK 类型，动作却是 `u()`。
         *
         * 这个区别不能省：`write("(")` 会去 LaTeX 命令表里找名叫 `(` 的命令，
         * 找不到就整段解析失败（静默什么都不插），必须走 typedText 才会自动配对。
         */
        private val TYPED_TEXT_CODES = setOf("/", "(", ")", "^")

        fun isTypedText(command: KeyCommand): Boolean =
            command.type == CommandType.NOOP || command.code in TYPED_TEXT_CODES
    }
}
