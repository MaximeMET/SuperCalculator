package org.matheclipse.core.form.tex;

import org.matheclipse.core.interfaces.IAST;

/**
 * 三角函数这类具名函数的 TeX 渲染。
 *
 * 上游写成 <code>\cos(x)</code>，参考实现写成教科书的 <code>\cos{x}</code>，
 * 多参数之间用 <code>\,</code> 分隔。
 *
 * 这是本项目对 Symja 的行为补丁之一，依据是差分测试观察到的基准输出，
 * 不是从参考实现复制的代码。
 */
public class TeXFunction extends AbstractConverter {

  String fFunctionName;

  public TeXFunction(final TeXFormFactory factory, final String functionName) {
    super(factory);
    fFunctionName = functionName;
  }

  /** {@inheritDoc} */
  public boolean convert(final StringBuffer buf, final IAST f, final int precedence) {
    buf.append('\\');
    buf.append(fFunctionName);
    for (int i = 1; i < f.size(); i++) {
      buf.append('{');
      fFactory.convert(buf, f.get(i), 0);
      buf.append('}');
      if (i < f.size() - 1) {
        buf.append("\\,");
      }
    }
    return true;
  }
}
