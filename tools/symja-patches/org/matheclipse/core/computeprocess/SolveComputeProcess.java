package org.matheclipse.core.computeprocess;

import java.util.HashMap;
import java.util.Map;
import org.matheclipse.core.computeprocess.strategy.ISolveProcessStrategy;
import org.matheclipse.core.computeprocess.strategy.SolveProcessBiqudraticEquation;
import org.matheclipse.core.computeprocess.strategy.SolveProcessCubicEquation;
import org.matheclipse.core.computeprocess.strategy.SolveProcessGeneralPolynomial;
import org.matheclipse.core.computeprocess.strategy.SolveProcessLinearEquation;
import org.matheclipse.core.computeprocess.strategy.SolveProcessQuadraticEquation;
import org.matheclipse.core.computeprocess.strategy.SolveProcessSystemEquation;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class SolveComputeProcess implements IComputeProcess {
    private static final Map<Integer, SolveType> intToSolveTypeMap = new HashMap();

    public enum SolveType {
        UNKNOWN(-1),
        LINER_EQUATION(1),
        QUDRATIC_EQUATION(2),
        CUBIC_EQUATION(3),
        BIQUDRATIC_EQUATION(4),
        GENERIC_EQUATION(5),
        SYSTEM_EQUATION(6);

        public int type;

        SolveType(int type) {
            this.type = type;
        }
    }

    static {
        for (SolveType type : SolveType.values()) {
            intToSolveTypeMap.put(Integer.valueOf(type.type), type);
        }
    }

    public static SolveType getSolveTypeFromInt(int i) {
        SolveType type = intToSolveTypeMap.get(Integer.valueOf(i));
        if (type == null) {
            return SolveType.UNKNOWN;
        }
        return type;
    }

    private class ProcessContext {
        ISolveProcessStrategy processStrategy;

        public ProcessContext(ISolveProcessStrategy processStrategy) {
            this.processStrategy = processStrategy;
        }

        public IAST process(IExpr inExpr, IAST traceList) {
            return this.processStrategy.generateProcess(inExpr, traceList);
        }
    }

    private static SolveType extractEquationType(IExpr inExpr, IAST traceList) {
        IExpr degree;
        int size = traceList.size();
        if (traceList.get(size - 2).getAt(1).equals(F.C1)) {
            if (ComputeProcessUtils.hasSolve(traceList)) {
                degree = traceList.get(4).getAt(1);
            } else {
                degree = traceList.get(2).getAt(1);
            }
            if (degree.equals(F.C1)) {
                return SolveType.LINER_EQUATION;
            }
            if (degree.equals(F.C2)) {
                return SolveType.QUDRATIC_EQUATION;
            }
            if (degree.equals(F.C3)) {
                return SolveType.CUBIC_EQUATION;
            }
            if (degree.equals(F.C4)) {
                return SolveType.BIQUDRATIC_EQUATION;
            }
            return SolveType.GENERIC_EQUATION;
        }
        return SolveType.SYSTEM_EQUATION;
    }

    @Override // org.matheclipse.core.computeprocess.IComputeProcess
    public IAST extractProcess(IExpr inExpr, IAST traceList) {
        ProcessContext context;
        switch (extractEquationType(inExpr, traceList)) {
            case LINER_EQUATION:
                context = new ProcessContext(new SolveProcessLinearEquation());
                break;
            case QUDRATIC_EQUATION:
                context = new ProcessContext(new SolveProcessQuadraticEquation());
                break;
            case CUBIC_EQUATION:
                context = new ProcessContext(new SolveProcessCubicEquation());
                break;
            case BIQUDRATIC_EQUATION:
                context = new ProcessContext(new SolveProcessBiqudraticEquation());
                break;
            case GENERIC_EQUATION:
                context = new ProcessContext(new SolveProcessGeneralPolynomial());
                break;
            case SYSTEM_EQUATION:
                context = new ProcessContext(new SolveProcessSystemEquation());
                break;
            default:
                return traceList;
        }
        return context.process(inExpr, traceList);
    }
}
