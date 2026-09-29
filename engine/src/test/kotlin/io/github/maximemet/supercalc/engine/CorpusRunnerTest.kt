package io.github.maximemet.supercalc.engine

import java.io.File
import kotlin.test.Test

/**
 * 语料批量跑分。
 *
 * 读取 `build/corpus/in.txt`，把本引擎的输出写成与插桩探针完全相同的 TSV 格式，
 * 供外部脚本和基准结果逐条对比。
 *
 * 输入文件不存在时直接跳过，所以正常 `gradle test` 不会因此失败。
 */
class CorpusRunnerTest {

    private fun esc(s: String?): String {
        if (s == null) return ""
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> b.append("\\\\")
                '\t' -> b.append("\\t")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    @Test
    fun runCorpus() {
        val dir = File("build/corpus")
        val input = File(dir, "in.txt")
        if (!input.exists()) {
            println("跳过：没有 $input")
            return
        }
        val outDir = File("build/diagnostic")
        outDir.mkdirs()

        val auto = StringBuilder()
        val methods = StringBuilder()
        val draw = StringBuilder()

        for (raw in input.readLines(Charsets.UTF_8)) {
            val expr = raw.trim()
            if (expr.isEmpty() || expr.startsWith("#")) continue

            val session = CalculationSession()
            session.setFormula(expr, expr)
            auto.append(esc(expr)).append('\t').append(esc(session.autoResult())).append('\n')

            var order = 0
            for (m in session.availableMethods()) {
                val result = try {
                    session.evaluate(m) ?: ""
                } catch (e: Throwable) {
                    "!!ERROR: ${e.cause ?: e}"
                }
                methods.append(esc(expr)).append('\t')
                    .append(order++).append('\t')
                    .append(esc(m.label)).append('\t')
                    .append(m.typeCode).append('\t')
                    .append(esc(result)).append('\n')

                // 绘图按钮的结果不是上面那段文本：界面会丢掉它，只把公式串交给绘图页。
                if (m == Method.Draw) {
                    val raw = Method.drawFormula(expr, session.lastFormula)
                    draw.append(esc(expr)).append('\t')
                        .append(esc(raw)).append('\t')
                        .append(esc(Method.splitDrawFormula(raw).joinToString("|"))).append('\n')
                }
            }
        }

        File(outDir, "engine_auto.tsv").writeText(auto.toString(), Charsets.UTF_8)
        File(outDir, "engine_methods.tsv").writeText(methods.toString(), Charsets.UTF_8)
        File(outDir, "engine_draw.tsv").writeText(draw.toString(), Charsets.UTF_8)
        println(
            "语料跑完：${auto.lines().size - 1} 条自动预览，" +
                "${methods.lines().size - 1} 条方法结果，${draw.lines().size - 1} 条绘图公式"
        )
    }
}
