package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 不等式（组）求解与穿线法步骤的回归测试。 */
class InequalityStepsTest {

    private val engine = SymjaEngine()

    @Test
    fun singleInequalityHasFourStages() {
        val steps = InequalitySteps.build(engine, "x^2-1>0", "", Method.SolveIneq)
        val keys = steps?.map { it.key } ?: emptyList()
        assertTrue("solveUnivaribaleInequality" in keys, "缺少待求解不等式：$keys")
        assertTrue("solveEquationInequality" in keys, "缺少对应方程：$keys")
        assertTrue("checkResult" in keys, "缺少穿线法：$keys")
        assertTrue("result" in keys, "缺少解集：$keys")
    }

    @Test
    fun singleInequalityStillSolves() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("x^2-1>0", "x^{2}-1>0")
        val result = session.evaluate(Method.SolveIneq)
        assertTrue(!result.isNullOrBlank(), "不等式解不出来：$result")
        assertTrue(
            result!!.contains("<") && result.contains(">"),
            "两根不等式的解集形状不对：$result",
        )
    }

    @Test
    fun inequalitySystemTakesIntersection() {
        val session = CalculationSession(SymjaEngine())
        // 上一行 x>1、当前行 x<3：编辑器里的换行是字面量 \n
        session.setFormula("x>1\\nx<3", "")
        val result = session.evaluate(Method.SolveIneq2)
        assertTrue(!result.isNullOrBlank(), "不等式组解不出来：$result")
        assertTrue(!result!!.contains("solvesysteminequality"), "不等式组还是原样吐回来了：$result")
    }

    @Test
    fun inequalitySystemStepsShowSubResults() {
        val steps = InequalitySteps.build(engine, "x<3", "x>1", Method.SolveIneq2)
        val keys = steps?.map { it.key } ?: emptyList()
        assertEquals(2, keys.count { it == "subEquation" }, "应该有两个子不等式步骤：$keys")
        assertTrue("result" in keys, "缺少解集：$keys")
    }

    @Test
    fun unknownCanBeOtherThanX() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("y^2-1>0", "y^{2}-1>0")
        val result = session.evaluate(Method.SolveIneq)
        assertTrue(!result.isNullOrBlank() && result.contains("y"), "y 的不等式没解出来：$result")
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            Triple(Method.SolveIneq to "x^2-1>0", "", ""),
            Triple(Method.SolveIneq to "(x-1)/(x+2)>0", "", ""),
            Triple(Method.SolveIneq2 to "x<3", "x>1", ""),
        )
        val sb = StringBuilder()
        for ((pair, last, extra) in cases) {
            val (method, formula) = pair
            sb.append("==== ").append(last).append(" | ").append(formula).append(" (").append(method.key)
                .append(")\n")
            val session = CalculationSession(SymjaEngine())
            session.setFormula(formula, extra)
            sb.append("  结果 = ").append(session.evaluate(method)).append('\n')
            val steps = InequalitySteps.build(engine, formula, last, method)
            if (steps == null) {
                sb.append("  <没有步骤>\n")
                continue
            }
            for (step in steps) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
            sb.append("  JSON = ").append(InequalitySteps.buildJson(engine, formula, last, method)).append('\n')
        }
        val out = java.io.File("build/diagnostic/inequality-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
