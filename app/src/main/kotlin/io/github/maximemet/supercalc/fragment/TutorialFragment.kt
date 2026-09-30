package io.github.maximemet.supercalc.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.MainActivity
import io.github.maximemet.supercalc.databinding.FragmentTutorialBinding

/**
 * 教程页。
 *
 * 参考实现也是一个 WebView，页面在 `assets/tutorial/tutorial.html`，
 * 里面用 `window.__HistoryCtrl.clickFormula(latex)` 回调 Android 把示例公式填进编辑器。
 * 内容和素材是我们自己重写的（原版那几张位图属于要替换的素材）。
 */
class TutorialFragment : Fragment() {

    private var _binding: FragmentTutorialBinding? = null
    private val binding get() = _binding!!

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTutorialBinding.inflate(inflater, container, false)
        val webView = binding.tutorialWebview as WebView
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        webView.loadUrl(URL)
        return binding.root
    }

    override fun onDestroy() {
        _binding?.tutorialWebview?.let { (it as WebView).destroy() }
        _binding = null
        super.onDestroy()
    }

    /** 教程里点一条示例：把公式填进计算页并切过去。 */
    @JavascriptInterface
    fun clickFormula(latex: String) {
        activity?.runOnUiThread {
            (activity as? MainActivity)?.openFormulaFromHistory(latex)
        }
    }

    companion object {
        private const val BRIDGE_NAME = "__HistoryCtrl"
        private const val URL = "file:///android_asset/tutorial/tutorial.html"
    }
}
