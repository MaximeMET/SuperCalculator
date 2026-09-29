/*
 * 本项目追加，不属于上游 MathQuill。
 *
 * MathQuill 0.10.1 只导出 getInterface 那一套公开 API，而 LatexCmds、CharCmds
 * 和几个构造器都藏在闭包里。键盘上的换行、度分秒、等差/等比数列以及「公式」页
 * 的函数模板必须注册成真正的 LaTeX 命令（这样才能有独立的输入槽、被 latex()
 * 正确还原），所以这里把需要的东西挂到 MathQuill 上。
 *
 * 由 tools/mathquill-concat.js 插在 src/outro.js 之前——也就是仍在这个 IIFE 内部。
 * 上游文件一个都没改，MPL-2.0 的「文件级 copyleft」不涉及上游源码。
 */
MathQuill.LatexCmds = LatexCmds;
MathQuill.CharCmds = CharCmds;
MathQuill.P = P;
MathQuill.Node = Node;
MathQuill.MathCommand = MathCommand;
MathQuill.MathBlock = MathBlock;
MathQuill.Symbol = Symbol;
MathQuill.VanillaSymbol = VanillaSymbol;
MathQuill.BinaryOperator = BinaryOperator;
MathQuill.Parser = Parser;
MathQuill.latexMathParser = latexMathParser;
MathQuill.Options = Options;
// 下面几个是「键盘会用到、但又必须补 symja()」的内置命令类
MathQuill.NthRoot = NthRoot;
MathQuill.SupSub = SupSub;
MathQuill.Bracket = Bracket;
