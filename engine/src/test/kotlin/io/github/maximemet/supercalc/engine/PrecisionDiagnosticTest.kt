package io.github.maximemet.supercalc.engine

import java.io.File
import kotlin.test.Test

/**
 * 诊断：为什么 `N(1/3)` 只输出 6 位小数。
 *
 * 基准 App 实测输出是 `= \frac{1}{3}$$= 0.3333333333`（10 位，跟随「保留小数位」设置），
 * 所以必须找到能让数值通道产出足够位数的写法。
 */
class PrecisionDiagnosticTest {

    @Test
    fun diagnose() {
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')
        line("保留小数位 = ${EngineSettings.precision}")

        val engine = SymjaEngine()
        for (formula in listOf("1/3", "sqrt(2)", "pi", "(1+sqrt(5))/2")) {
            line("")
            line("===== $formula =====")
            line("A) TexForm(N($formula))                  : ${engine.evaluateRaw("TexForm(N($formula))")}")
            line("B) TexForm(N($formula,10))               : ${engine.evaluateRaw("TexForm(N($formula,10))")}")
            line("C) TexForm(N($formula,20))               : ${engine.evaluateRaw("TexForm(N($formula,20))")}")

            // D) 打开数值模式 + 精度后再走 TexForm
            engine.evalEngine.setNumericMode(true)
            engine.evalEngine.setNumericPrecision(20)
            line("D) numericMode+prec20 TexForm(N(...))    : ${engine.evaluateRaw("TexForm(N($formula))")}")
            engine.evalEngine.setNumericMode(false)

            line("E) 引擎默认 numericMode 是否残留          : ${engine.evaluateRaw("TexForm(N($formula))")}")
        }

        val out = File("build/diagnostic/precision.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
    }
}
