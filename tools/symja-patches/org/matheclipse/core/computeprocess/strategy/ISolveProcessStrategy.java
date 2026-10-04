package org.matheclipse.core.computeprocess.strategy;

import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public interface ISolveProcessStrategy {
    IAST generateProcess(IExpr iExpr, IAST iast);
}
