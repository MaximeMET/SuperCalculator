package org.matheclipse.core.form.tex.reflection;

import org.matheclipse.core.form.tex.AbstractConverter;
import org.matheclipse.core.interfaces.IAST;

/**
 * 绝对值的 TeX 渲染。
 *
 * 上游只写裸竖线 <code>|x|</code>，参考实现用 <code>\left|</code> /
 * <code>\right|</code>，这样绝对值里的分式、根式会被撑到合适高度。
 *
 * 这是本项目对 Symja 的行为补丁之一，依据是差分测试观察到的基准输出，
 * 不是从参考实现复制的代码。
 */
public class Abs extends AbstractConverter {

    public Abs() {
    }

    /** {@inheritDoc} */
    public boolean convert(final StringBuffer buf, final IAST f, final int precedence) {
        if (f.size() != 2) {
            return false;
        }
        buf.append("\\left|");
        fFactory.convert(buf, f.arg1(), 0);
        buf.append("\\right|");
        return true;
    }
}
