package org.matheclipse.core.computeprocess;

import org.matheclipse.core.eval.EvalAttributes;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class ComputeProcessUtils {
    public static boolean hasSolve(IAST traceList) {
        return !traceList.get(2).isFree(F.Solve);
    }

    public static IExpr getGroupItemEquation(IAST traceList) {
        IAST groupSameItemResult;
        if (hasSolve(traceList)) {
            groupSameItemResult = (IAST) traceList.get(2).getAt(1).getAt(1);
        } else {
            IAST subTrace = (IAST) traceList.getAST(1).last();
            if (!subTrace.isList()) {
                groupSameItemResult = F.Equal(subTrace, F.C0);
            } else {
                groupSameItemResult = F.Equal(subTrace.last(), F.C0);
            }
        }
        if (groupSameItemResult.get(1).isAST()) {
            IAST tmp = groupSameItemResult.getAST(1);
            EvalAttributes.sort(tmp, true);
            groupSameItemResult.set(1, tmp);
        }
        return groupSameItemResult;
    }
}
