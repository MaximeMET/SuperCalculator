"""把参考实现里「解方程过程」的 trace 发点打进上游 Symja 源码。

参考 App 的 Symja 是个没公开的分支：多出 core/computeprocess（解方程过程）一整包，
Solve / Roots / QuarticSolver 里也多了一批 `getStepListener().add(...)` 发点。
反编译出来的那几个文件在别的 API 上（GroebnerBasis / PossibleZeroQ 签名都不同），
整文件覆盖编译不过，所以只能按点插入 —— 锚点必须唯一命中，改不动就报错。

用法：python tools/patch-symja-trace.py <symja_android_library 根目录>
"""

import os
import sys


def read(path):
    with open(path, encoding="utf-8", errors="surrogateescape") as fp:
        return fp.read().replace("\r\n", "\n")


def write(path, text):
    with open(path, "w", encoding="utf-8", errors="surrogateescape", newline="\n") as fp:
        fp.write(text)


def apply_edits(path, edits):
    text = read(path)
    for old, new, name in edits:
        if new in text:
            print("  跳过（已打过）:", name)
            continue
        count = text.count(old)
        if count != 1:
            raise SystemExit("%s 锚点命中 %d 次（要求 1 次）" % (name, count))
        text = text.replace(old, new)
        print("  打补丁:", name)
    write(path, text)


QUARTIC = "matheclipse-core/src/main/java/org/matheclipse/core/polynomials/QuarticSolver.java"
ROOTS = "matheclipse-core/src/main/java/org/matheclipse/core/reflection/system/Roots.java"
TRACESTACK = "matheclipse-core/src/main/java/org/matheclipse/core/eval/TraceStack.java"


def patch_tracestack(root):
    """把每个 trace 发点顺手记进 StepJournal（只在本项目显式开启记账时生效）。

    参考实现那套「按 trace 尾部下标取帧」的策略很脆：换一个方程，
    中间求值产生的 frame 数量就变，下标直接越界。记账本按算法真实执行顺序
    记录 `[步骤码, 输入, 结果]`，步骤整理不再依赖 frame 的位置。
    """
    path = os.path.join(root, TRACESTACK)
    edits = [
        (
            "import org.matheclipse.core.expression.F;\n",
            "import org.matheclipse.core.computeprocess.StepJournal;\n"
            "import org.matheclipse.core.expression.F;\n",
            "TraceStack/import",
        ),
        (
            "\tpublic void add(IExpr inputExpr, IExpr resultExpr, int recursionDepth, long iterationCounter, String hint) {\n"
            "\t\tif (iterationCounter == 0L) {\n",
            "\tpublic void add(IExpr inputExpr, IExpr resultExpr, int recursionDepth, long iterationCounter, String hint) {\n"
            "\t\tStepJournal.record(hint, inputExpr, resultExpr);\n"
            "\t\tif (iterationCounter == 0L) {\n",
            "TraceStack/记账",
        ),
    ]
    apply_edits(path, edits)


