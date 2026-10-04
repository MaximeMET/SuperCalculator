package org.matheclipse.core.computeprocess.strategy;

import org.matheclipse.core.computeprocess.ComputeProcessUtils;
import org.matheclipse.core.computeprocess.ProcessName;
import org.matheclipse.core.computeprocess.SolveComputeProcess;
import org.matheclipse.core.computeprocess.SystemEquationProcessUtils;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.IntegerSym;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;
import org.matheclipse.core.interfaces.INum;

public class SolveProcessSystemEquation implements ISolveProcessStrategy {
    @Override // org.matheclipse.core.computeprocess.strategy.ISolveProcessStrategy
    public IAST generateProcess(IExpr inExpr, IAST traceList) {
        int startIndex;
        IAST result = F.List();
        int size = traceList.size();
        IAST equationsWithExtraInfo = traceList.getAST(size - 3).getAST(1);
        result.add(F.List(IntegerSym.valueOf(ProcessName.GROUP_SAME_ITEM.type), equationsWithExtraInfo.getAt(4)));
        IAST simplifyProcess = getSimplifyResult(equationsWithExtraInfo.getAST(4), equationsWithExtraInfo.getAST(3), ((INum) equationsWithExtraInfo.getAt(2)).intValue());
        if (simplifyProcess != null) {
            result.add(F.List(IntegerSym.valueOf(ProcessName.SOLVE_SYSTEM_SIMPLIFY.type), simplifyProcess));
        }
        IAST gussianEquations = F.List();
        for (IExpr expr : equationsWithExtraInfo.getAST(1)) {
            if (expr.equals(F.Power(F.x, 1L)) || expr.equals(F.Power(F.y, 1L)) || expr.equals(F.Power(F.z, 1L))) {
                gussianEquations.add(expr.getAt(1));
            } else {
                gussianEquations.add(expr);
            }
        }
        result.add(F.List(IntegerSym.valueOf(ProcessName.GAUSSIAN_ELIMINATION.type), gussianEquations));
        if (ComputeProcessUtils.hasSolve(traceList)) {
            startIndex = 4;
        } else {
            startIndex = 2;
        }
        IAST recurisiveTrace = F.List();
        recurisiveTrace.add(F.List(equationsWithExtraInfo.getAt(1).getAt(1)));
        while (startIndex < size - 3) {
            recurisiveTrace.add(traceList.get(startIndex));
            startIndex++;
        }
        result.add(generateProcessRecurisive(recurisiveTrace, 1));
        result.add(traceList.last());
        return result;
    }

    private IAST generateProcessRecurisive(IAST recurisiveTrace, int level) {
        IAST result = F.List();
        boolean currentLevelHandled = false;
        IExpr currentLevelSolvedEquation = null;
        IAST currentLevelResults = F.List();
        int i = 1;
        while (i < recurisiveTrace.size()) {
            IExpr trace = recurisiveTrace.get(i);
            if (trace.head().equals(F.HoldForm)) {
                trace = trace.getAt(1);
            }
            if (trace.isInteger()) {
                currentLevelHandled = true;
                result.add(F.List(IntegerSym.valueOf(ProcessName.GROUP_SAME_ITEM.type), currentLevelSolvedEquation));
                switch (SolveComputeProcess.getSolveTypeFromInt(Integer.valueOf(trace.toString()).intValue())) {
                    case LINER_EQUATION:
                        currentLevelResults = (IAST) recurisiveTrace.get(i + 1);
                        i++;
                        break;
                    case QUDRATIC_EQUATION:
                        result.add(F.List(IntegerSym.valueOf(ProcessName.FACTORIZATION.type), recurisiveTrace.get(i + 1)));
                        result.add(F.List(IntegerSym.valueOf(ProcessName.EXTRACT_ROOTS_FORMULA.type), recurisiveTrace.get(i + 2)));
                        result.add(F.List(IntegerSym.valueOf(ProcessName.COMPELETE_SQUARE.type), recurisiveTrace.get(i + 3)));
                        currentLevelResults = (IAST) recurisiveTrace.get(i + 4);
                        i += 4;
                        break;
                    case BIQUDRATIC_EQUATION:
                        result.add(F.List(IntegerSym.valueOf(ProcessName.EXTRACT_ROOTS_FORMULA.type), recurisiveTrace.get(i + 1)));
                        result.add(F.List(IntegerSym.valueOf(ProcessName.FACTORIZATION.type), recurisiveTrace.get(i + 2)));
                        currentLevelResults.add(recurisiveTrace.get(i + 3));
                        i += 3;
                        break;
                    case CUBIC_EQUATION:
                        result.add(F.List(IntegerSym.valueOf(ProcessName.EXTRACT_ROOTS_FORMULA.type), recurisiveTrace.get(i + 1)));
                        result.add(F.List(IntegerSym.valueOf(ProcessName.FACTORIZATION.type), recurisiveTrace.get(i + 2)));
                        currentLevelResults.add(recurisiveTrace.get(i + 3));
                        i += 3;
                        break;
                    case GENERIC_EQUATION:
                        result.add(F.List(IntegerSym.valueOf(ProcessName.FACTORIZATION.type), recurisiveTrace.get(i + 1)));
                        currentLevelResults.add(recurisiveTrace.get(i + 2));
                        i += 2;
                        break;
                }
            } else if (trace.isList() && !currentLevelHandled) {
                if (((IAST) trace).size() <= 2) {
                    currentLevelSolvedEquation = trace.getAt(1);
                } else {
                    result.add(F.List(IntegerSym.valueOf(ProcessName.REPLACE_VARIABLE.type), F.Rule(trace.getAt(1), trace.getAt(2)), trace.getAt(3)));
                    if (currentLevelSolvedEquation == null) {
                        currentLevelSolvedEquation = trace.getAt(2);
                    }
                }
            } else {
                result.add(generateProcessRecurisive((IAST) trace, level + 1));
            }
            i++;
        }
        result.add(currentLevelResults);
        return result;
    }

    private IAST getSimplifyResult(IAST groupSameItems, IAST vars, int systemEquationType) {
        SystemEquationProcessUtils.SystemEquationType type = SystemEquationProcessUtils.getProcessNameFromInt(systemEquationType);
        IAST result = null;
        switch (type) {
            case TWO_ONE:
                result = SystemEquationProcessUtils.getTwoOneSimplifyResult(groupSameItems, vars);
                break;
            case THREE_ONE:
                result = SystemEquationProcessUtils.getThreeOneSimplifyResult(groupSameItems, vars);
                break;
        }
        if (result != null) {
            result.add(F.num(type.type));
        }
        return result;
    }
}
