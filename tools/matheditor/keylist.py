"""原版键盘命令表（抄自 bundle.min.js，见 work/logs/keymap-1.txt）。

两边探针共用这一份，保证比的是同一批输入。
每条：(键名, 类型, 插入内容, 左移次数, 是否走 typedText)
"""

KEYS = [
    # --- NOOP_CMD：直接敲字符 ---
    *[("d" + c, "NOOP", c, 0, True) for c in "0123456789"],
    ("dot", "NOOP", ".", 0, True),
    *[("v-" + c, "NOOP", c, 0, True) for c in "abcdhkpxyzei"],
    ("bang", "NOOP", "!", 0, True),
    ("eq", "NOOP", "=", 0, True),
    ("ge", "NOOP", "\u2265", 0, True),
    ("le", "NOOP", "\u2264", 0, True),
    ("gt", "NOOP", ">", 0, True),
    ("lt", "NOOP", "<", 0, True),
    ("plus", "NOOP", "+", 0, True),
    ("minus", "NOOP", "-", 0, True),
    # --- OP_CMD：函数与符号 ---
    ("times", "OP", r"\times", 0, False),
    ("slash", "OP", r"\slash", 0, False),
    ("degree", "OP", r"\degree", 0, False),
    ("minute", "OP", r"\minute", 0, False),
    ("second", "OP", r"\second", 0, False),
    ("infinity", "OP", r"\infty", 0, False),
    ("pi", "OP", r"\pi", 0, False),
    ("sin", "OP", r"\sin{}", 1, False),
    ("cos", "OP", r"\cos{}", 1, False),
    ("tan", "OP", r"\tan{}", 1, False),
    ("arcsin", "OP", r"\arcsin{}", 1, False),
    ("arccos", "OP", r"\arccos{}", 1, False),
    ("arctan", "OP", r"\arctan{}", 1, False),
    ("ln", "OP", r"\ln{}", 1, False),
    ("log", "OP", r"\log_{}{}", 2, False),
    ("gcd", "OP", r"\gcd({},{})", 2, False),
    ("lcm", "OP", r"\lcm({},{})", 2, False),
    ("abs", "OP", r"\left|{}\right|", 1, False),
    # --- BLOCK_CMD ---
    ("sqrt2", "BLOCK", r"\sqrt{}", 1, False),
    ("sqrt", "BLOCK", r"\sqrt[]{}", 2, False),
    ("int", "BLOCK", r"\int_{}^{}{}d{x}", 3, False),
    ("ddx", "BLOCK", r"\frac{d}{dx}", 0, False),
    ("log2", "BLOCK", r"\log_{2}{}", 1, False),
    ("log10", "BLOCK", r"\log_{10}{}", 1, False),
    ("newline", "BLOCK", r"\newline{}", 0, False),
    ("lim", "BLOCK", r"\lim_{}^{}{}", 3, False),
    ("sum", "BLOCK", r"\sum_{}^{}", 0, False),
    ("dms", "BLOCK", r"\dms{}{}{}", 3, False),
    ("AP", "BLOCK", r"\ap{}{}", 2, False),
    ("CP", "BLOCK", r"\cp{}{}", 2, False),
    # --- FORMULA_BANK ---
    ("fLinear", "BANK", r"\fLinear{}{}", 2, False),
    ("fInverse", "BANK", r"\fInverse{}", 1, False),
    ("fnQuadratic", "BANK", r"\fnQuadratic{}{}{}", 3, False),
    ("fQuadratic", "BANK", r"\fQuadratic{}{}{}", 3, False),
    ("fExponential", "BANK", r"\fExponential{}", 1, False),
    ("fLog", "BANK", r"\fLog{}", 1, False),
    ("fsCircle", "BANK", r"\fsCircle{}{}{}", 3, False),
    ("fCircle", "BANK", r"\fCircle{}{}{}", 3, False),
    ("fsEllipse", "BANK", r"\fsEllipse{}{}", 2, False),
    ("fEllipse", "BANK", r"\fEllipse{}{}{}{}", 4, False),
    ("fsHyperbola", "BANK", r"\fsHyperbola{}{}", 2, False),
    ("fHyperbola", "BANK", r"\fHyperbola{}{}{}{}", 4, False),
    ("fParabola", "BANK", r"\fParabola{}", 1, False),
    # --- 分式 / 括号 / 乘方：typedText ---
    ("frac键", "TYPE", "/", 0, True),
    ("lparen", "TYPE", "(", 0, True),
    ("rparen", "TYPE", ")", 0, True),
    ("pow", "TYPE", "^", 0, True),
]


