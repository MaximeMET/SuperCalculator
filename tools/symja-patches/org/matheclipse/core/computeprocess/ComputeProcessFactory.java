package org.matheclipse.core.computeprocess;

import java.util.HashMap;
import java.util.Map;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class ComputeProcessFactory {
    private static final Map<Integer, ProcessName> intToTypeMap = new HashMap();

    static {
        for (ProcessName type : ProcessName.values()) {
            intToTypeMap.put(Integer.valueOf(type.type), type);
        }
    }

    public static ProcessName getProcessNameFromInt(int i) {
        ProcessName type = intToTypeMap.get(Integer.valueOf(i));
        if (type == null) {
            return ProcessName.UNKNOWN;
        }
        return type;
    }

    private static IComputeProcess getComputeProcess(IExpr inputExpr) {
        if (!inputExpr.isFree(F.Solve)) {
            return new SolveComputeProcess();
        }
        if (!inputExpr.isFree(F.SolveInEquality)) {
            return new SolveInequalityComputeProcess();
        }
        return null;
    }

    public static IAST generateProcessDescription(IExpr inputExpression, IAST traceList) {
        IComputeProcess processDescriptor = getComputeProcess(inputExpression);
        if (processDescriptor != null) {
            return processDescriptor.extractProcess(inputExpression, traceList);
        }
        return traceList;
    }
}
