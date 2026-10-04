package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 解题步骤的回归测试。
 *
 * 步骤的「形状」比「逐字文案」重要：这里钉住每种方程至少产出哪些步骤，
 * 文案变了不会误报，但少了一步、或者引擎反过来拿不到步骤，就会红。
 */
class SolveStepsTest {

    private val engine = SymjaEngine()

    private fun stepsOf(formula: String, method: Method = Method.Solve) =
        SolveSteps.build(engine, formula, "", method)

    /** 结果步骤在二次以上叫 equalityResult，一次方程叫 result，两者都算。 */
    private fun List<String>.hasResult() = "result" in this || "equalityResult" in this

    @Test
    fun quadraticHasAllFourStages() {
        val steps = stepsOf("x^2-5*x+6==0")
        val keys = steps.map { it.key }
        assertTrue("groupSameItem" in keys, "缺少移项步骤：$keys")
        assertTrue("factors" in keys, "缺少因式分解：$keys")
        assertTrue("rootsFormula" in keys, "缺少求根公式：$keys")
        assertTrue("compeleteSquare" in keys, "缺少配方法：$keys")
        assertTrue(keys.hasResult(), "缺少结果：$keys")
    }

    @Test
    fun linearHasGroupAndResult() {
        val steps = stepsOf("2*x+3==7")
        val keys = steps.map { it.key }
        assertTrue("groupSameItem" in keys, "缺少移项步骤：$keys")
        assertTrue(keys.hasResult(), "缺少结果：$keys")
    }

    @Test
    fun irrationalQuadraticSkipsTrivialFactorization() {
        val steps = stepsOf("x^2==2")
        val keys = steps.map { it.key }
        // x^2-2 在有理数上分不出来，不该硬塞一个「因式分解」步骤
        assertTrue("factors" !in keys, "不该有因式分解：$keys")
        assertTrue("rootsFormula" in keys, "缺少求根公式：$keys")
        assertTrue("compeleteSquare" in keys, "缺少配方法：$keys")
    }

    @Test
    fun cubicFactorsIntoTriple() {
        val steps = stepsOf("x^3-6*x^2+11*x-6==0")
        val keys = steps.map { it.key }
        assertTrue("factors" in keys, "缺少因式分解：$keys")
        assertTrue("rootsFormula" in keys, "缺少求根公式：$keys")
        assertTrue(keys.hasResult(), "缺少结果：$keys")
    }

    @Test
    fun systemHasElimination() {
        val steps = SolveSteps.build(engine, "x-y==1", "x+y==3", Method.Solve2)
        val keys = steps.map { it.key }
        assertTrue("gaussianElimination" in keys, "缺少消元：$keys")
        assertTrue(keys.hasResult(), "缺少结果：$keys")
    }

    @Test
    fun sessionExposesProcessJsonForSolve() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("x^2-5*x+6==0", "x^{2}-5x+6=0")
        val json = session.processSteps(Method.Solve)
        assertTrue(json != null && json.contains("\"steps\""), "会话层拿不到步骤：$json")
    }

    @Test
    fun otherMethodsHaveNoProcess() {
        val session = CalculationSession(SymjaEngine())
        session.setFormula("x^2", "x^{2}")
        assertTrue(
            session.processSteps(Method.Integrate) == null,
            "积分不该有解题步骤（当前只做解方程）",
        )
    }

    @Test
    fun dumpForInspection() {
        val cases = listOf(
            Method.Solve to ("x^2-5*x+6==0" to ""),
            Method.Solve to ("2*x+3==7" to ""),
            Method.Solve to ("x^2==2" to ""),
            Method.Solve to ("x^3-6*x^2+11*x-6==0" to ""),
            Method.Solve to ("x^2-1==0" to ""),
            Method.Solve2 to ("x-y==1" to "x+y==3"),
        )
        val sb = StringBuilder()
        for ((method, pair) in cases) {
            val (formula, last) = pair
            sb.append("==== ").append(method.key).append(' ').append(last).append(" | ").append(formula)
                .append('\n')
            for (step in SolveSteps.build(engine, formula, last, method)) {
                sb.append("  ").append(step.label).append(" [").append(step.key).append("]\n")
                step.lines.forEach { sb.append("      ").append(it).append('\n') }
            }
            sb.append("  JSON = ").append(SolveSteps.buildJson(engine, formula, last, method)).append('\n')
        }
        val out = java.io.File("build/diagnostic/solve-steps.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        print(sb)
    }
}
