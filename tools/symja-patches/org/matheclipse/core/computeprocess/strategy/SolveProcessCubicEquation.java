package org.matheclipse.core.computeprocess.strategy;

import org.matheclipse.core.computeprocess.ComputeProcessUtils;
import org.matheclipse.core.computeprocess.ProcessName;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.IntegerSym;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class SolveProcessCubicEquation implements ISolveProcessStrategy {
    public static final int INTERNAL_STEPS = 2;

    @Override // org.matheclipse.core.computeprocess.strategy.ISolveProcessStrategy
    public IAST generateProcess(IExpr inExpr, IAST traceList) {
        IAST result = F.List();
        int size = traceList.size();
        result.add(F.List(IntegerSym.valueOf(ProcessName.SOLVE_TYPE.type), IntegerSym.valueOf(3L)));
        result.add(F.List(IntegerSym.valueOf(ProcessName.GROUP_SAME_ITEM.type), ComputeProcessUtils.getGroupItemEquation(traceList)));
        result.add(F.List(IntegerSym.valueOf(ProcessName.FACTORIZATION.type), traceList.get(size - 4)));
        result.add(F.List(IntegerSym.valueOf(ProcessName.EXTRACT_ROOTS_FORMULA.type), traceList.get(size - 5)));
        result.add(traceList.last());
        return result;
    }
}
