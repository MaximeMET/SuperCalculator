package org.matheclipse.core.computeprocess.strategy;

import org.matheclipse.core.computeprocess.ComputeProcessUtils;
import org.matheclipse.core.computeprocess.ProcessName;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.IntegerSym;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class SolveProcessLinearEquation implements ISolveProcessStrategy {
    public static final int INTERNAL_STEPS = 0;

    @Override // org.matheclipse.core.computeprocess.strategy.ISolveProcessStrategy
    public IAST generateProcess(IExpr inExpr, IAST traceList) {
        IAST result = F.List();
        result.add(F.List(IntegerSym.valueOf(ProcessName.SOLVE_TYPE.type), IntegerSym.valueOf(1L)));
        if (!traceList.get(1).isFree(F.Rule)) {
            traceList.add(1, inExpr);
            traceList.add(1, inExpr);
        }
        result.add(F.List(IntegerSym.valueOf(ProcessName.GROUP_SAME_ITEM.type), ComputeProcessUtils.getGroupItemEquation(traceList)));
        result.add(traceList.last());
        return result;
    }
}
