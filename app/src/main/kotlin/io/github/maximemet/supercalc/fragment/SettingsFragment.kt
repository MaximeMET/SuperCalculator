package io.github.maximemet.supercalc.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.AboutActivity
import io.github.maximemet.supercalc.MainActivity
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.databinding.FragmentSettingsBinding
import io.github.maximemet.supercalc.settings.AppSettings
import io.github.maximemet.supercalc.widget.PreferenceDialog

/**
 * 设置页。
 *
 * 四项设置和参考实现一一对应：
 *   * 字体大小 —— 存显示文案「大/中/小」，落到编辑器和结果页的 textZoom；
 *   * 举例展示 —— 关掉之后空公式时那行示例不显示；
 *   * 保留小数位 —— 4..11，直接改引擎精度；
 *   * 过程展示 —— 原版是联网取解题步骤，服务器已下线，这里只存开关。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFontSizeRow()
        setupFractionRow()
        setupSwitches()
        binding.tvCheckUpdate.setOnClickListener { checkUpdate() }
        binding.tvAbout.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    private fun refreshValues() {
        if (_binding == null) return
        val fontRow = binding.rowFontSize
        (fontRow.tvPrefContent as TextView).text = AppSettings.fontSize
        val fractionRow = binding.rowFraction
        (fractionRow.tvPrefContent as TextView).text = AppSettings.precision.toString()
        binding.cbExampleVisibility.isChecked = AppSettings.exampleVisible
        binding.cbProcessVisibility.isChecked = AppSettings.processVisible
    }

    private fun setupFontSizeRow() {
        val row = binding.rowFontSize
        (row.tvPrefTitle as TextView).setText(R.string.setting_font_size)
        row.root.setOnClickListener {
            PreferenceDialog(
                requireContext(),
                getString(R.string.setting_font_size),
                AppSettings.FONT_VALUES,
                AppSettings.FONT_VALUES.indexOf(AppSettings.fontSize),
            ) { index ->
                AppSettings.fontSize = AppSettings.FONT_VALUES[index]
                (row.tvPrefContent as TextView).text = AppSettings.FONT_VALUES[index]
                onSettingsChanged()
            }.show()
        }
    }

    private fun setupFractionRow() {
        val row = binding.rowFraction
        (row.tvPrefTitle as TextView).setText(R.string.setting_fraction)
        row.root.setOnClickListener {
            PreferenceDialog(
                requireContext(),
                getString(R.string.setting_fraction),
                AppSettings.FRACTION_VALUES,
                AppSettings.FRACTION_VALUES.indexOf(AppSettings.precision.toString()),
            ) { index ->
                AppSettings.precision = AppSettings.FRACTION_VALUES[index].toInt()
                (row.tvPrefContent as TextView).text = AppSettings.FRACTION_VALUES[index]
                onSettingsChanged()
            }.show()
        }
    }

    private fun setupSwitches() {
        binding.cbExampleVisibility.setOnClickListener {
            AppSettings.exampleVisible = binding.cbExampleVisibility.isChecked
            onSettingsChanged()
        }
        binding.cbProcessVisibility.setOnClickListener {
            AppSettings.processVisible = binding.cbProcessVisibility.isChecked
            onSettingsChanged()
        }
    }

    private fun checkUpdate() {
        // 参考实现在这里问 http://<更新服务器>/update.html，服务器早就没了；
        // 这个版本发布在 GitHub，直接告诉用户去看 Releases。
        Toast.makeText(requireContext(), R.string.about_version_newest, Toast.LENGTH_SHORT).show()
    }

    private fun onSettingsChanged() {
        (activity as? MainActivity)?.onSettingsChanged()
    }
}