def js_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def keys_js():
    return ",\n".join(
        "  {key: %s, type: %s, code: %s, back: %d, typed: %s}"
        % (js_str(k), js_str(t), js_str(code), back, "true" if typed else "false")
        for k, t, code, back, typed in KEYS
    )


def seqs_js():
    """连打序列：验证光标的落点和填槽后的 latex。"""
    seqs = [
        ("sqrt后打5", [r"w:\sqrt[]{}", "t:5"]),
        ("sqrt左1次打5", [r"w:\sqrt[]{}", "k:Left", "t:5"]),
        ("sqrt左2次打5", [r"w:\sqrt[]{}", "k:Left", "k:Left", "t:5"]),
        ("sqrt2左1次打5", [r"w:\sqrt{}", "k:Left", "t:5"]),
        ("log左2次打5", [r"w:\log_{}{}", "k:Left", "k:Left", "t:5"]),
        ("log左1次打5", [r"w:\log_{}{}", "k:Left", "t:5"]),
        ("int左3次打5", [r"w:\int_{}^{}{}d{x}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("int左4次打5", [r"w:\int_{}^{}{}d{x}", "k:Left", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("lim左3次打5", [r"w:\lim_{}^{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("abs左1次打5", [r"w:\left|{}\right|", "k:Left", "t:5"]),
        ("dms左3次打5", [r"w:\dms{}{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("ap左2次打5", [r"w:\ap{}{}", "k:Left", "k:Left", "t:5"]),
        ("fLinear左2次打5", [r"w:\fLinear{}{}", "k:Left", "k:Left", "t:5"]),
        ("fLinear左1次打5", [r"w:\fLinear{}{}", "k:Left", "t:5"]),
        ("frac键1/3", ["t:1", "t:/", "t:3"]),
        ("log2左1次打5", [r"w:\log_{2}{}", "k:Left", "t:5"]),
        ("newline后打1", [r"w:\newline{}", "t:1"]),
        ("sin左1次打x", [r"w:\sin{}", "k:Left", "t:x"]),
        ("gcd左2次打5", [r"w:\gcd({},{})", "k:Left", "k:Left", "t:5"]),
        ("fnQuadratic左3次打5", [r"w:\fnQuadratic{}{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("fCircle左3次打5", [r"w:\fCircle{}{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        # 手打序列：这些走 MathQuill 内置命令（分式/括号/乘方），
        # 它们也得算出和原版一样的 symja
        ("手打 x^2", ["t:x", "t:^", "t:2"]),
        ("手打 2^3", ["t:2", "t:^", "t:3"]),
        ("手打 (2+3)", ["t:(", "t:2", "t:+", "t:3", "t:)"]),
        ("手打 1/(2+3)", ["t:1", "t:/", "t:(", "t:2", "t:+", "t:3", "t:)"]),
        ("手打 -5", ["t:-", "t:5"]),
        ("手打 2*3", ["t:2", "t:*", "t:3"]),
        ("手打 5!", ["t:5", "t:!"]),
        ("手打 1<2", ["t:1", "t:<", "t:2"]),
        ("手打 1=", ["t:1", "t:="]),
        ("手打 pi", ["t:\u03c0"]),
        ("手打 e", ["t:e"]),
        ("手打 3.5", ["t:3", "t:.", "t:5"]),
        # 隐式乘法：变量挨着别的操作数时 symja 要补 `*`（原版 MathQuill 的
        # `$` 类行为：`ax` → `a*x`、`2x` → `2*x`、`x(2+3)` → `x*(2+3)`），
        # 两位数（`23`）和乘方（`x^2` → `x^(2)`）不能画蛇添足。
        ("手打 ax", ["t:a", "t:x"]),
        ("手打 x2", ["t:x", "t:2"]),
        ("手打 2x", ["t:2", "t:x"]),
        ("手打 x2y", ["t:x", "t:2", "t:y"]),
        ("手打 23", ["t:2", "t:3"]),
        ("手打 x(2+3)", ["t:x", "t:(", "t:2", "t:+", "t:3", "t:)"]),
        ("手打 (2+3)x", ["t:(", "t:2", "t:+", "t:3", "t:)", "t:x"]),
        ("手打 x.5", ["t:x", "t:.", "t:5"]),
        ("手打 xyz", ["t:x", "t:y", "t:z"]),
        ("手打 ax+b", ["t:a", "t:x", "t:+", "t:b"]),
        # 公式模板：把第一个槽位填上 5，看 symja 里的隐式乘法怎么处理
        ("fInverse填5", [r"w:\fInverse{}", "k:Left", "t:5"]),
        ("fExponential填5", [r"w:\fExponential{}", "k:Left", "t:5"]),
        ("fLog填5", [r"w:\fLog{}", "k:Left", "t:5"]),
        ("fQuadratic填5", [r"w:\fQuadratic{}{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("fsCircle填5", [r"w:\fsCircle{}{}{}", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("fsEllipse填5", [r"w:\fsEllipse{}{}", "k:Left", "k:Left", "t:5"]),
        ("fEllipse填5", [r"w:\fEllipse{}{}{}{}", "k:Left", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("fsHyperbola填5", [r"w:\fsHyperbola{}{}", "k:Left", "k:Left", "t:5"]),
        ("fHyperbola填5", [r"w:\fHyperbola{}{}{}{}", "k:Left", "k:Left", "k:Left", "k:Left", "t:5"]),
        ("fParabola填5", [r"w:\fParabola{}", "k:Left", "t:5"]),
        ("cp填5", [r"w:\cp{}{}", "k:Left", "k:Left", "t:5"]),
        ("int填被积函数", [r"w:\int_{}^{}{}d{x}", "k:Left", "t:5"]),
        ("int填上界", [r"w:\int_{}^{}{}d{x}", "k:Left", "k:Left", "t:5"]),
        ("lim填趋近值", [r"w:\lim_{}^{}{}", "k:Left", "k:Left", "t:5"]),
        ("lim填表达式", [r"w:\lim_{}^{}{}", "k:Left", "t:5"]),
    ]
    return ",\n".join(
        "  {name: %s, steps: [%s]}"
        % (js_str(name), ", ".join(js_str(s) for s in steps))
        for name, steps in seqs
    )


RUNNER = """
var out = [];
var seqOut = [];

function snapHtml(el) {
  // 去掉 reflow 产生的内联样式（transform: scale(...) 之类），
  // 只比结构
  try { return el.html().replace(/ style="[^"]*"/g, ''); }
  catch (e) { return '<err: ' + e + '>'; }
}

function symjaOf(field, fn) {
  if (!fn) return null;
  try { return String(fn()); } catch (e) { return '<err: ' + e + '>'; }
}

function runAll(field, symjaFn) {
  for (var i = 0; i < KEYS.length; i++) {
    var k = KEYS[i];
    field.latex('');
    field.moveToRightEnd();
    var rec = { key: k.key, type: k.type, code: k.code, back: k.back, typed: k.typed,
                latex: null, symja: null, html: null, afterBack: null, err: null };
    try {
      if (k.typed) field.typedText(k.code); else field.write(k.code);
      rec.latex = field.latex();
      rec.html = snapHtml(field);
      rec.symja = symjaOf(field, symjaFn);
      for (var j = 0; j < k.back; j += 1) field.keystroke('Left');
      rec.afterBack = field.latex();
    } catch (e) { rec.err = String(e); }
    out.push(rec);
  }

  for (var s = 0; s < SEQS.length; s++) {
    field.latex('');
    field.moveToRightEnd();
    var rec2 = { name: SEQS[s].name, steps: SEQS[s].steps.join(' | '), latex: null,
                 symja: null, html: null, err: null };
    try {
      for (var t = 0; t < SEQS[s].steps.length; t++) {
        var st = SEQS[s].steps[t];
        var kind = st.slice(0, 1), arg = st.slice(2);
        if (kind === 'w') field.write(arg);
        else if (kind === 't') field.typedText(arg);
        else if (kind === 'k') field.keystroke(arg);
      }
      rec2.latex = field.latex();
      rec2.html = snapHtml(field);
      rec2.symja = symjaOf(field, symjaFn);
    } catch (e) { rec2.err = String(e); }
    seqOut.push(rec2);
  }
}
"""


def page_js(with_editor):
    """返回页面里要插入的那段 JS（KEYS/SEQS 定义 + 驱动）。"""
    head = "var KEYS = [\n%s\n];\nvar SEQS = [\n%s\n];\n" % (keys_js(), seqs_js())
    if with_editor:
        tail = """
var field = window.SuperCalcEditor.__debug.field;
runAll(field, function () { return field.__controller.root.symja(); });
window.__results = out;
window.__seqs = seqOut;
window.__ready = true;
"""
    else:
        tail = """
var MQ = null;
if (typeof MathQuill.getInterface === 'function') {
  try { MQ = MathQuill.getInterface(2); } catch (e) { MQ = MathQuill.getInterface(1); }
} else {
  MQ = MathQuill;
}
var field = MQ.MathField(document.getElementById('probeField'), {});
runAll(field, typeof field.symja === 'function' ? function () { return field.symja(); } : null);
window.__results = out;
window.__seqs = seqOut;
window.__ready = true;
"""
    return head + RUNNER + tail