def patch_quartic(root):
    path = os.path.join(root, QUARTIC)
    edits = [
        (
            "import org.matheclipse.core.eval.exception.Validate;\n",
            "import org.matheclipse.core.eval.EvalEngine;\n"
            "import org.matheclipse.core.eval.exception.Validate;\n"
            "import org.matheclipse.core.eval.util.ResultFilterUtils;\n",
            "QuarticSolver/imports",
        ),
        (
            "public class QuarticSolver {\n",
            "public class QuarticSolver {\n"
            "\n"
            "\t/**\n"
            "\t * 参考实现的解方程过程要用的两个槽：当前正在解的方程、以及未知数。\n"
            "\t * Roots 调 quadraticSolve 之前把它们塞进来，过程渲染时再读。\n"
            "\t */\n"
            "\tpublic static final ThreadLocal<IExpr> EQUATION = new ThreadLocal<IExpr>();\n"
            "\tpublic static final ThreadLocal<IExpr> VARIABLE = new ThreadLocal<IExpr>();\n",
            "QuarticSolver/fields",
        ),
        (
            "\tpublic static IAST solve(IExpr exprPoly, IExpr x) throws ArithmeticException {\n"
            "\t\tIExpr[] coefficients",
            "\tpublic static IAST solve(IExpr exprPoly, IExpr x) throws ArithmeticException {\n"
            "\t\tEQUATION.set(exprPoly);\n"
            "\t\tVARIABLE.set(x);\n"
            "\t\tIExpr[] coefficients",
            "QuarticSolver/solve 记录方程",
        ),
        (
            "\tprivate static boolean convert2Coefficients(IExpr exprPoly, IExpr x, IExpr[] coefficients) {",
            "\tpublic static boolean convert2Coefficients(IExpr exprPoly, IExpr x, IExpr[] coefficients) {",
            "QuarticSolver/convert2Coefficients 改 public",
        ),
        (
            "\t\tif (!a.isZero()) {\n"
            "\t\t\tif (c.isZero()) {",
            "\t\tif (!a.isZero()) {\n"
            "\t\t\tEvalEngine engine = EvalEngine.get();\n"
            "\t\t\tif (engine.isTraceMode() && EQUATION.get() != null && VARIABLE.get() != null) {\n"
            "\t\t\t\tengine.getStepListener().add(EQUATION.get(), F.List(a, b, c),\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Formula!\");\n"
            "\t\t\t\tResultFilterUtils.setEngineTraceMode(true, false);\n"
            "\t\t\t\tengine.getStepListener().add(EQUATION.get(),\n"
            "\t\t\t\t\t\tF.Equal(F.eval(F.Times(a, F.Power(\n"
            "\t\t\t\t\t\t\t\tF.Plus(VARIABLE.get(), F.eval(F.Divide(b, F.Times(F.C2, a)))), F.C2))),\n"
            "\t\t\t\t\t\t\t\tF.eval(F.Subtract(F.Divide(F.Power(b, F.C2), F.Times(F.C4, a)), c))),\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Compelete Square!\");\n"
            "\t\t\t\tResultFilterUtils.setEngineTraceMode(true, true);\n"
            "\t\t\t}\n"
            "\t\t\tif (c.isZero()) {",
            "QuarticSolver/quadraticSolve 发点",
        ),
    ]
    apply_edits(path, edits)


