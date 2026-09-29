package org.matheclipse.core.form.tex.reflection;

import org.matheclipse.core.form.tex.AbstractConverter;
import org.matheclipse.core.interfaces.IAST;

/**
 * 对数函数的 TeX 渲染。
 *
 * 上游 Symja 没有这个转换器，`Log` 会落到默认分支渲染成 `\log (x)`。
 * 参考实现里有一个等价物，把对数渲染成教科书写法：
 *
 * <pre>
 *   Log(x)        ->  \ln{x}
 *   Log(E, x)     ->  \ln{x}
 *   Log(b, x)     ->  \log_{b}{x}     （b != E）
 * </pre>
 *
 * 这是本项目对 Symja 的唯一一处补丁。属于行为规格的重新实现，
 * 不是从参考实现复制的代码。
 *
 * 生效机制：{@code AbstractTeXFormFactory} 按「函数头的类名」在
 * {@code org.matheclipse.core.form.tex.reflection} 包里做反射查找，
 * 找到同名类就用它来渲染。
 */
public class Log extends AbstractConverter {

    public Log() {
    }

    @Override
    public boolean convert(final StringBuffer buf, final IAST f, final int precedence) {
        final int argc = f.size() - 1;

        if (argc == 2) {
            if (f.arg1().isE()) {
                // 以 e 为底：直接写 ln
                buf.append("\\ln{");
                fFactory.convert(buf, f.arg2(), 0);
                buf.append('}');
                return true;
            }
            // 一般底数：写成下标形式
            buf.append("\\log_{");
            fFactory.convert(buf, f.arg1(), 0);
            buf.append("}{");
            fFactory.convert(buf, f.arg2(), 0);
            buf.append('}');
            return true;
        }

        if (argc == 1) {
            // 单参数即为自然对数
            buf.append("\\ln{");
            fFactory.convert(buf, f.arg1(), 0);
            buf.append('}');
            return true;
        }

        // 其它参数个数交回默认处理
        return false;
    }
}
