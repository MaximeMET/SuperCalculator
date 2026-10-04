package org.matheclipse.core.computeprocess;

import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

public interface IComputeProcess {
    IAST extractProcess(IExpr iExpr, IAST iast);
}
