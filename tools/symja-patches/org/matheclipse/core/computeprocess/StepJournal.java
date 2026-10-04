package org.matheclipse.core.computeprocess;

import java.util.ArrayList;
import java.util.List;

import org.matheclipse.core.expression.F;
import org.matheclipse.core.expression.IntegerSym;
import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

/**
 * 解方程过程的「记账本」。
 *
 * <p>
 * 参考实现把中间结果记进 trace 列表，再由 core/computeprocess 里那套
 * 按尾部下标取帧的策略整理成步骤。尾部下标依赖「第几层求值产生了几个 frame」，
 * 换一个方程就会漂，实测很容易越界。
 *
 * <p>
 * 这里换一种做法：`Solve / Roots / QuarticSolver` 里的 trace 发点原地不动，
 * `TraceStack` 每收到一个发点就顺手往本线程的记账本里写一条
 * `[步骤码, 输入表达式, 结果]`。步骤顺序就是算法真实执行顺序，
 * 不再依赖 frame 的位置。记账本只在显式 {@link #begin()} 之后才记，
 * 日常求值零开销。
 */
public final class StepJournal {

	/** 多项式次数，例如 `2` 表示二次方程。 */
	public static final int DEGREE = 1;
	/** 因式分解结果，例如 `{x-3,x-2}`。 */
	public static final int FACTORIZATION = 2;
	/** 三次 / 四次方程的系数（常数项在前）。 */
	public static final int COEFFICIENTS = 3;
	/** 二次方程求根公式的三个系数 `{a,b,c}`。 */
	public static final int QUADRATIC_COEFFICIENTS = 4;
	/** 配方后的等价方程，例如 `(-x+5/2)^2==1/4`。 */
	public static final int COMPLETE_SQUARE = 5;
	/** 各因式展开（三次以上）。 */
	public static final int EXPAND_FACTORS = 6;
	/** 方程组的附加信息 `{方程表, 类型, 未知数表, 原输入}`。 */
	public static final int EXTRA_INFO = 7;
	/** 未知数个数。 */
	public static final int VARIABLE_COUNT = 8;
	/** 换元信息 `{原式, 新表达式, 规则}`。 */
	public static final int REPLACE_RULE = 9;
	/** 子方程各自的解。 */
	public static final int SUB_RESULTS = 10;

	private static final ThreadLocal<List<Entry>> ACTIVE = new ThreadLocal<List<Entry>>();

	private StepJournal() {
	}

	private static final class Entry {
		final int code;
		final IExpr input;
		final IExpr result;

		Entry(int code, IExpr input, IExpr result) {
			this.code = code;
			this.input = input;
			this.result = result;
		}
	}

	/**
	 * trace 发点里的提示串 -> 步骤码。返回 0 表示这条不用记账
	 * （EvalEngine 自己的 "Evaluation loop" 就走这里被过滤掉）。
	 */
	public static int codeOf(String hint) {
		if (hint == null) {
			return 0;
		}
		if ("Polynomial's degree!".equals(hint)) {
			return DEGREE;
		}
		if ("Polynomial's factor!".equals(hint)) {
			return FACTORIZATION;
		}
		if ("Polynomial's cofficients!".equals(hint)) {
			return COEFFICIENTS;
		}
		if ("Formula!".equals(hint)) {
			return QUADRATIC_COEFFICIENTS;
		}
		if ("Compelete Square!".equals(hint)) {
			return COMPLETE_SQUARE;
		}
		if ("Expand roots' factors".equals(hint)) {
			return EXPAND_FACTORS;
		}
		if ("Polynomial's Extrainfo!".equals(hint)) {
			return EXTRA_INFO;
		}
		if ("Polynomial's variable number!".equals(hint)) {
			return VARIABLE_COUNT;
		}
		if ("Replace rule!".equals(hint)) {
			return REPLACE_RULE;
		}
		if ("Add sub equation's results!".equals(hint)) {
			return SUB_RESULTS;
		}
		return 0;
	}

	/** 开始记账：清掉本线程上的旧记录。 */
	public static void begin() {
		ACTIVE.set(new ArrayList<Entry>());
	}

	public static boolean isActive() {
		return ACTIVE.get() != null;
	}

	/** 记一条：`hint` 认不出来就丢掉。 */
	public static void record(String hint, IExpr input, IExpr result) {
		List<Entry> list = ACTIVE.get();
		if (list == null) {
			return;
		}
		int code = codeOf(hint);
		if (code == 0) {
			return;
		}
		list.add(new Entry(code, input, result));
	}

	/**
	 * 结束记账，返回 `{{步骤码, 输入, 结果}, ...}`；同时清掉线程上的状态。
	 */
	public static IAST end() {
		List<Entry> list = ACTIVE.get();
		ACTIVE.remove();
		IAST out = F.List();
		if (list != null) {
			for (int i = 0; i < list.size(); i++) {
				Entry e = list.get(i);
				out.add(F.List(IntegerSym.valueOf(e.code), e.input, e.result));
			}
		}
		return out;
	}

	/** 放弃这次记账（异常路径用）。 */
	public static void cancel() {
		ACTIVE.remove();
	}
}
