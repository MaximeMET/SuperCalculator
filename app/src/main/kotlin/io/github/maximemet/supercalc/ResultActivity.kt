package io.github.maximemet.supercalc

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityResultBinding
import io.github.maximemet.supercalc.engine.LatexText
import io.github.maximemet.supercalc.engine.Method
import io.github.maximemet.supercalc.settings.AppSettings
import org.json.JSONObject

/**
 * 运算结果页。
 *
 * 对应参考实现的 `CalculatorResultActivity`：进来时带公式、方法名和结果，
 * 用 WebView 里的 MathJax 排版；底部三个按钮把「继续编辑 / 清空 / 用结果继续运算」
 * 三条命令回传给计算页。
 *
 * 参考实现往服务器要「运算过程」再塞进同一个 WebView（`__Result.setProcess`）；
 * 服务下线后这份过程改成计算页那边离线算好（见 `SolveSteps`），
 * 通过 [EXTRA_PROCESS] 带进来，这里只负责转交给页面。
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private lateinit var webView: WebView
    private var newEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        newEnabled = intent.getBooleanExtra(EXTRA_NEW_ENABLED, true)
        setupInsets()
        setupToolbar()
        setupWebView()
        setupOperations()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.resultRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.statusBarScrim.layoutParams =
                binding.statusBarScrim.layoutParams.apply { height = bars.top }
            binding.navBarScrim.layoutParams =
                binding.navBarScrim.layoutParams.apply { height = bars.bottom }
            binding.resultToolbar.layoutParams =
                (binding.resultToolbar.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                    .apply { topMargin = bars.top }
            // 白色内容区里的底部操作条要落在导航栏之上，参考实现那会系统已经让开了。
            val padding = resources.getDimensionPixelSize(R.dimen.space_huge)
            binding.resultContent.setPadding(
                binding.resultContent.paddingLeft,
                binding.resultContent.paddingTop,
                binding.resultContent.paddingRight,
                padding + bars.bottom,
            )
            insets
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareResult() }
    }

    private fun setupOperations() {
        binding.btnNew.isEnabled = newEnabled
        if (!newEnabled) {
            binding.btnNew.alpha = 0.5f
        }
        binding.btnResume.setOnClickListener { finishWith(CMD_RESUME, null) }
        binding.btnClear.setOnClickListener { finishWith(CMD_CLEAR, null) }
        binding.btnNew.setOnClickListener {
            finishWith(CMD_NEW, intent.getStringExtra(EXTRA_RESULT).orEmpty())
        }
    }

    private fun finishWith(command: Int, formula: String?) {
        val data = Intent()
        data.putExtra(EXTRA_BACK_CMD, command)
        if (formula != null) {
            data.putExtra(EXTRA_BACK_FORMULA, formula)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    /**
     * 分享。原版是把这个式子传给服务器换一条分享链接（服务已下线），
     * 这里退化成系统分享纯文本，语义上还是「把这个结果分享出去」。
     */
    private fun shareResult() {
        val formula = intent.getStringExtra(EXTRA_LATEX).orEmpty()
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val text = getString(R.string.share_result_prefix) + formula + " = " + result
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.op_share)))
    }

    private fun setupWebView() {
        webView = binding.resultWebview
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        // 原版结果页也吃「字体大小」这一项：大/中/小 → textZoom 120/100/80
        AppSettings.init(this)
        webView.settings.textZoom = AppSettings.fontZoom
        webView.isVerticalScrollBarEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "结果页加载完成: $url")
                pushResult()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() >= ConsoleMessage.MessageLevel.WARNING) {
                    Log.w(TAG, "结果页控制台: ${message.message()} @${message.lineNumber()}")
                }
                return true
            }
        }
        webView.loadUrl("file:///android_asset/result/result.html")
    }

    /** 把公式/方法名/结果/提示塞给页面里的 `window.__Result.setResult`。 */
    private fun pushResult() {
        // 编辑器给的 LaTeX 里 `\slash`（键盘那个 ÷ 键）MathJax 不认，先换掉
        val latex = LatexText.editorSafe(intent.getStringExtra(EXTRA_LATEX).orEmpty())
        val label = intent.getStringExtra(EXTRA_METHOD).orEmpty() +
            getString(R.string.result_label_suffix)
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val tip = tipFor(intent.getStringExtra(EXTRA_METHOD_KEY).orEmpty())
        val script = "window.__Result.setResult(${quote(latex)},${quote(label)}," +
            "${quote(result)},${quote(tip)})"
        webView.evaluateJavascript(script) { value ->
            Log.d(TAG, "setResult -> $value")
        }
        pushProcess()
    }

    /**
     * 把解题步骤交给页面。
     *
     * 内容是计算页生成好的 JSON（`{"steps":[...]}`），这里先解析一遍再注入：
     * 万一以后哪条链路搞坏了，也不会往页面里塞一段非法脚本。
     */
    private fun pushProcess() {
        val raw = intent.getStringExtra(EXTRA_PROCESS) ?: return
        if (raw.isEmpty()) return
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return
        webView.evaluateJavascript("window.__Result.setProcess($parsed)") { value ->
            Log.d(TAG, "setProcess -> $value")
        }
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    /**
     * 方法级提示（参考实现 `mTipMap`）。
     *
     * 原版提示里内嵌了 tipimg 目录下的位图，那些素材不能带进仓库，
     * 这里只保留文字部分。
     */
    private fun tipFor(methodKey: String): String {
        val res = when (methodKey) {
            "Integrate" -> R.string.tip_integrate
            "Diff" -> R.string.tip_diff
            "Solve" -> R.string.tip_solve
            "Solve2" -> R.string.tip_setsolve
            "DInte" -> R.string.tip_ninte
            else -> return ""
        }
        return stripImages(getString(res))
    }

    private fun stripImages(html: String): String =
        html.replace(Regex("<img[^>]*>"), "")

    override fun onDestroy() {
        (binding.resultWebview as WebView).destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ResultActivity"
        const val REQUEST_CODE = 1024

        const val EXTRA_LATEX = "latex"
        const val EXTRA_METHOD = "method_label"
        const val EXTRA_METHOD_KEY = "method_key"
        const val EXTRA_RESULT = "result"
        const val EXTRA_NEW_ENABLED = "btn_new_enable"
        const val EXTRA_PROCESS = "process_steps"

        const val EXTRA_BACK_CMD = "back_cmd"
        const val EXTRA_BACK_FORMULA = "back_formula"

        const val CMD_CLEAR = 1
        const val CMD_RESUME = 2
        const val CMD_NEW = 3

        /**
         * 结果能不能直接拿来继续运算。
         *
         * 参考实现把 Solve/Solve2/SolveIneq/SolveIneq2/Draw 排除掉了，
         * 这几个算出来的是「解集 / 图像」而不是可继续计算的表达式。
         */
        fun allowsReuse(method: Method): Boolean {
            return when (method) {
                Method.Solve,
                Method.Solve2,
                Method.SolveIneq,
                Method.SolveIneq2,
                Method.Draw -> false

                else -> true
            }
        }
    }
}
