package io.github.maximemet.supercalc.view

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.maximemet.supercalc.R

/**
 * 分享渠道选择框。对应参考实现的 `ImgTxtChooserDialog`：
 * 把所有能收 `ACTION_SEND` + 图片类型（image 通配）的应用按图标 + 名字列出来，
 * 底部一个「取消」。
 *
 * 和原版的两点差别都是系统版本逼出来的：
 *  - 图片用 FileProvider 的 content://（原版是 `Uri.fromFile`，Android 7 起会直接抛
 *    FileUriExposedException）；
 *  - 原版在 Activity 里缓存了一个静态对话框 + 后台线程（为了「先弹框、图片慢慢存」），
 *    这里图已经存好了，构造时直接查一次候选应用。
 */
class ShareChooserDialog(
    private val host: Activity,
    private val imageUri: Uri,
) : Dialog(host, R.style.DialogTheme) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_share_chooser)

        val container = findViewById<ViewGroup>(R.id.share_layout)
        val tip = findViewById<View>(R.id.share_tip_text)
        val empty = findViewById<View>(R.id.empty_text)
        findViewById<View>(R.id.share_none).setOnClickListener { dismiss() }

        val send = sendIntent()
        val targets = host.packageManager.queryIntentActivities(send, 0)
        if (targets.isEmpty()) {
            // 一个能分享的应用都没有：藏掉提示，换成「没装任何分享渠道」
            tip.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        val inflater = LayoutInflater.from(host)
        targets.forEach { info ->
            val item = inflater.inflate(R.layout.item_share_app, container, false)
            item.findViewById<ImageView>(R.id.app_icon)
                .setImageDrawable(info.loadIcon(host.packageManager))
            item.findViewById<TextView>(R.id.app_name).text = info.loadLabel(host.packageManager)
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            item.setOnClickListener { shareTo(component) }
            container.addView(item)
        }
    }

    private fun sendIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun shareTo(component: ComponentName) {
        val send = sendIntent().apply { this.component = component }
        runCatching { host.startActivity(send) }
            .onFailure { host.runCatching { dismiss() } }
        dismiss()
    }
}
