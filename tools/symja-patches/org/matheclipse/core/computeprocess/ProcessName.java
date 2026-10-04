package org.matheclipse.core.computeprocess;

public enum ProcessName {
    UNKNOWN(-1),
    SOLVE_TYPE(0),
    GROUP_SAME_ITEM(1),
    EXTRACT_ROOTS_FORMULA(2),
    FACTORIZATION(3),
    COMPELETE_SQUARE(4),
    GAUSSIAN_ELIMINATION(5),
    REPLACE_VARIABLE(6),
    SOLVE_SUB_EQUATION(7),
    CHECK_EQUALITY_RESULT(8),
    SOLVE_INEQUALITY_UNIVARIBALE(9),
    SOLVE_SYSTEM_SIMPLIFY(10);

    public int type;

    ProcessName(int type) {
        this.type = type;
    }
}
