package io.github.maximemet.supercalc.view

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * 永不弹系统输入法的 WebView。
 *
 * 公式全部由页内自绘键盘输入，但 WebView 里只要有元素拿到焦点（MathQuill 的光标
 * 就是一个隐藏 textarea），系统就会把输入法顶上来盖住自绘键盘——原版没有这个现象。
 *
 * 覆盖 `onCreateInputConnection` 返回 null：系统拿不到输入连接就不会弹输入法，
 * 触摸、滚动、JS 侧的焦点与光标全都照常工作。`onCheckIsTextEditor` 一并回答否，
 * 免得系统在窗口重新获得焦点时又来问一次。
 */
class NoImeWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? = null

    override fun onCheckIsTextEditor(): Boolean = false
}
