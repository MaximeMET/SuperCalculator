package io.github.maximemet.supercalc.engine

/**
 * 影响计算结果的用户设置。
 *
 * 原版把这些值放在 Android SharedPreferences 里，引擎侧只读取快照，
 * 这样纯 JVM 模块不依赖任何 Android API，方便单元测试。
 */
object EngineSettings {

    /** 保留小数位，对应设置页「保留小数位」，默认 10。 */
    var precision: Int = 10

    /** 默认未知数。 */
    var unknown: String = "x"

    /** 角度单位："degree" 或 "radian"。 */
    var degreeUnit: String = "degree"

    /** 多行输入的换行标记。 */
    const val NEWLINE = "\\n"

    /** 由工具栏「换行」键插入的分隔模式。 */
    val NEWLINE_STR: String get() = NEWLINE

    fun reset() {
        precision = 10
        unknown = "x"
        degreeUnit = "degree"
    }
}
