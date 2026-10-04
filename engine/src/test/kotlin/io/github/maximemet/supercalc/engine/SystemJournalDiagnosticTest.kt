package io.github.maximemet.supercalc.engine

import org.matheclipse.core.computeprocess.StepJournal
import org.matheclipse.core.expression.F
import org.matheclipse.core.interfaces.IAST
import kotlin.test.Test

/**
 * 方程组 trace 记账本摸底：看看 `Solve({...},{x,y})` 到底发出了哪些步骤码，
 * 决定「变量替换 / 求解子方程」这两步能不能直接翻译。
 */
class SystemJournalDiagnosticTest {

    private val engine = SymjaEngine()

    private val cases = listOf(
        "x+y==3, x-y==1" to "{x,y}",
        "x+y==3, 2*x-y==0" to "{x,y}",
        "2*x+3*y==8, 4*x-y==2" to "{x,y}",
        "x+y+z==6, x-y==0, z-y==1" to "{x,y,z}",
        "x*y==6, x+y==5" to "{x,y}",
    )

    @Test
    fun dumpJournal() {
        val sb = StringBuilder()
        for ((equations, unknown) in cases) {
            sb.append("==== ").append(equations).append("  unknown=").append(unknown).append('\n')
            val code = "Solve({$equations}, $unknown)"
            val parsed = engine.parseOrNull(code)
            if (parsed == null) {
                sb.append("  解析失败\n")
                continue
            }
            StepJournal.begin()
            val journal = try {
                engine.evalEngine.evalTrace(parsed, null, F.List())
                StepJournal.end()
            } catch (t: Throwable) {
                StepJournal.cancel()
                sb.append("  求值异常: ").append(t).append('\n')
                continue
            }
            sb.append("  求值结果: ").append(engine.evaluateOrNull(engine.parseOrNull(code))).append('\n')
            for (i in 1 until journal.size) {
                val item = journal.get(i) as? IAST ?: continue
                if (item.size != 4) continue
                sb.append("  [").append(item.get(1)).append("] in = ").append(item.get(2))
                    .append("  out = ").append(item.get(3)).append('\n')
            }
        }
        val out = java.io.File("build/diagnostic/system-journal.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
