package org.matheclipse.core.eval.util;

import org.matheclipse.core.eval.EvalEngine;
import org.matheclipse.core.expression.F;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public class ResultFilterUtils {

    private enum TrigEquation {
        NONE { // from class: org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation.1
            @Override // org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation
            public int getId() {
                return -1;
            }
        },
        SIN { // from class: org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation.2
            @Override // org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation
            public int getId() {
                return 0;
            }
        },
        COS { // from class: org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation.3
            @Override // org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation
            public int getId() {
                return 1;
            }
        },
        TAN { // from class: org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation.4
            @Override // org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation
            public int getId() {
                return 2;
            }
        },
        COT { // from class: org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation.5
            @Override // org.matheclipse.core.eval.util.ResultFilterUtils.TrigEquation
            public int getId() {
                return 3;
            }
        };

        public abstract int getId();
    }

    public static IExpr filterResult(IExpr inputExpression, IExpr result) {
        if (!inputExpression.equals(result) && !inputExpression.isFree(F.Solve)) {
            return simplfyResultForSolve(inputExpression, result);
        }
        return result;
    }

    public static boolean isFinal(String result) {
        char tp;
        int p = result.indexOf("rror");
        if ((p > 0 && ((tp = result.charAt(p - 1)) == 'E' || tp == 'e')) || result.contains("**")) {
            return false;
        }
        int p1 = result.indexOf("\\text{");
        if (p1 < 0) {
            return true;
        }
        int p2 = result.indexOf(125);
        try {
            String funcName = result.substring("\\text{".length() + p1, p2);
            return !funcName.matches("[A-Z][a-zA-Z]+");
        } catch (Exception e) {
            return true;
        }
    }

    private static TrigEquation getTrigEquation(IExpr inputExpression) {
        IAST equation = (IAST) inputExpression.getAt(1);
        if (!equation.arg1().getAt(1).equals(equation.arg2())) {
        }
        if (!equation.isFree(F.Sin)) {
            return TrigEquation.SIN;
        }
        if (!equation.isFree(F.Cos)) {
            return TrigEquation.COS;
        }
        if (!equation.isFree(F.Tan)) {
            return TrigEquation.TAN;
        }
        if (!equation.isFree(F.Cot)) {
            return TrigEquation.COT;
        }
        return TrigEquation.NONE;
    }

    private static boolean hasSymbols(IExpr expr) {
        return (expr.isFree(F.a) && expr.isFree(F.b) && expr.isFree(F.c) && expr.isFree(F.x) && expr.isFree(F.y) && expr.isFree(F.z)) ? false : true;
    }

    public static IExpr simplfyResultForSolve(IExpr inputExpression, IExpr result) {
        boolean originalTraceMode = EvalEngine.get().isTraceMode();
        setEngineTraceMode(originalTraceMode, false);
        IAST resultList = (IAST) result;
        int i = 1;
        IAST mergeResult = F.List();
        while (i < resultList.size()) {
            if (((IAST) resultList.get(i)).isList()) {
                IAST rules = (IAST) resultList.get(i);
                IAST mergeRules = F.List();
                for (IExpr ruleIter : rules) {
                    IAST rule = (IAST) ruleIter;
                    IExpr resultInRule = rule.arg2();
                    IExpr symbol = rule.arg1();
                    IExpr numericResult = null;
                    if (!resultInRule.isInteger() && !resultInRule.isNumeric() && !resultInRule.isFraction() && !hasSymbols(resultInRule)) {
                        numericResult = F.eval(F.unaryAST1(F.N, resultInRule));
                    }
                    if (resultInRule.isPower() && resultInRule.getAt(2).isFraction() && resultInRule.getAt(2).isNegative()) {
                        resultList.set(i, rule.setAtClone(2, F.eval(F.Power(resultInRule, F.Divide(F.CN1, resultInRule.getAt(2))))));
                    } else if (resultInRule.isTimes()) {
                        IExpr denomResult = resultInRule.getAt(2);
                        IExpr enumResult = resultInRule.getAt(1);
                        if (denomResult.isPower() && denomResult.getAt(2).isFraction() && denomResult.getAt(2).isNegative()) {
                            IAST denomExponential = F.Divide(F.CN1, denomResult.getAt(2));
                            ((IAST) resultInRule).set(2, F.eval(F.Power(denomResult, denomExponential)));
                            ((IAST) resultInRule).set(1, F.eval(F.Times(enumResult, F.eval(F.Power(denomResult.getAt(1), F.Subtract(denomResult.getAt(2), F.CN1))))));
                        }
                    }
                    if (numericResult != null) {
                        mergeRules.add(F.Rule(symbol, F.Rule(resultInRule, numericResult)));
                    } else {
                        mergeRules.add(ruleIter);
                    }
                }
                mergeResult.add(mergeRules);
            }
            i++;
        }
        setEngineTraceMode(originalTraceMode, true);
        if (i != 1) {
            TrigEquation trigEquationType = getTrigEquation(inputExpression);
            if (!trigEquationType.equals(TrigEquation.NONE)) {
                mergeResult.add(F.p);
            }
            return mergeResult;
        }
        return inputExpression;
    }

    public static void setEngineTraceMode(boolean originalTraceMode, boolean mode) {
        if (originalTraceMode) {
            EvalEngine.get().setTraceMode(mode);
        }
    }
}
