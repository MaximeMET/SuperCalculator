package org.matheclipse.core.computeprocess;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class SystemEquationProcessUtils {
    private static final Map<Integer, SystemEquationType> intToTypeMap = new HashMap();

    public enum SystemEquationType {
        NONE(-1),
        OTHER(0),
        TWO_ONE(1),
        THREE_ONE(2);

        public int type;

        SystemEquationType(int type) {
            this.type = type;
        }
    }

    static {
        for (SystemEquationType type : SystemEquationType.values()) {
            intToTypeMap.put(Integer.valueOf(type.type), type);
        }
    }

    public static SystemEquationType getProcessNameFromInt(int i) {
        SystemEquationType type = intToTypeMap.get(Integer.valueOf(i));
        if (type == null) {
            return SystemEquationType.NONE;
        }
        return type;
    }

    public static SystemEquationType checkEquationType(IAST ast) {
        if (ast.get(1).isList()) {
            int varSize = ast.getAST(2).size() - 1;
            IAST equations = ast.getAST(1);
            boolean freeOfPower = true;
            if (varSize == 2) {
                int i = 1;
                while (true) {
                    if (i >= equations.size()) {
                        break;
                    }
                    if (equations.get(i).isFree(F.Power)) {
                        i++;
                    } else {
                        freeOfPower = false;
                        break;
                    }
                }
                if (freeOfPower) {
                    return SystemEquationType.TWO_ONE;
                }
            } else if (varSize == 3) {
                int i2 = 1;
                while (true) {
                    if (i2 >= equations.size()) {
                        break;
                    }
                    if (equations.get(i2).isFree(F.Power)) {
                        i2++;
                    } else {
                        freeOfPower = false;
                        break;
                    }
                }
                if (freeOfPower) {
                    return SystemEquationType.THREE_ONE;
                }
            }
            return SystemEquationType.OTHER;
        }
        if (ast.get(1).isAnd()) {
            return SystemEquationType.OTHER;
        }
        return SystemEquationType.NONE;
    }

    public static IAST getTwoOneSimplifyResult(IAST groupSameItems, IAST vars) {
        IExpr freeOfVar1 = null;
        IExpr freeOfVar2 = null;
        IExpr noFree = null;
        IAST result = F.List();
        for (IExpr expr : groupSameItems) {
            if (expr.isFree(vars.arg1())) {
                freeOfVar2 = expr;
            } else if (expr.isFree(vars.arg2())) {
                freeOfVar1 = expr;
            } else {
                noFree = expr;
            }
        }
        if (freeOfVar1 == null || freeOfVar2 == null) {
            if (freeOfVar1 == null && freeOfVar2 == null) {
                IExpr tmp = F.eval(F.Solve(groupSameItems.arg2(), vars.arg2()));
                IExpr rule = tmp.getAt(1).getAt(1);
                result.add(F.Rule(groupSameItems.arg2(), F.Rule(F.Equal(vars.arg2(), rule.getAt(2)), groupSameItems.arg1())));
                IExpr tmp2 = F.eval(F.Solve(groupSameItems.arg1(), vars.arg1()));
                IExpr rule2 = tmp2.getAt(1).getAt(1);
                result.add(F.Rule(groupSameItems.arg1(), F.Rule(F.Equal(vars.arg1(), rule2.getAt(2)), groupSameItems.arg2())));
            } else if (freeOfVar1 == null) {
                result.add(F.Rule(F.Equal(vars.arg2(), freeOfVar2.getAt(2)), noFree));
            } else {
                result.add(F.Rule(F.Equal(vars.arg1(), freeOfVar1.getAt(2)), noFree));
            }
        }
        return result;
    }

    public static IAST getThreeOneSimplifyResult(IAST groupSameItems, IAST vars) {
        return F.List(getThreeOneSimplifyResultInner(groupSameItems, vars.arg1(), vars.arg2(), vars.arg3()), getThreeOneSimplifyResultInner(groupSameItems, vars.arg2(), vars.arg1(), vars.arg3()), getThreeOneSimplifyResultInner(groupSameItems, vars.arg3(), vars.arg1(), vars.arg2()));
    }

    private static IAST getThreeOneSimplifyResultInner(IAST groupSameItems, IExpr var1, IExpr var2, IExpr var3) {
        IExpr solveResultForVar3 = null;
        IExpr solvedVar3Expr = null;
        Iterator<IExpr> it = groupSameItems.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            IExpr item = it.next();
            if (!item.isFree(var3) && (solveResultForVar3 = F.eval(F.Solve(item, var3))) != null && solveResultForVar3.isFree(F.Solve)) {
                solveResultForVar3 = solveResultForVar3.getAt(1).getAt(1).getAt(2);
                if (solveResultForVar3.isRuleAST()) {
                    solveResultForVar3 = solveResultForVar3.getAt(1);
                }
                solvedVar3Expr = item;
            }
        }
        IExpr solveResultForVar2 = null;
        IExpr solvedVar2Expr = null;
        Iterator<IExpr> it2 = groupSameItems.iterator();
        while (true) {
            if (!it2.hasNext()) {
                break;
            }
            IExpr item2 = it2.next();
            if (!item2.isFree(var2) && (item2.isFree(var3) || !item2.equals(solvedVar3Expr))) {
                if (!item2.isFree(var3)) {
                    item2 = item2.replaceAll(F.Rule(var3, solveResultForVar3));
                }
                solveResultForVar2 = F.eval(F.Solve(item2, var2));
                if (solveResultForVar2 != null && solveResultForVar2.isFree(F.Solve)) {
                    solveResultForVar2 = solveResultForVar2.getAt(1).getAt(1).getAt(2);
                    solvedVar2Expr = item2;
                    break;
                }
            }
        }
        if (solveResultForVar2.isRuleAST()) {
            solveResultForVar2 = solveResultForVar2.getAt(1);
        }
        IExpr toRepaceExpr = null;
        for (IExpr item3 : groupSameItems) {
            if (!item3.isFree(var1) && (solvedVar2Expr.isFree(var1) || (!solvedVar2Expr.isFree(var1) && !item3.equals(solvedVar2Expr)))) {
                if (solvedVar3Expr.isFree(var1) || (!solvedVar3Expr.isFree(var1) && !item3.equals(solvedVar3Expr))) {
                    toRepaceExpr = item3;
                    break;
                }
            }
        }
        if (toRepaceExpr == null) {
            return null;
        }
        return F.Rule(F.List(solvedVar2Expr, solvedVar3Expr), F.Rule(F.List(F.Equal(var2, solveResultForVar2), F.Equal(var3, solveResultForVar3)), toRepaceExpr));
    }
}
