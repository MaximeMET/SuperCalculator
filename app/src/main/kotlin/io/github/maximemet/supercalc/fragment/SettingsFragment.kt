package io.github.maximemet.supercalc.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.AboutActivity
import io.github.maximemet.supercalc.BuildConfig
import io.github.maximemet.supercalc.MainActivity
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.databinding.FragmentSettingsBinding
import io.github.maximemet.supercalc.engine.RulePacks
import io.github.maximemet.supercalc.engine.UpdateManifests
import io.github.maximemet.supercalc.settings.AppSettings
import io.github.maximemet.supercalc.update.RuleStore
import io.github.maximemet.supercalc.update.UpdateClient
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
        val context = requireContext().applicationContext
        Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            val manifest = UpdateClient.fetchManifest()
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                if (manifest == null) {
                    Toast.makeText(requireContext(), R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                } else {
                    showUpdate(manifest)
                }
            }
        }.start()
    }

    /**
     * 一次检查同时看应用本体和规则包：
     *  - 应用有新版本 → 提示 + 「去下载新版本」（开浏览器到 Releases）；
     *  - 规则包有新版本 → 就地下载、验签、安装，立即生效，不需要升级应用。
     */
    private fun showUpdate(manifest: UpdateManifests.Manifest) {
        val appUpdate = manifest.app?.takeIf {
            UpdateManifests.appUpdateAvailable(BuildConfig.VERSION_CODE, it)
        }
        val rulesUpdate = manifest.rules?.takeIf {
            UpdateManifests.rulesUpdateAvailable(RulePacks.activeVersion, it)
        }
        if (appUpdate == null && rulesUpdate == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME, RulePacks.activeVersion),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val lines = mutableListOf<String>()
        if (appUpdate != null) {
            lines += getString(
                R.string.update_app_line,
                appUpdate.versionName,
                BuildConfig.VERSION_NAME,
                appUpdate.notes,
            )
        }
        if (rulesUpdate != null) {
            lines += getString(R.string.update_rules_line, RulePacks.activeVersion, rulesUpdate.version)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_available_title)
            .setMessage(lines.joinToString("\n\n"))
            .setNegativeButton(R.string.update_later, null)
        if (rulesUpdate != null) {
            dialog.setPositiveButton(R.string.update_rules_now) { _, _ -> updateRules(rulesUpdate) }
        }
        if (appUpdate != null) {
            dialog.setNeutralButton(R.string.update_open_download) { _, _ -> openDownload(appUpdate.url) }
        }
        dialog.show()
    }

    private fun updateRules(update: UpdateManifests.RulesUpdate) {
        val context = requireContext().applicationContext
        Toast.makeText(context, R.string.update_rules_working, Toast.LENGTH_SHORT).show()
        Thread {
            val json = UpdateClient.fetchPackFile(update.jsonUrl)
            val signature = UpdateClient.fetchPackFile(update.sigUrl)
            val ok = json != null && signature != null && RuleStore.save(context, json, signature)
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                val message = if (ok) {
                    getString(R.string.update_rules_done, RulePacks.activeVersion)
                } else {
                    getString(R.string.update_rules_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun openDownload(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSettingsChanged() {
        (activity as? MainActivity)?.onSettingsChanged()
    }
}
