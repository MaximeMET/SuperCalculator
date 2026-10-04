package org.matheclipse.core.computeprocess;

import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.IntegerSym;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class SolveInequalityComputeProcess implements IComputeProcess {
    @Override // org.matheclipse.core.computeprocess.IComputeProcess
    public IAST extractProcess(IExpr inExpr, IAST traceList) {
        if (!traceList.last().isFree(F.SolveInEquality)) {
            return null;
        }
        IComputeProcess solveProcess = new SolveComputeProcess();
        IExpr result = traceList.get(traceList.size() - 1);
        boolean hasSolveInEquality = !traceList.isFree(F.SolveInEquality);
        IAST traceList2 = traceList.setAtClone(1, F.List(F.Subtract(inExpr.getAt(1).getAt(1), inExpr.getAt(1).getAt(2))));
        if (hasSolveInEquality) {
            traceList2 = traceList2.removeAtClone(2);
        }
        IAST temp = traceList2.removeAtClone(traceList2.size() - 1);
        IAST solveTraceResult = solveProcess.extractProcess(inExpr, temp);
        solveTraceResult.add(1, F.List(IntegerSym.valueOf(ProcessName.SOLVE_INEQUALITY_UNIVARIBALE.type), inExpr.getAt(1)));
        solveTraceResult.add(result);
        return solveTraceResult;
    }
}
