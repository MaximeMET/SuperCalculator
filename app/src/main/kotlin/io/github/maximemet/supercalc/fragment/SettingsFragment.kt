package io.github.maximemet.supercalc.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import io.github.maximemet.supercalc.update.ApkInstaller
import io.github.maximemet.supercalc.update.RuleStore
import io.github.maximemet.supercalc.update.UpdateClient
import io.github.maximemet.supercalc.widget.PreferenceDialog
import java.io.File

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
        // 从「安装未知应用」授权页回来：权限给上了就接着装（见 requestInstall）
        pendingApk?.let { apk ->
            if (ApkInstaller.canRequestInstall(requireContext())) launchInstaller(apk)
        }
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
            dialog.setNeutralButton(R.string.update_open_download) { _, _ -> openDownload(appUpdate) }
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

    // ---------- 应用内下载安装 ----------

    /** 下好待安装的包；从「安装未知应用」设置页回来时接着装（见 [onResume]）。 */
    private var pendingApk: File? = null

    /**
     * 清单里有安装包直链就走应用内下载；没有（老清单）就退回浏览器。
     */
    private fun openDownload(update: UpdateManifests.AppUpdate) {
        val apkUrl = update.apkUrl
        if (apkUrl.isNullOrBlank()) {
            openInBrowser(update.url)
            return
        }
        startApkDownload(update, apkUrl)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startApkDownload(update: UpdateManifests.AppUpdate, apkUrl: String) {
        val context = requireContext().applicationContext
        val target = ApkInstaller.targetFile(context, update.versionName)
        val handle = UpdateClient.DownloadHandle()

        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val progressText = TextView(context).apply {
            text = getString(R.string.update_download_progress_unknown)
            textSize = 14f
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, (pad / 2), pad, 0)
            addView(progressText)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad / 2 },
            )
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.update_download_title, update.versionName))
            .setView(content)
            .setNegativeButton(R.string.update_download_cancel, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                handle.cancel()
                dialog.dismiss()
            }
        }
        dialog.show()

        Thread {
            val ok = UpdateClient.download(apkUrl, target, handle) { done, total ->
                activity?.runOnUiThread {
                    if (total > 0) {
                        bar.isIndeterminate = false
                        bar.progress = ((done * 100) / total).toInt()
                    }
                    progressText.text = if (total > 0) {
                        getString(R.string.update_download_sizes, formatBytes(done), formatBytes(total))
                    } else {
                        getString(R.string.update_download_progress_unknown)
                    }
                }
            }
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                dialog.dismiss()
                if (!ok) {
                    if (!handle.isCancelled) {
                        Toast.makeText(
                            requireContext(),
                            R.string.update_download_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@runOnUiThread
                }
                onApkDownloaded(target, update)
            }
        }.start()
    }

    /** 下载完成：先验签名，再（必要时）要安装权限，最后交给系统安装器。 */
    private fun onApkDownloaded(apk: File, update: UpdateManifests.AppUpdate) {
        val context = requireContext().applicationContext
        if (ApkInstaller.sameSigner(context, apk)) {
            requestInstall(apk)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_signature_mismatch_title)
            .setMessage(R.string.update_signature_mismatch_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.update_install_anyway) { _, _ -> requestInstall(apk) }
            .show()
    }

    private fun requestInstall(apk: File) {
        pendingApk = apk
        if (ApkInstaller.canRequestInstall(requireContext())) {
            launchInstaller(apk)
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_install_permission_go) { _, _ ->
                    startActivity(ApkInstaller.unknownSourcesSettings(requireContext()))
                }
                .show()
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            ApkInstaller.install(requireContext(), apk)
            pendingApk = null
            Toast.makeText(requireContext(), R.string.update_download_start, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 系统安装器被裁掉（个别 ROM）时退回浏览器下载
            Toast.makeText(requireContext(), R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 对字节数做人话格式化：用来在进度框里显示"已下 3.2 MB / 8.4 MB"。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun onSettingsChanged() {
        (activity as? MainActivity)?.onSettingsChanged()
    }
}
