package io.github.maximemet.supercalc.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.maximemet.supercalc.engine.EngineSettings

/**
 * 设置页那四项。
 *
 * 键名和取值都照抄参考实现（`PreferenceConsts` + `SettingParams`）：
 * 字体大小存的是显示文案「大 / 中 / 小」，小数位存的是字符串，两个开关是布尔。
 * 参考实现把这些值缓存在 `SettingParams` 的静态字段里，我们只把影响引擎的那一个
 * （精度）同步进 [EngineSettings]，其余按需读。
 */
object AppSettings {

    const val FONT_BIG = "大"
    const val FONT_MID = "中"
    const val FONT_SMALL = "小"

    /** 字体大小可选项，顺序和参考实现的 `setting_font_size` 数组一致。 */
    val FONT_VALUES = listOf(FONT_BIG, FONT_MID, FONT_SMALL)

    /** 保留小数位可选项，顺序和参考实现的 `setting_fraction_digits` 数组一致。 */
    val FRACTION_VALUES = (4..11).map { it.toString() }

    const val KEY_FONT_SIZE = "pref_Key_font_size"
    const val KEY_FRACTION = "pref_Key_fraction_digits"
    const val KEY_EXAMPLE_VISIBILITY = "pref_key_example_visibility"
    const val KEY_PROCESS_VISIBILITY = "pref_key_process_visibility"

    private const val PREF_FILE = "supercalc"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        // 引擎侧只认一个快照，开机同步一次
        EngineSettings.precision = precision
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("AppSettings.init() 还没调用")

    /**
     * 字体大小对应的 WebView 缩放。
     *
     * 参考实现（CalculatorFragment.setWebViewFontSize / CalculatorResultActivity.setWebViewFontSize）
     * 用的是 `WebSettings.setTextSize(LARGER / NORMAL / SMALLER)`。
     * WebView 内部把这三个枚举映射成 textZoom 125 / 100 / 75
     * （Chrome 的 AwSettings 里就是 {50, 75, 100, 125, 150}），所以这里直接写死这三个数。
     */
    val fontZoom: Int
        get() = when (fontSize) {
            FONT_BIG -> 125
            FONT_SMALL -> 75
            else -> 100
        }

    var fontSize: String
        get() = requirePrefs().getString(KEY_FONT_SIZE, FONT_MID) ?: FONT_MID
        set(value) = requirePrefs().edit().putString(KEY_FONT_SIZE, value).apply()

    var precision: Int
        get() = (requirePrefs().getString(KEY_FRACTION, "10") ?: "10").toIntOrNull() ?: 10
        set(value) {
            requirePrefs().edit().putString(KEY_FRACTION, value.toString()).apply()
            EngineSettings.precision = value
        }

    var exampleVisible: Boolean
        get() = requirePrefs().getBoolean(KEY_EXAMPLE_VISIBILITY, true)
        set(value) = requirePrefs().edit().putBoolean(KEY_EXAMPLE_VISIBILITY, value).apply()

    var processVisible: Boolean
        get() = requirePrefs().getBoolean(KEY_PROCESS_VISIBILITY, true)
        set(value) = requirePrefs().edit().putBoolean(KEY_PROCESS_VISIBILITY, value).apply()
}
