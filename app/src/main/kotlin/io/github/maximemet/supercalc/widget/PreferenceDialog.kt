package io.github.maximemet.supercalc.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.TextView
import io.github.maximemet.supercalc.R

/**
 * 设置项的选择对话框，对应参考实现的 `PreferenceDialog`：
 * 一个单选的 ListView + 取消/确定，只有点「确定」才会把新值回调出去。
 *
 * 选项行用的是 `item_preference_option.xml`（左边圆圈，选中变红）。
 */
class PreferenceDialog(
    context: Context,
    private val dialogTitle: String,
    private val entries: List<String>,
    private val checkedIndex: Int,
    private val onChange: (Int) -> Unit,
) : Dialog(context) {

    private var selectedIndex = checkedIndex

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_preference)
        findViewById<TextView>(R.id.tv_dialog_title).text = dialogTitle

        val list = findViewById<ListView>(R.id.lv_content)
        list.adapter = OptionAdapter()
        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.setOnItemClickListener { _, _, position, _ -> selectedIndex = position }
        list.post { list.setItemChecked(checkedIndex, true) }

        findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_confirm).setOnClickListener {
            if (selectedIndex != checkedIndex && selectedIndex >= 0) {
                onChange(selectedIndex)
            }
            dismiss()
        }
    }

    private inner class OptionAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: android.view.LayoutInflater.from(context)
                    .inflate(R.layout.item_preference_option, parent, false)
            val text = view as CheckedTextView
            text.text = entries[position]
            text.isChecked = position == selectedIndex
            return view
        }
    }
}
