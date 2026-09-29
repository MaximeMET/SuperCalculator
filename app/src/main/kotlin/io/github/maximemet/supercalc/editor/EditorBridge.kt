package io.github.maximemet.supercalc.editor

import android.webkit.JavascriptInterface

/**
 * WebView 里的编辑器和 Kotlin 之间唯一的通道。
 *
 * 这边对应参考实现里的 `MathQuillView` 注入的那套接口：
 *
 * - `autoResult(symja, latex)`：编辑器算好引擎输入后**同步**要结果。
 *   原版就是同步的（`setFormulaAndroid(symja, latex)` 之后立刻 `getResultAndroid()`），
 *   所以这里也会阻塞 JS 线程等引擎算完。
 * - `onEditorReady()`：页面加载完、命令层注册好了，这时才能开始下发按键。
 * - `onHistoryChanged(undo, redo)`：编辑器自己的撤销栈变了，用来控制工具条按钮。
 * - `log(msg)`：编辑器里的异常往 Logcat 抛，方便查线上问题。
 *
 * 注意：`@JavascriptInterface` 的方法都在 WebView 的 JavaBridge 线程上被调用，
 * 不是主线程，实现里要自己切回去。
 */
class EditorBridge(
    private val autoResult: (symja: String, latex: String) -> String,
    private val onEditorReady: () -> Unit,
    private val onHistoryChanged: (canUndo: Boolean, canRedo: Boolean) -> Unit,
    private val onLog: (String) -> Unit,
) {

    @JavascriptInterface
    fun autoResult(symja: String, latex: String): String = autoResult.invoke(symja, latex)

    @JavascriptInterface
    fun onEditorReady() = onEditorReady.invoke()

    @JavascriptInterface
    fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) =
        onHistoryChanged.invoke(canUndo, canRedo)

    @JavascriptInterface
    fun log(message: String) = onLog.invoke(message)
}
