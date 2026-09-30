package io.github.maximemet.supercalc.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.MainActivity
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.databinding.FragmentHistoryBinding
import io.github.maximemet.supercalc.history.HistoryStore

/**
 * 历史记录页。
 *
 * 和参考实现一样是个 WebView：页面（`assets/history/history.html`）负责排版和滚动，
 * 数据由 Android 侧现查现给，两边通过 `window.__HistoryCtrl` 通话：
 *
 *   * `getFormulas(endId)` —— 取一页记录（JSON 数组），endId 是上一页最后一条的 id；
 *   * `clickFormula(latex)` —— 点某条记录，回到计算页并把它填进编辑器；
 *   * `resetFinish()` —— 页面挂好了，可以收掉 loading；
 *   * `clickShare(latex, time)` —— 24 点成绩分享用，普通历史里用不到。
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        val webView = binding.historyWebview as WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.addJavascriptInterface(this, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                call("window.__History && window.__History.reset()")
            }
        }
        webView.loadUrl(URL)
        return binding.root
    }

    override fun onDestroy() {
        _binding?.historyWebview?.let { (it as WebView).destroy() }
        _binding = null
        super.onDestroy()
    }

    /** 每次切到这一页都重新拉一遍（可能刚算完新公式）。 */
    fun reload() {
        if (pageReady) call("window.__History && window.__History.reset()")
    }

    fun clearHistory() {
        runCatching { HistoryStore.get(requireContext()).clear() }
        reload()
    }

    /** 参考实现的历史分享是发给服务器的；这里退化成把第一页记录分享成文本。 */
    fun shareHistory() {
        val json = runCatching { HistoryStore.get(requireContext()).page(null, PAGE_SIZE) }
            .getOrDefault("[]")
        val text = buildString {
            append(getString(R.string.share_history_title))
            append('\n')
            append(json)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.op_share)))
    }

    // ---------- 给页面调的接口 ----------

    @JavascriptInterface
    fun getFormulas(endId: String?): String {
        val id = endId?.toLongOrNull()
        val json = runCatching { HistoryStore.get(requireContext()).page(id, PAGE_SIZE) }
            .getOrElse {
                Log.w(TAG, "取历史记录失败", it)
                "[]"
            }
        // 和参考实现一样留一条 FECall 日志，方便对拍
        Log.d("FECall", "getFormulas calling: $endId")
        Log.d("FECall", "getFormulas returned: $json")
        return json
    }

    @JavascriptInterface
    fun clickFormula(latex: String) {
        activity?.runOnUiThread {
            val act = activity as? MainActivity
            if (act == null) {
                Toast.makeText(requireContext(), "抱歉，暂时无法打开此算式", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            act.openFormulaFromHistory(latex)
        }
    }

    @JavascriptInterface
    fun clickShare(latex: String, time: String) {
        // 普通历史里没有这个入口，24 点在 M5 时再接
    }

    @JavascriptInterface
    fun resetFinish() {
        // 页面自己会显示，这里只需要把 WebView 亮出来
        activity?.runOnUiThread { if (_binding != null) binding.historyWebview.visibility = View.VISIBLE }
    }

    private fun call(js: String) {
        if (_binding == null) return
        binding.historyWebview.evaluateJavascript(js, null)
    }

    companion object {
        private const val TAG = "HistoryFragment"
        private const val BRIDGE_NAME = "__HistoryCtrl"
        private const val URL = "file:///android_asset/history/history.html"

        /** 参考实现的 historyPageItemCnt。 */
        private const val PAGE_SIZE = 15
    }
}