def patch_roots(root):
    path = os.path.join(root, ROOTS)
    edits = [
        (
            "import org.matheclipse.core.eval.exception.WrappedException;\n",
            "import org.matheclipse.core.eval.exception.WrappedException;\n"
            "import org.matheclipse.core.eval.util.ResultFilterUtils;\n"
            "import org.matheclipse.core.expression.IntegerSym;\n",
            "Roots/imports",
        ),
        (
            "\t\tList<IExpr> varList = r.toList();\n"
            "\t\ttry {\n",
            "\t\tList<IExpr> varList = r.toList();\n"
            "\t\tfinal boolean originalTraceMode = engine.isTraceMode();\n"
            "\t\ttry {\n",
            "Roots/记录 trace 模式",
        ),
        (
            "\t\t\tGenPolynomial<BigRational> polyRat = jas.expr2JAS(expr, numericSolutions);\n"
            "\t\t\tif (polyRat.degree(0) <= 2) {\n"
            "\t\t\t\treturn rootsOfExprPolynomial(expr, variables);\n"
            "\t\t\t}\n",
            "\t\t\tGenPolynomial<BigRational> polyRat = jas.expr2JAS(expr, numericSolutions);\n"
            "\t\t\tfinal long degree = polyRat.degree(0);\n"
            "\t\t\tif (originalTraceMode) {\n"
            "\t\t\t\tengine.getStepListener().add(expr, IntegerSym.valueOf(degree),\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Polynomial's degree!\");\n"
            "\t\t\t}\n"
            "\t\t\tif (degree <= 2) {\n"
            "\t\t\t\tif (originalTraceMode && degree == 2) {\n"
            "\t\t\t\t\t// 二次方程先给一份「因式分解」的中间结果\n"
            "\t\t\t\t\tIAST traceFactors = Factor.factorComplex(polyRat, jas, varList, F.List, true);\n"
            "\t\t\t\t\tResultFilterUtils.setEngineTraceMode(originalTraceMode, false);\n"
            "\t\t\t\t\tIAST evalFactors = F.List();\n"
            "\t\t\t\t\tfor (int i = 1; i < traceFactors.size(); i++) {\n"
            "\t\t\t\t\t\tevalFactors.add(F.evalExpand(traceFactors.get(i)));\n"
            "\t\t\t\t\t}\n"
            "\t\t\t\t\tResultFilterUtils.setEngineTraceMode(originalTraceMode, true);\n"
            "\t\t\t\t\tengine.getStepListener().add(expr, evalFactors,\n"
            "\t\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Polynomial's factor!\");\n"
            "\t\t\t\t}\n"
            "\t\t\t\tQuarticSolver.EQUATION.set(expr);\n"
            "\t\t\t\tQuarticSolver.VARIABLE.set(variables.arg1());\n"
            "\t\t\t\treturn rootsOfExprPolynomial(expr, variables);\n"
            "\t\t\t}\n",
            "Roots/degree + 二次因式发点",
        ),
        (
            "\t\t\tIAST factors = Factor.factorComplex(polyRat, jas, varList, F.List, true);\n"
            "\t\t\tfor (int i = 1; i < factors.size(); i++) {\n"
            "\t\t\t\ttemp = F.evalExpand(factors.get(i));\n",
            "\t\t\tResultFilterUtils.setEngineTraceMode(originalTraceMode, false);\n"
            "\t\t\tIExpr[] traceCoefficients = new IExpr[] { F.C0, F.C0, F.C0, F.C0, F.C0 };\n"
            "\t\t\tif (originalTraceMode && (degree == 3 || degree == 4)\n"
            "\t\t\t\t\t&& QuarticSolver.convert2Coefficients(expr, variables.arg1(), traceCoefficients)) {\n"
            "\t\t\t\tIAST coefficientList = F.List();\n"
            "\t\t\t\tfor (int i = 0; i < 5; i++) {\n"
            "\t\t\t\t\tcoefficientList.add(traceCoefficients[i]);\n"
            "\t\t\t\t}\n"
            "\t\t\t\tengine.getStepListener().add(expr, coefficientList,\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Polynomial's cofficients!\");\n"
            "\t\t\t}\n"
            "\t\t\tIAST factors = Factor.factorComplex(polyRat, jas, varList, F.List, true);\n"
            "\t\t\tIAST expandFactors = null;\n"
            "\t\t\tfor (int i = 1; i < factors.size(); i++) {\n"
            "\t\t\t\ttemp = F.evalExpand(factors.get(i));\n"
            "\t\t\t\tif (originalTraceMode) {\n"
            "\t\t\t\t\tif (expandFactors == null) {\n"
            "\t\t\t\t\t\texpandFactors = F.List();\n"
            "\t\t\t\t\t}\n"
            "\t\t\t\t\texpandFactors.add(temp);\n"
            "\t\t\t\t}\n",
            "Roots/系数与展开因子发点",
        ),
        (
            "\t\t\tresult = QuarticSolver.createSet(result);\n"
            "\t\t\treturn result;\n"
            "\t\t} catch (JASConversionException e) {\n"
            "\t\t\tresult = rootsOfExprPolynomial(expr, variables);\n"
            "\t\t}\n",
            "\t\t\tif (originalTraceMode) {\n"
            "\t\t\t\tengine.getStepListener().add(expr, expandFactors,\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Expand roots' factors\");\n"
            "\t\t\t}\n"
            "\t\t\tResultFilterUtils.setEngineTraceMode(originalTraceMode, true);\n"
            "\t\t\tresult = QuarticSolver.createSet(result);\n"
            "\t\t\treturn result;\n"
            "\t\t} catch (JASConversionException e) {\n"
            "\t\t\tif (originalTraceMode) {\n"
            "\t\t\t\tengine.getStepListener().add(expr, IntegerSym.valueOf(1L),\n"
            "\t\t\t\t\t\tengine.getRecursionCounter() + 1, 0L, \"Polynomial's degree!\");\n"
            "\t\t\t}\n"
            "\t\t\tresult = rootsOfExprPolynomial(expr, variables);\n"
            "\t\t}\n",
            "Roots/展开因子收尾 + 兜底度数",
        ),
    ]
    apply_edits(path, edits)


def main():
    root = sys.argv[1]
    print("补 QuarticSolver ...")
    patch_quartic(root)
    print("补 Roots ...")
    patch_roots(root)
    print("补 TraceStack ...")
    patch_tracestack(root)


if __name__ == "__main__":
    main()
