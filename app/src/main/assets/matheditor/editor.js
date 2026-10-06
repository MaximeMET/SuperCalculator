/*
 * 公式编辑器。
 *
 * 组成：
 *   1. 命令层：键盘要用的 LaTeX 命令 + 每个命令自己的 symja()（引擎输入）
 *   2. 一个可编辑的 MathQuill 域当公式输入区，两个静态域显示结果
 *   3. 撤销 / 重做
 *   4. 输入停顿 500ms 后把 symja 交给 Android 换回结果
 *   5. 暴露给 Android 的 window.SuperCalcEditor
 *
 * 为什么结果也用 MathQuill 而不是 MathJax：参考实现就是这么做的。
 * 编辑器页（Mathbot Editor.html）根本没引 MathJax，MathJax 只出现在独立的结果页。
 *
 * 命令层为什么长这样：原版 APK 里的 MathQuill 是一个**带 symja() 的分支**，
 * 每个命令自己算引擎输入，算完连同 latex 一起交给 Android。我们照这个架构来，
 * 好处是规则跟着命令走、不用把 latex 再解析一遍；而且可以拿原版当活体标尺逐条
 * 比对（基准数据 work/logs/keys-orig.json，回归脚本 work/mqtest/run_probes.ps1）。
 *
 * 下面每个命令的 ctrlSeq / 槽位顺序 / 渲染结构 / latex / symja 都是对着原版
 * 实测结果写的，不是猜的。改动前请先跑一遍对照脚本。
 */
(function (window, document) {
  'use strict';

  var MathQuill = window.MathQuill;
  var MQ = MathQuill.getInterface(2);

  var LatexCmds = MathQuill.LatexCmds;
  var CharCmds = MathQuill.CharCmds;
  var P = MathQuill.P;
  var MathCommand = MathQuill.MathCommand;
  var MathBlock = MathQuill.MathBlock;
  var Symbol = MathQuill.Symbol;
  var BinaryOperator = MathQuill.BinaryOperator;
  var SupSub = MathQuill.SupSub;
  var Parser = MathQuill.Parser;
  var latexMathParser = MathQuill.latexMathParser;

  var string = Parser.string;
  var optWhitespace = Parser.optWhitespace;
  var succeed = Parser.succeed;
  var mqBlock = latexMathParser.block;

  /** 参考实现里前端防抖写的是 500ms。 */
  var DEBOUNCE_MS = 500;

  // ---------------------------------------------------------------
  // 1a. symja()：引擎输入的算法挂在每个节点上
  // ---------------------------------------------------------------

  /** 块 = 子节点依次拼接。 */
  MathBlock.prototype.symja = function () {
    return this.foldChildren('', function (acc, child) {
      return acc + child.symja();
    });
  };

  /** 兜底：没单独定义的命令不产出任何东西，免得 undefined 混进表达式。 */
  MathCommand.prototype.symja = function () {
    return '';
  };

  /**
   * 单字符节点（数字、字母、运算符）的 symja 基本就是它自己的文字，
   * 少数几个写法不同，表里的值全部来自原版实测。
   */
  var SYMJA_TEXT = {
    e: 'E', // 自然常数：原版把字母 e 换成 E
    '=': '==', // Symja 里判等是 ==
    '\\times': '*',
    '\\cdot': '*',
    '\\div': '/',
    '\\slash': '/',
    '\\ge': '>=',
    '\\le': '<=',
    // 希腊字母：编辑区里是 \theta 这类命令，送引擎时换成 Symja 的符号名
    //（实测 Symja 2016 认 theta/lambda 这类名字，不认 Unicode 字符）
    '\\theta': 'theta',
    '\\phi': 'phi',
    '\\lambda': 'lambda',
    '\\mu': 'mu',
    '\\sigma': 'sigma',
    '\\omega': 'omega',
  };

  /**
   * 变量节点（拉丁字母与第 3 页的希腊字母）判定。
   *
   * 原版 MathQuill 分支里带隐式乘法的是 `$` 类（`<var>`），希腊字母是普通符号类；
   * 我们这两种都走 [Symbol]，所以按 ctrlSeq 认。
   */
  var VARIABLE_CTRL = /^(?:[a-zA-Z]|\\theta|\\phi|\\lambda|\\mu|\\sigma|\\omega)$/;

  function isVariableNode(node) {
    return !!node && typeof node.ctrlSeq === 'string' &&
      VARIABLE_CTRL.test(node.ctrlSeq.replace(/\s+$/, ''));
  }

  /**
   * 单字符节点的 symja。
   *
   * 变量（字母）要按原版补**隐式乘号**：`ax` → `a*x`、`2x` → `2*x`、
   * `x(2+3)` → `x*(2+3)`，而 `23` 保持 `23`、`x^2` 保持 `x^(2)`。判定和
   * vanilla MathQuill 的 `Variable.text()` 同一套邻居规则（原版就是从它改的），
   * 我们只是把结果接到 symja 上。少了它，`ax` 会被 Symja 当成一个叫 `ax`
   * 的符号——积分/求导按钮不会出现，自动结果只会回显 `ax`。
   */
  Symbol.prototype.symja = function () {
    var ctrl = String(this.ctrlSeq || '').replace(/\s+$/, '');
    var out;
    if (SYMJA_TEXT[ctrl] !== undefined) {
      out = SYMJA_TEXT[ctrl];
    } else {
      var t = this.textTemplate;
      var text = (t && t[0]) || ctrl;
      out = SYMJA_TEXT[text] !== undefined ? SYMJA_TEXT[text] : text;
    }
    if (!isVariableNode(this)) return out;

    var left = this[MathQuill.L];
    var right = this[MathQuill.R];
    if (left && !isVariableNode(left) && !(left instanceof BinaryOperator) &&
        left.ctrlSeq !== '\\ ') {
      out = '*' + out;
    }
    if (right && !(right instanceof BinaryOperator) && !(right instanceof SupSub)) {
      out = out + '*';
    }
    return out;
  };

  // 分式：((分子)/(分母))
  LatexCmds.frac.prototype.symja = function () {
    return '((' + this.blocks[0].symja() + ')/(' + this.blocks[1].symja() + '))';
  };

  // 平方根：sqrt(x)
  LatexCmds.sqrt.prototype.symja = function () {
    return 'sqrt(' + this.blocks[0].symja() + ')';
  };

  // n 次根：((被开方数)^(1/(根指数)))，根指数空着时按 2 算
  MathQuill.NthRoot.prototype.symja = function () {
    var index = this.blocks[0].symja() || '2';
    return '((' + this.blocks[1].symja() + ')^(1/(' + index + ')))';
  };

  /**
   * 上下标：_() / ^()，原版两个槽位都写出来（空着也写）。
   *
   * 例外：空着的次数位按一次方算（x^| → x^(1)）。这是用户点名的行为：
   * × ÷ = 从空上标里跳出去之后，槽位留在那儿是空的，进引擎的不能是
   * 语法不完整的 x^()。没有底数的裸 ^（公式开头直接按 ^）保持原版的
   * ^()，对照基准里就是这一条。
   */
  MathQuill.SupSub.prototype.symja = function () {
    var out = '';
    if (this.sub) out += '_(' + this.sub.symja() + ')';
    if (this.sup) {
      var sup = this.sup.symja();
      if (sup === '' && this[-1]) sup = '1';
      out += '^(' + sup + ')';
    }
    return out;
  };

  // 括号 / 绝对值：|□| 在引擎里是 Abs(□)
  MathQuill.Bracket.prototype.symja = function () {
    // 注意左右是 MathQuill 的 L / R（-1 / 1），不是 0 / 1
    var open = this.sides[MathQuill.L].ch;
    var close = this.sides[MathQuill.R].ch;
    var inner = this.blocks[0] ? this.blocks[0].symja() : '';
    return open === '|' ? 'Abs(' + inner + ')' : open + inner + close;
  };

  // ---------------------------------------------------------------
  // 1b. 注册键盘要用的命令
  // ---------------------------------------------------------------

  /**
   * 槽位解析：把 'block'（{}）、'sub'（_{}）、'sup'（^{}）和字面量串起来。
   *
   * 槽位按出现顺序记进 this.blocks，这同时决定了两件事：
   *   * latex/symja 里 blocks[i] 指的是哪个槽；
   *   * 光标从右往左走时先碰到谁（第 N 个槽位左移 N 次就到）。
   * 所以下面每个命令的 slots 顺序都是对着原版的实测结果定的。
   */
  function slotParser(steps) {
    return function () {
      var self = this;
      var blocks = (self.blocks = []);
      var parser = optWhitespace;

      steps.forEach(function (step) {
        if (step === 'block' || step === 'sub' || step === 'sup') {
          var target = MathBlock();
          target.adopt(self, self.ends[1], 0);
          blocks.push(target);
          if (step === 'sub') parser = parser.then(string('_'));
          else if (step === 'sup') parser = parser.then(string('^'));
          parser = parser.then(mqBlock).then(function (src) {
            src.children().adopt(target, target.ends[1], 0);
            return succeed(self);
          });
        } else {
          // 字面量：匹配完还得把「当前命令」留在结果里，
          // 否则 parser 收完最后一个 ')' 就把结果换成那个字符串了
          parser = parser.then(string(step).result(self));
        }
      });
      return parser.then(succeed(self));
    };
  }

  /** 注册一个带槽位的命令。spec.slots / spec.latex / spec.symja。 */
  function defCommand(name, ctrlSeq, template, spec) {
    var klass = P(MathCommand, function (_) {
      _.ctrlSeq = ctrlSeq;
      _.htmlTemplate = template;
      _.textTemplate = [''];
      _.parser = slotParser(spec.slots || []);
      _.latex = function () {
        return spec.latex(this.blocks);
      };
      if (spec.symja) {
        _.symja = function () {
          return spec.symja(this.blocks);
        };
      }
    });
    LatexCmds[name] = klass;
    return klass;
  }

  /** 注册一个没有槽位的符号：latex 就是 ctrlSeq，text 是它送给引擎的写法。 */
  function defSymbol(name, ctrlSeq, template, text) {
    var klass = P(Symbol, function (_) {
      _.init = function () {
        // 注意是 Symbol.prototype.init：pjs 的方法挂在原型上，没有静态 init
        Symbol.prototype.init.call(this, ctrlSeq, template, text);
      };
    });
    LatexCmds[name] = klass;
    return klass;
  }

  // ---- 运算符 ----

  // 除号：原版把它画成 ÷，latex 里仍是 \slash（和「分式」键区分开）
  defSymbol('slash', '\\slash', '<span class="mq-binary-operator">&divide;</span>', '/');

  /**
   * 抛物线的示意图（示例行「绘制图像：y = x² + 2x ⇒ ⌣」的右边）。
   *
   * 原版那条示例的右边不是并集符号，而是**画出来的一段抛物线**（位图里量到
   * 41×52 xhdpi px、顶点朝下、描边 2px）。这里用同一套自绘 SVG 当符号画出来，
   * 见 editor.css 的 .mq-parabola。只给示例行用，不进引擎：它的 symja 是空的。
   */
  defSymbol('parabola', '\\parabola', '<span class="mq-parabola"></span>', '');

  /**
   * 两行方程组的大括号（示例行「求解方程组」专用）。
   *
   * 不能拿 `\left\{` 凑：MathQuill 的定界符是**把字符纵向拉伸**（实测 scale(1.2,
   * 2.6)），拉出来是一根细长弧，中间那个腰几乎看不见；原版位图里的括号是画出来的，
   * 腰很明确。这里用和编辑区同一个自绘 SVG（bracket-2-7.svg，见 editor.css 的
   * .mq-sys-brace），字号一变括号跟着变高，形状不变形。
   *
   * 槽位里就是那两行（`\newline` 分隔），括号按内容高度撑满 —— 和原版位图一致。
   */
  defCommand(
    'sysbrace',
    '\\sysbrace',
    '<span class="mq-sys-brace"><span class="mq-sys-brace-img"></span>' +
      '<span class="mq-non-leaf">&0</span></span>',
    {
      slots: ['block'],
      latex: function (b) {
        return '\\sysbrace{' + b[0].latex() + '}';
      },
      symja: function (b) {
        return b[0].symja();
      },
    }
  );

  /**
   * 两行方程组中间那个 ⇒（示例行专用）。
   *
   * 它左右两边都是两行高的 `\sysbrace`，按基线排的话箭头会落在**第二行**的
   * 基线上，看着整体往下掉；原版位图里箭头是压在两行中线上（量下来箭头中心
   * 和括号中心齐平）。所以单独注册一个符号，用 CSS 的 vertical-align 往上抬，
   * 见 editor.css 的 .mq-sys-arrow。
   */
  defSymbol('sysarrow', '\\sysarrow', '<span class="mq-sys-arrow">&rArr;</span>', '');

  /**
   * 箭头族的左右间距。
   *
   * MathQuill 只把五个「单词形」箭头注册成了 BinaryOperator（带
   * .mq-binary-operator，padding 0.2em）：`\to`、`\gets`、`\implies`、
   * `\impliedby`、`\iff`；其余箭头全是 VanillaSymbol，前后不留空。于是同一个
   * 符号换个写法就两种排版：`\to` 有空而 `\rightarrow` 贴死，`\implies` 有空而
   * `\Rightarrow` 贴死。LaTeX 里它们同为二元关系（\mathrel），原版位图里
   * 「算式 ⇒ 结果」的箭头两侧也是留空的，所以这里照 BinaryOperator 重挂一遍，
   * 每个写法的别名（\rArr、\larr、\harr…）一并接上。
   *
   * text 传空串是有意的：MathQuill 的 Symbol.init 在没给 text 时会把命令名当
   * 正文（`\leftarrow ` → `leftarrow `），而 text 正是送给 Symja 引擎的字符串，
   * 送过去只会让引擎不认。这些箭头目前只用于显示（举例行、历史、结果），编辑器
   * 键盘也打不出来，所以干脆不参与引擎表达式。
   *
   * ctrlSeq 末尾的空格按 MathQuill 原样保留：latex 导出时它能把
   * `\leftarrow b` 和 `\leftarrowb` 分开，省得再解析时命令名把后面的字母吃进去。
   */
  [
    // 单线箭头
    ['\\leftarrow ', '&larr;', ['leftarrow', 'larr']],
    ['\\rightarrow ', '&rarr;', ['rightarrow', 'rarr']],
    ['\\leftrightarrow ', '&harr;', ['leftrightarrow', 'harr', 'lrarr']],
    // 双线箭头
    ['\\Leftarrow ', '&lArr;', ['Leftarrow', 'lArr']],
    ['\\Rightarrow ', '&rArr;', ['Rightarrow', 'rArr']],
    ['\\Leftrightarrow ', '&hArr;', ['Leftrightarrow', 'hArr', 'lrArr']],
    // 长箭头
    ['\\longleftarrow ', '&#8592;', ['longleftarrow']],
    ['\\longrightarrow ', '&#8594;', ['longrightarrow']],
    ['\\longleftrightarrow ', '&#8596;', ['longleftrightarrow']],
    ['\\Longleftarrow ', '&#8656;', ['Longleftarrow']],
    ['\\Longrightarrow ', '&#8658;', ['Longrightarrow']],
    ['\\Longleftrightarrow ', '&#8660;', ['Longleftrightarrow']],
    // 上下箭头
    ['\\uparrow ', '&uarr;', ['uparrow', 'uarr', 'diverges']],
    ['\\downarrow ', '&darr;', ['downarrow', 'darr', 'dnarr', 'dnarrow', 'converges']],
    ['\\updownarrow ', '&#8597;', ['updownarrow']],
    ['\\Uparrow ', '&uArr;', ['Uparrow', 'uArr']],
    ['\\Downarrow ', '&dArr;', ['Downarrow', 'dArr', 'dnArr', 'dnArrow']],
    ['\\Updownarrow ', '&#8661;', ['Updownarrow']],
    // 映射、斜向、钩形、鱼叉
    ['\\mapsto ', '&#8614;', ['mapsto']],
    ['\\nearrow ', '&#8599;', ['nearrow']],
    ['\\searrow ', '&#8600;', ['searrow']],
    ['\\swarrow ', '&#8601;', ['swarrow']],
    ['\\nwarrow ', '&#8598;', ['nwarrow']],
    ['\\hookleftarrow ', '&#8617;', ['hookleftarrow']],
    ['\\hookrightarrow ', '&#8618;', ['hookrightarrow']],
    ['\\leftharpoonup ', '&#8636;', ['leftharpoonup']],
    ['\\leftharpoondown ', '&#8637;', ['leftharpoondown']],
    ['\\rightharpoonup ', '&#8640;', ['rightharpoonup']],
    ['\\rightharpoondown ', '&#8641;', ['rightharpoondown']],
  ].forEach(function (row) {
    var klass = defSymbol(
      row[2][0],
      row[0],
      '<span class="mq-binary-operator">' + row[1] + '</span>',
      ''
    );
    row[2].forEach(function (key) {
      LatexCmds[key] = klass;
    });
  });

  // 度、分、秒是三个独立符号，latex 借用了 ^\circ / ^\prime / ^\pprime
  defSymbol('degree', '^\\circ', '<span>&deg;</span>', 'degree');
  defSymbol('minute', '^\\prime', '<span>&#39;</span>', 'arcminute');
  defSymbol('second', '^\\pprime', '<span>&quot;</span>', 'arcsecond');

  /*
   * 细空格 \, ——引擎给的结果串里常见（`\frac{3\,\pi}{2}`），官方 MathQuill 不认这个
   * 命令，解析到它就直接断在半路；原版那个 MathQuill 分支是认的。这里补一个，
   * 按原版实机效果做成零宽（它那边 \, 不占宽度，只是让解析继续往下走）。
   */
  defSymbol(
    ',',
    '\\,',
    '<span class="mq-thinspace"></span>',
    ' '
  );

  // ---- 函数：名字 + 括号 + 一个槽位 ----
  // 官方 0.10.1 的 \sin 不带槽位（只是个函数名），原版带，所以自己定义。
  ['sin', 'cos', 'tan', 'arcsin', 'arccos', 'arctan', 'ln'].forEach(function (name) {
    defCommand(
      name,
      '\\' + name,
      '<span class="mq-non-leaf"><span class="non-italicized-function">' + name + '</span>' +
        '<span class="mq-scaled mq-paren">(</span>' +
        '<span class="mq-non-leaf"><span class="mq-non-leaf">&0</span></span>' +
        '<span class="mq-scaled mq-paren">)</span></span>',
      {
        slots: ['block'],
        // 空槽位要留一个空格，和 MathQuill 自己的写法一致（原版实测 \sin{ }）
        latex: function (b) {
          return '\\' + name + '{' + (b[0].latex() || ' ') + '}';
        },
        symja: function (b) {
          return name + '(' + b[0].symja() + ')';
        },
      }
    );
  });

  /**
   * 对数：log 底数 真数。
   *
   * 键盘上有三个键共用它：log（空底数）、log2、log10。
   * 引擎输入统一换算成换底公式 (ln(真数)/ln(底数))，原版就是这样。
   */
  defCommand(
    'log',
    '\\log',
    '<span class="mq-non-leaf"><span class="non-italicized-function">log</span>' +
      '<span class="mq-supsub mq-non-leaf"><span class="mq-sub">&0</span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span>' +
      '<span class="mq-scaled mq-paren">(</span><span class="mq-non-leaf">&1</span>' +
      '<span class="mq-scaled mq-paren">)</span></span>',
    {
      slots: ['sub', 'block'],
      latex: function (b) {
        return '\\log_{' + b[0].latex() + '}{' + b[1].latex() + '}';
      },
      symja: function (b) {
        return '(ln(' + b[1].symja() + ')/ln(' + b[0].symja() + '))';
      },
    }
  );

  // ---- 最大公约数 / 最小公倍数：括号里两个槽位 ----
  [
    { name: 'gcd', fn: 'gcd' },
    { name: 'lcm', fn: 'lcm' },
  ].forEach(function (spec) {
    defCommand(
      spec.name,
      '\\' + spec.name,
      '<span class="mq-non-leaf"><span class="non-italicized-function">' + spec.name + '</span>' +
        '<span class="mq-scaled mq-paren">(</span>' +
        '<span class="mq-non-leaf"><span class="mq-non-leaf">&0</span>, ' +
        '<span class="mq-non-leaf">&1</span></span>' +
        '<span class="mq-scaled mq-paren">)</span></span>',
      {
        slots: ['(', 'block', ',', 'block', ')'],
        latex: function (b) {
          return '\\' + spec.name + '({' + b[0].latex() + '},{' + b[1].latex() + '})';
        },
        symja: function (b) {
          return spec.fn + '(' + b[0].symja() + ',' + b[1].symja() + ')';
        },
      }
    );
  });

  /**
   * 定积分：∫ 下界 上界 被积函数 d 积分变量。
   *
   * 键盘插入的是 \int_{}^{}{}d{x}，四个槽位。原版的光标顺序是
   * [下界, 上界, 被积函数, 积分变量]（左移三次落到下界）。
   */
  defCommand(
    'int',
    '\\int',
    '<span class="mq-non-leaf"><big>&int;</big>' +
      '<span class="mq-supsub mq-non-leaf mq-limit"><span class="mq-sup">&1</span>' +
      '<span class="mq-sub">&0</span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span>' +
      '<span class="mq-non-leaf">&2</span><var>d</var><span class="mq-non-leaf">x</span></span>',
    {
      // 积分变量原版是写死的 x：latex 里的 d{x} 只是字面量，不是槽位
      slots: ['sub', 'sup', 'block', 'd', '{x}'],
      latex: function (b) {
        return '\\int_{' + b[0].latex() + '}^{' + b[1].latex() + '}{' + b[2].latex() +
          '}d{x}';
      },
      symja: function (b) {
        return 'NIntegrate(' + b[2].symja() + ', {x, ' + b[0].symja() + ', ' +
          b[1].symja() + '})';
      },
    }
  );

  /**
   * 极限：lim 变量 → 趋近值 表达式。
   *
   * 键盘插的是 \lim_{}^{}{}，但显示和 latex 都是 \lim_{□\to□}{□}，
   * 所以第二个槽位（上标位置）在渲染上跑到箭头右边去了。
   */
  defCommand(
    'lim',
    '\\lim',
    '<span class="mq-non-leaf"><span class="mq-large-operator mq-non-leaf"><big>lim</big>' +
      '<span class="mq-from"><span>&0</span><span>&rarr;</span><span>&1</span></span></span>' +
      '<span class="mq-non-leaf">&2</span></span>',
    {
      slots: ['sub', 'sup', 'block'],
      latex: function (b) {
        return '\\lim_{{' + b[0].latex() + '}\\to{' + b[1].latex() + '}}{' +
          b[2].latex() + '}';
      },
      symja: function (b) {
        return 'Limit(' + b[2].symja() + ',' + b[0].symja() + '->' + b[1].symja() + ')';
      },
    }
  );

  /** 度分秒：三个槽位，空着按 0 算（原版实测）。 */
  defCommand(
    'dms',
    '\\dms',
    '<span class="mq-non-leaf"><span>&0</span><span>&deg;</span><span>&1</span>' +
      '<span>&apos;</span><span>&2</span><span>&quot;</span></span>',
    {
      slots: ['block', 'block', 'block'],
      latex: function (b) {
        return '{' + (b[0].latex() || '0') + '}^\\circ{' + (b[1].latex() || '0') +
          '}^\\prime{' + (b[2].latex() || '0') + '}^\\pprime';
      },
      symja: function (b) {
        return 'DegreeMinuteSecond(' + (b[0].symja() || '0') + ',' + (b[1].symja() || '0') +
          ',' + (b[2].symja() || '0') + ')';
      },
    }
  );

  /** 排列 / 组合：A 或 C，上标是总数、下标是取几个。 */
  [
    { name: 'ap', letter: 'A', fn: 'ArrangementPermutations' },
    { name: 'cp', letter: 'C', fn: 'CombinationPermutations' },
  ].forEach(function (spec) {
    defCommand(
      spec.name,
      '\\' + spec.name,
      '<span class="mq-non-leaf"><big>' + spec.letter + '</big>' +
        '<span class="mq-supsub mq-non-leaf mq-limit"><span class="mq-sup">&1</span>' +
        '<span class="mq-sub">&0</span>' +
        '<span style="display:inline-block;width:0">&#8203;</span></span></span>',
      {
        slots: ['block', 'block'],
        latex: function (b) {
          return spec.letter + '_{' + b[0].latex() + '}^{' + b[1].latex() + '}';
        },
        symja: function (b) {
          // 原版实测：引擎里第一个参数是上标（总数），第二个是下标
          return spec.fn + '(' + b[1].symja() + ',' + b[0].symja() + ')';
        },
      }
    );
  });

  /** 换行：一个纯装饰用的空行。原版写死了宽 100px、高 23px。 */
  defCommand(
    'newline',
    '\\newline',
    '<span><span style="width: 100px; height:23px;" class="newlineArea">' +
      '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;' +
      '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</span>' +
      '<div></div><span class="newlineAreaSpan">&nbsp;</span></span>',
    {
      slots: [],
      latex: function () {
        return '\\newline';
      },
      symja: function () {
        return '\\n';
      },
    }
  );

  // ---- 「公式」页的函数模板 ----
  //
  // 这些模板里，空槽位上的 +、- 前缀是 CSS 画上去的（见 editor.css 的
  // .mq-empty.mq-plus-a 之类），所以 latex 里看不到它们；引擎输入也同样
  // 不含这些符号——原版实测 y=x^2x 就是这么来的，别「顺手修正」。

  /** 一次函数 y=kx+b。 */
  defCommand(
    'fLinear',
    '\\fLinear',
    '<span class="mq-non-leaf"><span>y=</span><span class="mq-para-k">&0</span><span>x</span>' +
      '<span class="mq-plus-b">&1</span></span>',
    {
      slots: ['block', 'block'],
      latex: function (b) {
        return 'y=' + b[0].latex() + 'x' + b[1].latex();
      },
      symja: function (b) {
        var k = b[0].symja();
        return 'y==' + (k ? k + '*' : '') + 'x' + b[1].symja();
      },
    }
  );

  /** 反比例函数 y=k/x。 */
  defCommand(
    'fInverse',
    '\\fInverse',
    '<span class="mq-non-leaf"><span>y=</span><span class="mq-fraction mq-non-leaf">' +
      '<span class="mq-numerator">&0</span><span class="mq-denominator">x</span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span></span>',
    {
      slots: ['block'],
      latex: function (b) {
        return 'y=\\frac{' + b[0].latex() + '}{x}';
      },
      symja: function (b) {
        return 'y==((' + b[0].symja() + ')/(x))';
      },
    }
  );

  /** 二次函数一般式 y=ax²+bx+c。 */
  defCommand(
    'fnQuadratic',
    '\\fnQuadratic',
    '<span class="mq-non-leaf"><span>y=</span><span class="mq-para-a">&0</span><span>x</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span class="mq-plus-b">&1</span><span>x</span><span class="mq-plus-c">&2</span></span>',
    {
      slots: ['block', 'block', 'block'],
      latex: function (b) {
        return 'y=' + b[0].latex() + 'x^2' + b[1].latex() + 'x' + b[2].latex();
      },
      symja: function (b) {
        // 系数非空时要补 *：Symja 不认 5x 这种隐式乘法，但空槽位又不能留一个 * 在那儿
        var a = b[0].symja();
        var c = b[1].symja();
        return 'y==' + (a ? a + '*' : '') + 'x^(2)' + (c ? c + '*' : '') + 'x' +
          b[2].symja();
      },
    }
  );

  /** 二次函数顶点式 y=a(x-h)²+k。 */
  defCommand(
    'fQuadratic',
    '\\fQuadratic',
    '<span class="mq-non-leaf"><span>y=</span><span class="mq-para-a">&0</span><span>(x</span>' +
      '<span class="mq-subtract-h">&1</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span class="mq-plus-k">&2</span></span>',
    {
      slots: ['block', 'block', 'block'],
      latex: function (b) {
        return 'y=' + b[0].latex() + '\\left(x' + b[1].latex() + '\\right)^2' + b[2].latex();
      },
      symja: function (b) {
        return 'y==' + b[0].symja() + '(x' + b[1].symja() + ')^(2)' + b[2].symja();
      },
    }
  );

  /** 指数函数 y=a^x。 */
  defCommand(
    'fExponential',
    '\\fExponential',
    '<span class="mq-non-leaf"><span>y=</span><span>&0</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">x</span></span></span>',
    {
      slots: ['block'],
      latex: function (b) {
        return 'y=' + b[0].latex() + '^x';
      },
      symja: function (b) {
        return 'y==' + b[0].symja() + '^(x)';
      },
    }
  );

  /** 对数函数 y=log_a x。 */
  defCommand(
    'fLog',
    '\\fLog',
    '<span class="mq-non-leaf"><span>y=</span><span class="non-italicized-function">log</span>' +
      '<span class="mq-supsub mq-non-leaf"><span class="mq-sub">&0</span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span>' +
      '<span class="mq-non-leaf">x</span></span>',
    {
      slots: ['block'],
      latex: function (b) {
        return 'y=\\log_{' + b[0].latex() + '}{x}';
      },
      symja: function (b) {
        return 'y==(ln(x)/ln(' + b[0].symja() + '))';
      },
    }
  );

  /** 圆的标准方程 (x-a)²+(y-b)²=r²。 */
  defCommand(
    'fsCircle',
    '\\fsCircle',
    '<span class="mq-non-leaf"><span>(x</span><span class="mq-subtract-a">&0</span>' +
      '<span>)</span><span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span>+</span><span>(y</span><span class="mq-subtract-b">&1</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span>=</span><span class="mq-sqrt">&2<span class="mq-sqrt-span">r<sup>2</sup></span></span></span>',
    {
      slots: ['block', 'block', 'block'],
      latex: function (b) {
        return '\\left(x' + b[0].latex() + '\\right)^2+\\left(y' + b[1].latex() +
          '\\right)^2=' + b[2].latex();
      },
      symja: function (b) {
        return '(x' + b[0].symja() + ')^(2)+(y' + b[1].symja() + ')^(2)==' + b[2].symja();
      },
    }
  );

  /** 圆的一般方程 x²+y²+ax+by+c=0。 */
  defCommand(
    'fCircle',
    '\\fCircle',
    '<span class="mq-non-leaf"><span>x</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span>+y</span><span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span class="mq-plus-a">&0</span><span>x</span><span class="mq-plus-b">&1</span>' +
      '<span>y</span><span class="mq-plus-c">&2</span><span>=0</span></span>',
    {
      slots: ['block', 'block', 'block'],
      latex: function (b) {
        return 'x^2+y^2' + b[0].latex() + 'x' + b[1].latex() + 'y' + b[2].latex() + '=0';
      },
      symja: function (b) {
        var a = b[0].symja();
        var bb = b[1].symja();
        return 'x^(2)+y^(2)' + (a ? a + '*' : '') + 'x' + (bb ? bb + '*' : '') + 'y' +
          b[2].symja() + '==0';
      },
    }
  );

  /** 椭圆标准方程 x²/a²+y²/b²=1。 */
  defCommand(
    'fsEllipse',
    '\\fsEllipse',
    '<span class="mq-non-leaf"><span class="mq-fraction mq-non-leaf">' +
      '<span class="mq-numerator"><span>x</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&0<span class="mq-sqrt-span">a<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>+</span>' +
      '<span class="mq-fraction mq-non-leaf"><span class="mq-numerator"><span>y</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&1<span class="mq-sqrt-span">b<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>=1</span></span>',
    {
      slots: ['block', 'block'],
      latex: function (b) {
        return '\\frac{x^2}{' + b[0].latex() + '}+\\frac{y^2}{' + b[1].latex() + '}=1';
      },
      symja: function (b) {
        return '((x^(2))/(' + b[0].symja() + '))+((y^(2))/(' + b[1].symja() + '))==1';
      },
    }
  );

  /** 椭圆（中心不在原点）(x-k)²/a²+(y-h)²/b²=1。 */
  defCommand(
    'fEllipse',
    '\\fEllipse',
    '<span class="mq-non-leaf"><span class="mq-fraction mq-non-leaf"><span class="mq-numerator">' +
      '<span>(x</span><span class="mq-subtract-k">&0</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&1<span class="mq-sqrt-span">a<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>+</span>' +
      '<span class="mq-fraction mq-non-leaf"><span class="mq-numerator"><span>(y</span>' +
      '<span class="mq-subtract-h">&2</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&3<span class="mq-sqrt-span">b<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>=1</span></span>',
    {
      slots: ['block', 'block', 'block', 'block'],
      latex: function (b) {
        return '\\frac{\\left(x' + b[0].latex() + '\\right)^2}{' + b[1].latex() +
          '}+\\frac{\\left(y' + b[2].latex() + '\\right)^2}{' + b[3].latex() + '}=1';
      },
      symja: function (b) {
        return '(((x' + b[0].symja() + ')^(2))/(' + b[1].symja() + '))+' +
          '(((y' + b[2].symja() + ')^(2))/(' + b[3].symja() + '))==1';
      },
    }
  );

  /** 双曲线标准方程 x²/a²-y²/b²=1。 */
  defCommand(
    'fsHyperbola',
    '\\fsHyperbola',
    '<span class="mq-non-leaf"><span class="mq-fraction mq-non-leaf">' +
      '<span class="mq-numerator"><span>x</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&0<span class="mq-sqrt-span">a<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>-</span>' +
      '<span class="mq-fraction mq-non-leaf"><span class="mq-numerator"><span>y</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&1<span class="mq-sqrt-span">b<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>=1</span></span>',
    {
      slots: ['block', 'block'],
      latex: function (b) {
        return '\\frac{x^2}{' + b[0].latex() + '}-\\frac{y^2}{' + b[1].latex() + '}=1';
      },
      symja: function (b) {
        return '((x^(2))/(' + b[0].symja() + '))-((y^(2))/(' + b[1].symja() + '))==1';
      },
    }
  );

  /** 双曲线（中心不在原点）(x-k)²/a²-(y-h)²/b²=1。 */
  defCommand(
    'fHyperbola',
    '\\fHyperbola',
    '<span class="mq-non-leaf"><span class="mq-fraction mq-non-leaf"><span class="mq-numerator">' +
      '<span>(x</span><span class="mq-subtract-k">&0</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&1<span class="mq-sqrt-span">a<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>-</span>' +
      '<span class="mq-fraction mq-non-leaf"><span class="mq-numerator"><span>(y</span>' +
      '<span class="mq-subtract-h">&2</span><span>)</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span></span>' +
      '<span class="mq-denominator"><span class="mq-sqrt">' +
      '&3<span class="mq-sqrt-span">b<sup>2</sup></span></span></span>' +
      '<span style="display:inline-block;width:0">&#8203;</span></span><span>=1</span></span>',
    {
      slots: ['block', 'block', 'block', 'block'],
      latex: function (b) {
        return '\\frac{\\left(x' + b[0].latex() + '\\right)^2}{' + b[1].latex() +
          '}-\\frac{\\left(y' + b[2].latex() + '\\right)^2}{' + b[3].latex() + '}=1';
      },
      symja: function (b) {
        return '(((x' + b[0].symja() + ')^(2))/(' + b[1].symja() + '))-' +
          '(((y' + b[2].symja() + ')^(2))/(' + b[3].symja() + '))==1';
      },
    }
  );

  /** 抛物线 y²=2px。 */
  defCommand(
    'fParabola',
    '\\fParabola',
    '<span class="mq-non-leaf"><span>y</span>' +
      '<span class="mq-supsub mq-non-leaf mq-sup-only"><span class="mq-sup">2</span></span>' +
      '<span>=</span><span>&0</span><span>x</span></span>',
    {
      slots: ['block'],
      latex: function (b) {
        return 'y^2=' + b[0].latex() + 'x';
      },
      symja: function (b) {
        var p = b[0].symja();
        return 'y^(2)==' + (p ? p + '*' : '') + 'x';
      },
    }
  );

  // ---------------------------------------------------------------
  // 2. 建三个域
  // ---------------------------------------------------------------

  /*
   * 历史页也要用这批自定义命令（\degree \dms \lim \int …），但它不需要编辑器本体，
   * 而且页面上根本没有 #formulaSpan 这些节点。页面在加载本文件之前设
   * window.SUPERCALC_MQ_EXT_ONLY = true，这里把命令表交出去就直接返回，
   * 后面的 DOM 代码一概不跑（原来这段判断放在 DOM 代码之后，历史页会先炸再挂不到
   * window.SuperCalcMQ，导致 \degree 之类的命令丢失、公式退化成纯文本）。
   */

  /*
   * 根号图形：整块（斜线 + 顶上横线）用一块内联 SVG 画出来，一次成型的
   * 连续形状，接缝不存在。详见 editor.css 的「根号图形」一节。
   *
   * 数据来源是 STIX Two Math（assets/mathquill/font/STIXTwoMath-Regular.ttf）
   * 里 U+221A 的轮廓 —— 纯多边形，顶点如下（字体单位，1000/em，y 向上）：
   *
   *   (667,922)-(829,922)-(829,854)-(713,854)  ← 顶上那截平顶（厚 68）
   *   -(413,-265)-(371,-265)                   ← 斜线底端的尖
   *   -(138,319)-(34,279)-(18,307)-(177,417)-(197,417)  ← 左下的小起笔
   *   -(395,-93)-(399,-93)-闭合
   *
   * 横线就是把「(667,922)→(829,854) 那截平顶」向右延长：SVG 里加一个
   * 从 x=700、y=854、高 68 的矩形，一直铺到 stem 的右边 —— 和字形平顶
   * 重叠 33 个单位，同一条上边、同一厚度，所以看不出接缝。
   *
   * MathQuill 的 sqrt.reflow 每次都会把 .mq-sqrt-prefix 写成
   * `transform: scale(1, k)`（k = 被开方数高度 / 字号 - 0.1，√2 约 0.93、
   * √(1/2) 约 2.1）。但 STIX 的 √ 顶上带一截「平顶尾巴」，整块按 k 拉伸的话
   * 尾巴会跟着变粗，横线为了贴合也得变粗 —— 被开方数一高就是一根胖横线，
   * 不好看，也不像原版（原版字形没有这截尾巴）。所以拉伸只作用在斜线上：
   * 沿 y 做分段线性映射，平顶下缘以上原样（厚度恒定 68/1000 em）、以下乘 k，
   * 横线就接在这截固定厚度的平顶上。被开方数再高，顶部横线粗细都不变。
   *
   * 这里不碰 vendored 的 mathquill.min.js：只盯前缀上的 style 变化（MathQuill
   * 每次 reflow 都会重写 transform），按前缀 / stem / 外壳的盒位算图形几何，
   * 写进那块 SVG。次数槽（mq-nthroot）被 <sup> 的基线排版带着往下漂，也在
   * 这里用一个 relative 位移钉回根号左上角。MutationObserver 回调是微任务，
   * 跑在下一帧绘制之前，肉眼看不到滞后；另外还有启动扫描和 reflow 兜底。
   *
   * 放在 SuperCalcMQ 之前，历史页（SUPERCALC_MQ_EXT_ONLY）也装得上 ——
   * 历史记录里的公式一样有根号。
   */
  /*
   * 轮廓顶点（字体单位，y 向上），按绘制顺序。斜线以下的部分随 k 纵向拉伸，
   * 「尾巴」（SQRT_TAIL_Y 以上那截平顶）不拉伸 —— 沿 y 做分段线性映射即可：
   * 直线段映射后还是直线段，跨过分界的那两条边拆一个顶点。
   */
  var SQRT_OUTLINE = [
    [667, 922], [829, 922], [829, 854], [713, 854], [413, -265], [371, -265],
    [138, 319], [34, 279], [18, 307], [177, 417], [197, 417], [395, -93],
    [399, -93],
  ];
  var SQRT_UPEM = 1000;
  var SQRT_INK_TOP = 922;      // 字形墨迹顶（字体单位，y 向上）
  var SQRT_INK_BOTTOM = -265;  // 墨迹底
  var SQRT_BAR_L = 700;        // 横线从平顶里接出去的位置
  var SQRT_BAR_B = 854;        // 平顶下缘：横线厚度 = 922-854 = 68/1000 em
  /* 平顶下缘：它以上（那截尾巴）不参与纵向拉伸，厚度恒定。 */
  var SQRT_TAIL_Y = 854;
  /* 墨迹顶在前缀盒顶上方多少 em —— 只决定整块 √ 的落点（配合 CSS 的 0.216em）。 */
  var SQRT_INK_TOP_EM = 0.19;
  /* 掩码盒在斜线尖下面多留一点，免得抗锯齿把尖切平。 */
  var SQRT_MASK_SLACK_PX = 2;

  function sqrtScaleY(prefix) {
    var text = (prefix.style && prefix.style.transform) || '';
    var m = /matrix\(([^)]*)\)/.exec(text);
    if (m) {
      var parts = m[1].split(',');
      return parts.length === 6 ? parseFloat(parts[3]) : 0;
    }
    m = /scale\(([^,]+),\s*([^)]+)\)/.exec(text);
    return m ? parseFloat(m[2]) : 0;
  }

  /** px / 属性值统一留三位小数，避免亚像素抖动带来的无谓改写。 */
  function sqrtPx(value) {
    return String(Math.round(value * 1000) / 1000);
  }

  /**
   * 把字形轮廓按「尾巴不拉伸」的分段线性映射转成 path。
   *
   *   y ≥ SQRT_TAIL_Y（平顶那截）：原样，厚度固定 68/1000 em；
   *   y <  SQRT_TAIL_Y（斜线 + 左下起笔）：以平顶下缘为不动点纵向乘 k。
   *
   * 这样被开方数再高，顶部平顶和横线都不会变粗 —— 只有斜线变长变陡。
   * 返回 { d, bottom }，bottom 是映射后墨迹最低点的 y（字体单位）。
   */
  function sqrtOutlinePath(k) {
    var n = SQRT_OUTLINE.length;
    var pts = [];
    for (var i = 0; i < n; i++) {
      var a = SQRT_OUTLINE[i];
      var b = SQRT_OUTLINE[(i + 1) % n];
      pts.push(sqrtMapPoint(a, k));
      if ((a[1] - SQRT_TAIL_Y) * (b[1] - SQRT_TAIL_Y) < 0) {
        var t = (SQRT_TAIL_Y - a[1]) / (b[1] - a[1]);
        pts.push([a[0] + (b[0] - a[0]) * t, SQRT_TAIL_Y]);
      }
    }
    var d = '';
    for (var j = 0; j < pts.length; j++) {
      d += (j ? 'L' : 'M') + Math.round(pts[j][0]) + ' ' + Math.round(pts[j][1]);
    }
    return d + 'Z';
  }

  function sqrtMapPoint(p, k) {
    var y = p[1];
    if (y < SQRT_TAIL_Y) y = SQRT_TAIL_Y - (SQRT_TAIL_Y - y) * k;
    return [p[0], y];
  }

  /** 映射后墨迹最低点的 y（字体单位）。 */
  function sqrtOutlineBottom(k) {
    return SQRT_TAIL_Y - (SQRT_TAIL_Y - SQRT_INK_BOTTOM) * k;
  }

  var SQRT_SVG_NS = 'http://www.w3.org/2000/svg';

  /**
   * 根号图形本体：一块挂在「前缀 + stem 的外壳」上的内联 SVG。
   *
   * 走过两条弯路，记在这里免得再踩：
   *   1. 字体字形整块拉伸 —— STIX 的平顶尾巴跟着变粗，横线只好跟着粗；
   *   2. 把轮廓画成 data URI 的 SVG 掩码（贴在前缀 / stem 的伪元素上）——
   *      Chrome/WebView 对「当 CSS 掩码用的 SVG」会按图片固有尺寸那一档去
   *      光栅化，根号一高就只按 1× 出图再拉到 dpr 倍：480dpi 实机上应 5.75
   *      设备像素的横线，k≈7.7 时只剩 1.7 像素（同页 k≈4 的却正常，纯属
   *      光栅化启发式，跟几何无关）。换成 canvas 出的 PNG 掩码，同一个位置
   *      照样偶发被压细 —— 只要走 CSS 掩码就可能踩到。
   *
   * 内联 SVG 是页面里的普通矢量元素，由渲染器按设备像素直接绘制，不经过
   * 「图片光栅化」这条链，任何尺寸都稳定。它挂在壳元素上（不是可编辑的 stem
   * 里），absolute 定位、不参与排版，MathQuill 的编辑逻辑碰不到它。
   */
  function sqrtInkSvg(prefix) {
    var host = prefix.parentElement;
    if (!host) return null;
    var svg = host.__mqSqrtInk;
    if (svg && svg.parentNode === host && svg.firstChild) return svg;
    svg = document.createElementNS(SQRT_SVG_NS, 'svg');
    svg.setAttribute('class', 'mq-sqrt-ink');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('preserveAspectRatio', 'none');
    /* 轮廓顶点是字体单位（y 向上），这层 <g> 负责换算到 px（y 向下），
     * 和 viewBox 的 px 坐标对齐 —— transform 每次 sync 时重写。 */
    var g = document.createElementNS(SQRT_SVG_NS, 'g');
    var path = document.createElementNS(SQRT_SVG_NS, 'path');
    path.setAttribute('fill', 'currentColor');
    var rect = document.createElementNS(SQRT_SVG_NS, 'rect');
    rect.setAttribute('fill', 'currentColor');
    g.appendChild(path);
    g.appendChild(rect);
    svg.appendChild(g);
    host.appendChild(svg);
    if (window.getComputedStyle(host).position === 'static') {
      host.style.position = 'relative';
    }
    host.__mqSqrtInk = svg;
    return svg;
  }

  /**
   * 次数（根指数）钉回根号左上角。
   *
   * MathQuill 把次数放在 `<sup class="mq-nthroot">` 里按基线排版
   * （vertical-align: .8em）。被开方数一高，stem 的基线跟着往下跑，次数就漂到
   * 根号中腰去了（原版也这样）。这里不动它的排版位置（宽度、前缀落点都不变），
   * 只加一个 position: relative 的纵向位移：次数中心对准横线顶边 —— 和 k≈1 的
   * 观感一致（实机量过：√2 的次数中心就落在横线顶边上）。
   */
  function syncSqrtIndex(prefix, barTop) {
    var scaled = prefix.parentElement;
    var nth = scaled && scaled.previousElementSibling;
    if (!nth || String(nth.className || '').indexOf('mq-nthroot') < 0) return;
    var rect = nth.getBoundingClientRect();
    /*
     * 上下文键：我们的写入本身也会让 MutationObserver 再叫一次，而 getBoundingClientRect
     * 有 1/64px 级取整误差 —— 每次都重算会来回震。几何（横线位置 + 次数盒尺寸）没变
     * 就直接跳过，循环自然断掉；内容/拉伸变了键就变，照常重钉。
     */
    var key = sqrtPx(barTop) + '|' + sqrtPx(rect.width) + 'x' + sqrtPx(rect.height);
    if (nth.__mqIdxKey === key) return;
    var applied = parseFloat(nth.__mqIdxTop || '0') || 0;
    var center = rect.top - applied + rect.height / 2;
    var want = sqrtPx(barTop - center);
    nth.__mqIdxKey = key;
    nth.__mqIdxTop = want;
    nth.style.position = 'relative';
    nth.style.top = want + 'px';
  }

  function syncSqrtMask(prefix) {
    var stem = prefix.nextElementSibling;
    if (!stem || String(stem.className).indexOf('mq-sqrt-stem') < 0) return;
    var k = sqrtScaleY(prefix);
    if (!k || !isFinite(k)) k = 1;
    var fontPx = parseFloat(window.getComputedStyle(prefix).fontSize) || 0;
    if (fontPx <= 0) return;
    var prefixRect = prefix.getBoundingClientRect();
    var stemRect = stem.getBoundingClientRect();

    /* 纵向拉伸已经烘进轮廓坐标，掩码本身是等比缩放：1 字体单位 = fontPx/1000 px。 */
    var scale = fontPx / SQRT_UPEM;
    var barTop = prefixRect.top - SQRT_INK_TOP_EM * k * fontPx;
    var width = stemRect.right - prefixRect.left;
    var height = (SQRT_INK_TOP - sqrtOutlineBottom(k)) * scale + SQRT_MASK_SLACK_PX;
    /* 掩码右边最多画到 stem 的右缘：横线矩形宽度按字体单位折算。 */
    var barUnits = width / scale - SQRT_BAR_L;
    if (barUnits < 0) barUnits = 0;

    var svg = sqrtInkSvg(prefix);
    if (!svg) return;
    var hostRect = svg.parentNode.getBoundingClientRect();
    var viewBox = '0 0 ' + sqrtPx(width) + ' ' + sqrtPx(height);
    if (svg.getAttribute('viewBox') !== viewBox) {
      svg.setAttribute('viewBox', viewBox);
      svg.setAttribute('width', sqrtPx(width));
      svg.setAttribute('height', sqrtPx(height));
    }
    var g = svg.firstChild;
    var path = g.firstChild;
    var rect = g.lastChild;
    var transform = 'translate(0 ' + sqrtPx(SQRT_INK_TOP * scale) + ') scale(' +
      scale + ' ' + (-scale) + ')';
    if (g.getAttribute('transform') !== transform) {
      g.setAttribute('transform', transform);
    }
    var d = sqrtOutlinePath(k);
    if (path.getAttribute('d') !== d) path.setAttribute('d', d);
    var rectX = String(SQRT_BAR_L);
    var rectY = String(SQRT_BAR_B);
    var rectW = sqrtPx(barUnits);
    var rectH = String(SQRT_INK_TOP - SQRT_BAR_B);
    if (rect.getAttribute('x') !== rectX) rect.setAttribute('x', rectX);
    if (rect.getAttribute('y') !== rectY) rect.setAttribute('y', rectY);
    if (rect.getAttribute('width') !== rectW) rect.setAttribute('width', rectW);
    if (rect.getAttribute('height') !== rectH) rect.setAttribute('height', rectH);
    var left = sqrtPx(prefixRect.left - hostRect.left) + 'px';
    if (svg.style.left !== left) svg.style.left = left;
    var top = sqrtPx(barTop - hostRect.top) + 'px';
    if (svg.style.top !== top) svg.style.top = top;
    /* 字形本体藏起来，位置由掩码顶上 —— 两套光栅化叠着画会出重影。 */
    if (String(prefix.style.color) !== 'transparent') {
      prefix.style.color = 'transparent';
    }
    syncSqrtIndex(prefix, barTop);
  }

  /** 扫一遍某个子树（含自己）里的根号前缀。 */
  function sweepSqrtScales(root) {
    if (root.nodeType === 1 && String(root.className).indexOf('mq-sqrt-prefix') >= 0) {
      syncSqrtMask(root);
    }
    var list = root.querySelectorAll ? root.querySelectorAll('.mq-sqrt-prefix') : [];
    for (var i = 0; i < list.length; i++) syncSqrtMask(list[i]);
  }

  /** 从某个被改动的节点出发，找它所在根号的 .mq-sqrt-prefix（嵌套根号取最近那层）。 */
  function sqrtPrefixOf(node) {
    var el = node.nodeType === 1 ? node : node.parentElement;
    for (; el; el = el.parentElement) {
      if (el.querySelector) {
        var prefix = el.querySelector('.mq-sqrt-prefix');
        if (prefix) return prefix;
      }
    }
    return null;
  }

  if (window.MutationObserver) {
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var rec = records[i];
        if (rec.target.nodeType === 1 &&
            String(rec.target.className).indexOf('mq-sqrt-prefix') >= 0) {
          syncSqrtMask(rec.target);
        } else {
          /*
           * 次数槽 / 被开方数自己的改动（输入一个次数、改个数字）不会碰前缀的
           * transform，但盒位会变：次数槽要跟着重钉，掩码宽度也要跟着重算。
           */
          var tid = rec.target.nodeType === 1 ? rec.target : rec.target.parentElement;
          if (tid && tid.closest && tid.closest('.mq-nthroot, .mq-sqrt-stem')) {
            var owner = sqrtPrefixOf(tid);
            if (owner) syncSqrtMask(owner);
          }
        }
        for (var j = 0; rec.addedNodes && j < rec.addedNodes.length; j++) {
          var node = rec.addedNodes[j];
          if (node.nodeType === 1) sweepSqrtScales(node);
        }
      }
    }).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['style'],
      childList: true,
      characterData: true,
      subtree: true,
    });
  }

  window.SuperCalcMQ = {
    MathQuill: MathQuill,
    MQ: MQ,
    LatexCmds: LatexCmds,
    CharCmds: CharCmds,
    defCommand: defCommand,
    defSymbol: defSymbol,
  };
  if (window.SUPERCALC_MQ_EXT_ONLY) return;

  var formulaField = MQ.MathField(document.getElementById('formulaSpan'), {
    spaceBehavesLikeTab: false,
    handlers: {
      edit: function () {
        onEdited();
      },
    },
  });

  var resultField = MQ.StaticMath(document.getElementById('resultSpan'));
  var numericField = MQ.StaticMath(document.getElementById('numericResultSpan'));

  // 空公式时底下那行示例。算式用静态域渲染，和上面结果行是同一套排版；
  // 中文小标题是普通文字，所以两者共用一条基线。
  var exampleTipBox = document.getElementById('exampleTip');
  var exampleTipLabel = document.getElementById('exampleTipLabel');
  var exampleTipInner = document.getElementById('exampleTipInner');
  var exampleTipMath = document.getElementById('exampleTipMath');
  var exampleTipField = exampleTipBox ? MQ.StaticMath(exampleTipMath) : null;

  /** 示例行的字号：和 native 那行 TextView 一样是 13px，放不下再整体缩。 */
  var EXAMPLE_TIP_MAX_PX = 13;
  var EXAMPLE_TIP_MIN_PX = 8;
  var EXAMPLE_TIP_STEP_PX = 0.5;
  /** 算式最高能顶多高（CSS px）：再高就要碰到键盘了，宁可缩字号。 */
  var EXAMPLE_TIP_MAX_HEIGHT_PX = 46;
  /**
   * 两行的那条（求解方程组）另算：原版这张位图 73px 高、行盒只有 25dp，
   * CENTER_INSIDE 把它整张压到 25dp 才画得下（屏上实测 638×75 设备像素，
   * 连中文标题一起缩小）。这里照着压：超过 30px 就缩字号，13px 一路缩到
   * 10.5px，整条 215×30 dp —— 位图版是 213×25，宽度几乎重合。
   */
  var EXAMPLE_TIP_TWOLINE_MAX_HEIGHT_PX = 30;
  /** 当前这条示例是不是两行的（\newline 组成）。 */
  var exampleTipTwoLine = false;
  /** 示例行当前显示着没有。 */
  var exampleTipVisible = false;

  /**
   * 示例行的 class 由两个状态拼出来。
   *
   * 以前是 setExampleTipVisible 里直接赋值 className，加了两行这条之后
   * 两个状态得各管各的，不然显示/隐藏一次就把 twoline 抹掉了。
   */
  function applyExampleTipClasses() {
    if (!exampleTipBox) return;
    var cls = 'exampleTip';
    if (exampleTipVisible) cls += ' on';
    if (exampleTipTwoLine) cls += ' twoline';
    exampleTipBox.className = cls;
  }

  /**
   * 放不下就整体缩字号。
   *
   * 参考实现这一行是位图，按可用宽度整体缩放 —— 最长那条「求解方程组」在原版里
   * 明显比别的条目小一档。我们照同样的做法：先按 13px 量，超宽（或者高到要压到
   * 键盘上）就按 0.5px 往下缩。两行那条再压低一档上限（见上面
   * EXAMPLE_TIP_TWOLINE_MAX_HEIGHT_PX），最终落在 10px 左右。
   */
  function fitExampleTip() {
    if (!exampleTipBox || exampleTipBox.className.indexOf('on') < 0) return;
    var avail = exampleTipBox.clientWidth;
    if (avail <= 0) return;
    var size = EXAMPLE_TIP_MAX_PX;
    exampleTipBox.style.fontSize = size + 'px';
    exampleTipField.reflow();
    var maxHeight = exampleTipTwoLine
      ? EXAMPLE_TIP_TWOLINE_MAX_HEIGHT_PX
      : EXAMPLE_TIP_MAX_HEIGHT_PX;
    var tooWide = function () { return exampleTipInner.offsetWidth > avail; };
    var tooTall = function () {
      if (!exampleTipTwoLine) return exampleTipInner.offsetHeight > maxHeight;
      // 算式盒子被 CSS 压成 1em 高（免得撑起行盒），两行的真实高度只能问根块 ——
      // 两行那条就是靠这个数从 13px 缩到 10px 的。
      var root = exampleTipMath.querySelector('.mq-root-block');
      return (root ? root.offsetHeight : 0) > maxHeight;
    };
    while (size > EXAMPLE_TIP_MIN_PX && (tooWide() || tooTall())) {
      size -= EXAMPLE_TIP_STEP_PX;
      exampleTipBox.style.fontSize = size + 'px';
      exampleTipField.reflow();
    }
    // 字号变了，掩码的 px 几何也要跟着重算（reflow 写的是同一串 transform 时
    // MutationObserver 未必有回调，这里显式扫一遍）。
    sweepSqrtScales(exampleTipMath);
    alignExampleTip();
  }

  /**
   * 把这一行摆正：标题和算式各自回到行盒中线上。
   *
   * MathQuill 的分数、积分、根号比一行高，而且是**按基线**排的：内容一高，行盒
   * 就被撑高，整行（含左边那串中文标题）跟着往下掉 —— 化简、积分两条就是这么偏的
   * （实测 5px）。位图那版是整张图在行里居中，所以这里也按居中收尾：渲染完量一次
   * 实际位置，用 transform 把标题和算式的墨迹各自平移回中线。
   *
   * 用 transform 而不是改 margin：它不参与布局，宽度测量（要不要缩字号）不受影响，
   * 也不会因为行盒已经被撑高而互相牵扯。
   */
  function alignExampleTip() {
    if (!exampleTipBox || !exampleTipLabel || !exampleTipMath) return;
    exampleTipLabel.style.transform = '';
    exampleTipMath.style.transform = '';
    var box = exampleTipBox.getBoundingClientRect();
    if (!box.height) return;
    var target = box.top + box.height / 2;
    var labelRect = exampleTipLabel.getBoundingClientRect();
    exampleTipLabel.style.transform =
      'translateY(' + (target - (labelRect.top + labelRect.height / 2)) + 'px)';
    var root = exampleTipMath.querySelector('.mq-root-block');
    if (!root) return;
    var rootRect = root.getBoundingClientRect();
    if (!rootRect.height) return;
    exampleTipMath.style.transform =
      'translateY(' + (target - (rootRect.top + rootRect.height / 2)) + 'px)';
  }

  var resultDiv = document.getElementById('resultDiv');
  var numericResultDiv = document.getElementById('numericResultDiv');
  var statusDiv = document.getElementById('statusDiv');
  var opIcon = document.getElementById('resultOpIcon');

  // 点结果行 = 把这个结果变成新公式（原版的 setResultAsFormula）
  document.getElementById('resultSpan').addEventListener('click', function () {
    useResultAsFormula(resultField);
  });
  document.getElementById('numericResultSpan').addEventListener('click', function () {
    useResultAsFormula(numericField);
  });

  // 点方块按钮 = 把数值结果放进键盘上的剪贴板槽（原版的 copyNumericResult）
  if (opIcon) {
    opIcon.addEventListener('click', function () {
      // 原版的 copyResult 是这么写的：
      //   displayAllBtn('none') + copyNumericResult(trimLatexEqImply(symja),
      //                                            trimLatexEqImply(latex))
      // 两个 trim 一个都不能省：数值结果带着前导 `=`，symja 里 `=` 又写作 `==`，
      // 不 trim 的话键盘槽会显示 `==5`、点回公式里会多一个 `=`。
      opIcon.style.display = 'none';
      var bridge = window.Android;
      if (bridge && bridge.copyNumericResult) {
        bridge.copyNumericResult(
          trimLatexEqImply(symjaOf(numericField)),
          trimLatexEqImply(numericField.latex())
        );
      }
    });
  }

  // ---------------------------------------------------------------
  // 3. 撤销 / 重做
  // ---------------------------------------------------------------

  var undoStack = [];
  var redoStack = [];
  var current = '';
  var restoring = false;
  var MAX_HISTORY = 100;

  function pushHistory(previous) {
    if (previous === current) return;
    undoStack.push(previous);
    if (undoStack.length > MAX_HISTORY) undoStack.shift();
    redoStack.length = 0;
    current = previous;
    notifyHistory();
  }

  function notifyHistory() {
    var bridge = window.Android;
    if (bridge && bridge.onHistoryChanged) {
      bridge.onHistoryChanged(undoStack.length > 0, redoStack.length > 0);
    }
  }

  /**
   * 原版 `reFormatFormula` 里的那一步：把 `^\circ` 这类写法换回 `\degree`。
   *
   * 度数/角分/角秒三个符号内部就是 `^\circ`、`^\prime`、`^\pprime`（和原版一致，
   * 存下来的 latex 也是这个），但这段文本**不能再喂回 MathQuill 解析**——
   * `^` 会被当成上标，公式里就多出一个空槽位和那个尖号。所以每次把 latex
   * 交还给编辑器之前先归一化，和原版的重排公式是同一个动作。
   */
  var TEX_ALIASES = {
    '^\\circ': '\\degree',
    '^\\prime': '\\minute',
    '^\\pprime': '\\second',
    '^{\\prime\\prime}': '\\second',
  };
  var TEX_ALIAS_PATTERN = /\^(\\(?:circ|prime|pprime)|\{\\prime\\prime\})/g;

  function normalizeLatex(latex) {
    if (!latex) return latex;
    return latex.replace(TEX_ALIAS_PATTERN, function (match) {
      return TEX_ALIASES[match] || match;
    });
  }

  /**
   * 引擎结果里的希腊符号名换回字形：`theta` → `\theta`。
   *
   * 键盘第 3 页的 θ φ λ μ σ ω 送进引擎时用的是 Symja 符号名（theta…），
   * 结果串里也会以这个名字回来。直接交给 MathQuill 会渲染成一串斜体字母，
   * 所以在渲染结果前换回 LaTeX 命令。只替换独立的标识符，避免误伤
   * `thetabc` 这类用户自己起的连写名字。
   */
  var GREEK_LATEX = {
    theta: '\\theta', phi: '\\phi', lambda: '\\lambda',
    mu: '\\mu', sigma: '\\sigma', omega: '\\omega',
  };
  var GREEK_NAME_PATTERN = /\b(theta|phi|lambda|mu|sigma|omega)\b/g;

  function greekToLatex(text) {
    if (!text) return text;
    return String(text).replace(GREEK_NAME_PATTERN, function (name) {
      return GREEK_LATEX[name] || name;
    });
  }

  function setLatexInternal(latex) {
    restoring = true;
    formulaField.latex(normalizeLatex(latex) || '');
    // latex(...) 只是换掉内容，光标会脱位；不把光标放回末尾的话，
    // 接下来 write() 会静默写不进去。这个坑很隐蔽。
    formulaField.moveToRightEnd();
    restoring = false;
    current = formulaField.latex();
    notifyEmpty(current);
    fitBracket(current);
  }

  // ---------------------------------------------------------------
  // 4. 输入 -> 结果
  // ---------------------------------------------------------------

  var timer = null;
  var lastSent = null;

  function onEdited() {
    if (restoring) return;
    var latex = formulaField.latex();
    pushHistory(latex);
    notifyEmpty(latex);
    fitBracket(latex);
    if (timer) window.clearTimeout(timer);
    timer = window.setTimeout(function () {
      compute(latex);
    }, DEBOUNCE_MS);
  }

  /**
   * 多行公式左边那个花括号。
   *
   * 原版 `Matharea.fitBracket(latex)`：数 latex 里 `newline` 出现的次数，
   * 一行时藏起来，2~7 行用矮的那张、8 行以上换高的那张，图片被拉到容器高度。
   * 两张图是我们按原版位图的轮廓重画的 SVG。
   */
  function fitBracket(latex) {
    var div = document.getElementById('bracketDiv');
    var img = document.getElementById('bracketImg');
    var container = document.getElementById('leftContainer');
    if (!div || !img || !container) return;

    var matches = String(latex || '').match(/newline/gi);
    var lines = matches ? matches.length + 1 : 1;
    if (lines <= 1) {
      div.style.display = 'none';
      return;
    }
    img.src = (lines <= 7 ? 'bracket-2-7.svg' : 'bracket-8.svg');
    div.style.height = container.offsetHeight + 'px';
    div.style.display = '';
  }

  /**
   * 公式空没空告诉 Android 一声：空的时候要显示「全部举例」那行示例。
   * 状态没变就不发，免得每敲一个键都过一次桥。
   */
  var lastEmpty = null;
  function notifyEmpty(latex) {
    var empty = !latex;
    if (empty === lastEmpty) return;
    lastEmpty = empty;
    var bridge = window.Android;
    if (bridge && bridge.onFormulaEmpty) bridge.onFormulaEmpty(empty);
  }

  /**
   * 把当前公式交给 Android 换成结果字符串。
   *
   * 传的是 symja 而不是 latex：引擎只认 symja，而 symja 是命令层按原版规则
   * 算出来的（原版前端也是这么做的：setFormulaAndroid(symja, latex)）。
   */
  function compute(latex) {
    if (latex === lastSent) return;
    lastSent = latex;

    var bridge = window.Android;
    if (!bridge || !bridge.autoResult) {
      renderResult('');
      return;
    }

    var symja = symjaOf();
    var raw = '';
    try {
      raw = bridge.autoResult(symja, latex) || '';
    } catch (e) {
      if (bridge.log) bridge.log('autoResult 失败: ' + e.message);
      raw = '';
    }
    renderResult(raw);
  }

  /** 某个域（默认是公式域）的引擎输入。 */
  function symjaOf(field) {
    try {
      return (field || formulaField).__controller.root.symja();
    } catch (e) {
      return '';
    }
  }

  /**
   * 结果串的格式是「精确结果 $$ 数值结果」。
   *
   * 数值结果后面那个方块按钮不是永远显示的：原版只在数值结果「像个结果」
   * （不含 true / false / 出错标记）时才挂出来，否则点了也没东西可放。
   */
  function renderResult(raw) {
    var parts = raw ? String(raw).split('$$') : [];
    var exact = parts[0] || '';
    var numeric = parts[1] || '';

    resultDiv.style.display = exact ? '' : 'none';
    numericResultDiv.style.display = numeric ? '' : 'none';
    if (opIcon) opIcon.style.display = isValid(numeric) ? '' : 'none';
    resultField.latex(greekToLatex(exact));
    numericField.latex(greekToLatex(numeric));
  }

  /** 原版 trimLatexEqImply：去掉首尾空白和开头的 `=` / `\Rightarrow`。 */
  function trimLatexEqImply(text) {
    return String(text || '')
      .replace(/^[\s\uFEFF\xA0]+|[\s\uFEFF\xA0]+$/g, '')
      .replace(/^=+|\\?Rightarrow/, '');
  }

  /** 原版 isValid：结果里出现 true / false / 报错标记就不算「能用的结果」。 */
  function isValid(text) {
    if (!text) return false;
    var t = String(text).toLowerCase();
    return t.indexOf('true') === -1 && t.indexOf('false') === -1;
  }

  /** 点结果行：把它当成新公式（原版的 setResultAsFormula）。 */
  function useResultAsFormula(field) {
    var latex = trimLatexEqImply(field.latex());
    if (!latex) return;
    pushHistory(formulaField.latex());
    setLatexInternal(latex);
    lastSent = null;
    compute(current);
    var bridge = window.Android;
    if (bridge && bridge.onSetResult) bridge.onSetResult(latex);
    formulaField.focus();
  }

  // ---------------------------------------------------------------
  // 4b. 三角函数里敲数字自动补度数（原版 filterCommand）
  // ---------------------------------------------------------------

  /**
   * 原版每次按键前会先过一道 `Matharea.filterCommand()`：返回 true 表示这一下
   * 已经处理完了，不要再走默认插入。这段逻辑在原版里**不在 MathQuill 里**
   * （在 bundle.min.js 的 React 层），所以得自己接回来。
   *
   * 原版判定用的是那支改版 MathQuill 才有的 `preCtrl() / nextCtrl() /
   * currentCtrl()`。官方 MathQuill 没有这三个口子，这里按同一语义直接读
   * `__controller.cursor`：`cursor[-1]` 是左邻节点、`cursor[1]` 是右邻节点，
   * `cursor.parent.parent` 是光标所在的最小外层命令（原版 currentCtrl()
   * 取的就是它的 ctrlSeq）。
   */
  var DEGREE_CTRL = '^\\circ';

  function ctrlOf(node) {
    if (!node || !node.ctrlSeq) return '';
    // 原版的 ° 是 `^\circ `（带尾空格），我们这版没有；比较前统一去掉。
    return String(node.ctrlSeq).replace(/\s+$/, '');
  }

  function editorCursor() {
    var controller = formulaField.__controller;
    return controller && controller.cursor ? controller.cursor : null;
  }

  function preCtrl() {
    var cursor = editorCursor();
    return cursor ? ctrlOf(cursor[-1]) : '';
  }

  function postCtrl() {
    var cursor = editorCursor();
    return cursor ? ctrlOf(cursor[1]) : '';
  }

  function currentCtrl() {
    var cursor = editorCursor();
    if (!cursor || !cursor.parent || !cursor.parent.parent) return '';
    return ctrlOf(cursor.parent.parent);
  }

  function isDigitCtrl(ctrl) {
    return /^[0-9]$/.test(ctrl);
  }

  /** 光标是不是直接待在 sin/cos/tan 的槽位里（不含更深一层的分式之类）。 */
  function isTrigonometric() {
    var ctrl = currentCtrl();
    // 我们的函数命令 ctrlSeq 带反斜杠（原版那支 MathQuill 的 ctrlSeq 是裸名字）
    return ctrl === '\\sin' || ctrl === '\\cos' || ctrl === '\\tan';
  }

  function isDegreeCtrl(ctrl) {
    return ctrl === DEGREE_CTRL;
  }

  /** 删掉光标右边那个 °：先右移一格再退格。 */
  function deleteDegreeOnRight() {
    formulaField.keystroke('Right');
    formulaField.keystroke('Backspace');
  }

  /**
   * 原版 `findAngleContent()`：把光标两边连着的数字串从公式里取出来，
   * 顺手删掉紧跟其后的 °，返回那串数字。°′″ 键用它。
   */
  function takeAngleContent() {
    var cursor = editorCursor();
    if (!cursor) return '';
    var digits = [];
    // 左边紧挨着 ° 时先跨过去，它等下会被右边的规则删掉
    if (cursor[-1] && /circ/.test(String(cursor[-1].ctrlSeq || ''))) {
      formulaField.keystroke('Left');
    }
    while (cursor[-1] && isDigitCtrl(ctrlOf(cursor[-1]))) {
      digits.unshift(ctrlOf(cursor[-1]));
      formulaField.keystroke('Backspace');
    }
    while (cursor[1] && isDigitCtrl(ctrlOf(cursor[1]))) {
      digits.push(ctrlOf(cursor[1]));
      formulaField.keystroke('Right');
      formulaField.keystroke('Backspace');
    }
    if (cursor[1] && isDegreeCtrl(ctrlOf(cursor[1]))) deleteDegreeOnRight();
    return digits.join('');
  }

  /**
   * 原版退格规则：光标左边已经空了、右边还挂着一个 °，而且外层是 sin/cos/tan 时，
   * 先把那个 ° 删掉，再走正常退格。
   *
   * 不这么做的话，sin(5°) 连按退格会把 5 删掉、剩下一个删不掉的 °
   * （光标停在 ° 左边，退格删不到它）。
   */
  function filterBackspace() {
    var cursor = editorCursor();
    if (!cursor || cursor[-1]) return;
    if (!isDegreeCtrl(ctrlOf(cursor[1]))) return;
    var ctrl = ctrlOf(cursor[1].parent && cursor[1].parent.parent);
    if (ctrl !== '\\sin' && ctrl !== '\\cos' && ctrl !== '\\tan') return;
    deleteDegreeOnRight();
  }

  /**
   * 光标所在的最小外层是不是 MathQuill 原生的上下标命令（^ / _）。
   *
   * 是就返回那个命令（SupSub 实例），否则 null。\int、\log 这些带槽命令的槽
   * 不算：它们的槽位块不是 SupSub 实例，原版那支补丁也管不到它们。
   */
  function supSubAtCursor() {
    var cursor = editorCursor();
    if (!cursor || !cursor.parent || !cursor.parent.parent) return null;
    var cmd = cursor.parent.parent;
    if (!(cmd instanceof SupSub)) return null;
    if (cursor.parent !== cmd.sup && cursor.parent !== cmd.sub) return null;
    return cmd;
  }

  /** 把光标从上下标里挪到整项右边（跳出上标）。挪了返回 true。 */
  function breakOutOfSupSub() {
    var cmd = supSubAtCursor();
    var cursor = editorCursor();
    // 有选区时不跳：这一刻的输入是用来替换选区的
    if (!cmd || !cursor || cursor.selection) return false;
    cursor.insRightOf(cmd);
    return true;
  }

  /**
   * 原版 MathQuill 补丁的判定：光标在上下标槽里、左边有内容、右边没内容。
   * 也就是「正要往上下标末尾追加」的那一刻，+ − = < > 会先跳出去。
   */
  function appendingToSupSub() {
    var cursor = editorCursor();
    return !!(supSubAtCursor() && cursor && cursor[-1] && !cursor[1] && !cursor.selection);
  }

  /**
   * 用户点名的规则（优于原版）：空上标里的 × ÷ 跳出上标插到顶层。
   * 「空」看的是光标左边有没有内容 —— x^| 这种空槽才跳，x^2| 不跳。
   */
  function emptySupSubAtCursor() {
    var cursor = editorCursor();
    return !!(supSubAtCursor() && cursor && !cursor[-1] && !cursor.selection);
  }

  /**
   * 这个命令是不是括号 / 绝对值组（\left(…\right)、\left|…\right|）。
   *
   * 括号里的比较号是合法写法（`(a<b)`），所以「内层槽位里的关系符号挪到
   * 最外层」这条规则到括号组这一层就停 —— 光标已经在括号组里时不再往外跳。
   */
  function isParenGroup(cmd) {
    return ctrlOf(cmd).indexOf('\\left') === 0;
  }

  /**
   * 把光标从任意内层槽位挪到最外层（顶层项的右边），挪了返回 true。
   *
   * 用户点名的规则（优于原版）：分子分母、根号次数与被开方数、积分 / 求和 /
   * 极限的上下限、排列组合的两个槽这些「内层」里，= < > ≤ ≥ 都是无效内容
   * ——正常写法不会在分式、根号、上下限里写等号比较号，所以遇到这五个符号
   * 一律先跳到最外层再插，落点在「包着光标的那个顶层项」右边。
   *
   * 「最外层」的边界是括号组：`(a<b)` 里的比较号是合法写法，光标已经在
   * 括号组里时不再往外跳；但括号组里更深的槽（`(\frac{1}{2}|`）还是会跳
   * 到括号组这一层，符号不会留在分式里。光标本来就在最外层时不跳。
   */
  function breakOutToTopLevel() {
    var cursor = editorCursor();
    if (!cursor || !cursor.parent || cursor.selection) return false;
    var block = cursor.parent;
    var top = null;
    while (block && block.parent) {
      if (isParenGroup(block.parent)) break;
      top = block.parent;
      block = top.parent;
    }
    if (!top) return false;
    cursor.insRightOf(top);
    return true;
  }

  /**
   * 按键过滤器。返回 true = 这一下已经被吃掉，不要再走默认插入。
   *
   * [symbol] 是参考实现里的按键标识（KeyItem.symbol），[code] 是插入内容。
   * 逐条对照原版 `Matharea.filterCommand` 的分支，只少了一条与本轮无关的
   * （°′″ 内部只允许数字的闸门）。
   */
  function filterCommand(symbol, code) {
    switch (symbol) {
      // 数字：在 sin/cos/tan 的槽位里自动补 °。光标停在数字和 ° 中间，
      // 所以接着敲的数字会补进 ° 前面（123 → 123°，不是 1°23）。
      case '0': case '1': case '2': case '3': case '4':
      case '5': case '6': case '7': case '8': case '9':
        if (isTrigonometric()) {
          var post = postCtrl();
          if (!isDegreeCtrl(post) && !isDigitCtrl(post)) {
            formulaField.write(code + '\\degree');
            formulaField.keystroke('Left');
            return true;
          }
        }
        return false;

      // 加减号排在度数后面：sin(5°+2)。
      // 上标里：正往末尾追加（x^2|）时先跳出去，+ / − 加在整项后面；
      // 空上标（x^|）留在上标里 —— 这里的 + / − 是正负号，不是运算符。
      case '+':
      case '-':
        if (appendingToSupSub() && breakOutOfSupSub()) return false;
        if (isDegreeCtrl(postCtrl())) formulaField.keystroke('Right');
        return false;

      // 乘除号：sin(5×2) 不是角度，先把 ° 摘掉。
      // 上标里：空槽（x^|）里的 × ÷ 不是乘方内容，跳出去插到顶层；
      // 左边有内容（x^2|）时留在上标里（原版 × ÷ 走 LaTeX 写入，
      // 压根不经过跳出补丁，这里照原版保留）。
      case '*':
      case '/':
        if (emptySupSubAtCursor() && breakOutOfSupSub()) return false;
        if (isTrigonometric() && isDegreeCtrl(postCtrl())) deleteDegreeOnRight();
        return false;

      /*
       * 关系符号 = < > ≥ ≤：只要光标在「内层槽位」里（分子分母、根号次数 /
       * 被开方数、积分与求和的上下限、排列组合槽、其它命令的槽），就跳到
       * 最外层再插（用户要求，优于原版）——这些槽里写不下关系符号。
       *
       * 原版只有上标里的一部分情况会跳（改版 MathQuill 的
       * `charsThatBreakOutOfSupSub: "+-=<>"` 补丁），≥ ≤ 还是「先右移一格」的
       * 老做法；现在统一走 breakOutToTopLevel()，括号组例外（见那个函数）。
       */
      case '=':
      case 'less':
      case 'greater':
      case 'ge':
      case 'le':
        breakOutToTopLevel();
        return false;

      // 变量和 π 同理：sin(5x) 里的 5 不是角度
      case 'x': case 'y': case 'z': case 'a': case 'b': case 'pi':
        if (isDegreeCtrl(postCtrl())) deleteDegreeOnRight();
        return false;

      // ° 键自己：左右已经有 ° 就别再叠一个
      case 'degree':
        return isDegreeCtrl(postCtrl()) || isDegreeCtrl(preCtrl());

      // °′″：光标边上有数字（或 °）时，把数字收进度槽
      case 'dms': {
        var pre = preCtrl();
        if (isDigitCtrl(pre) || isDegreeCtrl(postCtrl()) || isDegreeCtrl(pre)) {
          var content = takeAngleContent();
          formulaField.write('\\dms{' + content + '}{}{}');
          formulaField.keystroke('Left');
          formulaField.keystroke('Left');
          formulaField.keystroke('Left');
          return true;
        }
        return false;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------
  // 5. 暴露给 Android 的接口
  // ---------------------------------------------------------------

  window.SuperCalcEditor = {
    /**
     * 执行一条按键命令，然后把光标往左退 cursorBack 次（把光标放进空槽）。
     *
     * `typed` 决定走哪条路，这个区分很关键、不能省：
     *   typed=true  → typedText()，也就是「用户敲了一个字符」。
     *                 分式键靠它（敲 `/` 会变成真分式）、括号键靠它（自动配对）。
     *   typed=false → write()，插入一段 LaTeX 命令，例如 \sqrt[]{}、\sin{}。
     * 参考实现里这两类分别对应 actions 用的是 u() 还是 a()，是逐条分开的。
     *
     * `symbol` 是按键标识，先过一遍 [filterCommand]（原版同款）：三角函数里
     * 自动补度数、° 键去重、退格收拾多余的 ° 都在那里面。
     */
    writeCommand: function (code, cursorBack, typed, symbol) {
      try {
        if (filterCommand(symbol, code)) return;
      } catch (e) {
        var bridge = window.Android;
        if (bridge && bridge.log) bridge.log('filter 失败 ' + symbol + ': ' + e.message);
      }
      try {
        if (typed) formulaField.typedText(code);
        else formulaField.write(code);
      } catch (e) {
        var bridge = window.Android;
        if (bridge && bridge.log) bridge.log('write 失败 ' + code + ': ' + e.message);
        return;
      }
      for (var i = 0; i < (cursorBack || 0); i += 1) {
        formulaField.keystroke('Left');
      }
    },

    typedText: function (text) {
      formulaField.typedText(text);
    },

    keystroke: function (name, times) {
      for (var i = 0; i < (times || 1); i += 1) {
        if (name === 'Backspace') filterBackspace();
        formulaField.keystroke(name);
      }
    },

    clear: function () {
      pushHistory(formulaField.latex());
      setLatexInternal('');
      lastSent = null;
      compute('');
      formulaField.focus();
    },

    setLatex: function (latex) {
      pushHistory(formulaField.latex());
      setLatexInternal(latex);
      lastSent = null;
      compute(current);
    },

    getLatex: function () {
      return formulaField.latex();
    },

    /** 当前公式的引擎输入（调试和测试用）。 */
    getSymja: function () {
      return symjaOf();
    },

    /** 把一段 latex 追加进公式。原版的 setCopyResult 就是这个，剪贴板槽点一下会用到。 */
    writeLatex: function (latex) {
      if (!latex) return;
      formulaField.write(normalizeLatex(latex));
      formulaField.focus();
    },

    /**
     * 显示一行状态文字（引擎启动中、方法按钮算出来的结果）。
     *
     * 原版没有这一块：它把结果甩给独立的结果页去渲染。在 M3 那个页面做出来之前，
     * 这里先当临时落脚点，默认不显示。
     */
    setStatus: function (text) {
      if (!statusDiv) return;
      statusDiv.textContent = text || '';
      statusDiv.style.display = text ? '' : 'none';
    },

    /**
     * 空公式时那行示例：label 是中文小标题，latex 是「算式 ⇒ 结果」。
     *
     * insetPx 是右边「全部举例」按钮的宽度（CSS px = dp），算式在剩下的空白里
     * 居中 —— 参考实现那个 ViewPager 也是填满按钮左边的整块空白、位图居中。
     */
    setExampleTip: function (label, latex, insetPx) {
      if (!exampleTipBox || !exampleTipField) return;
      exampleTipLabel.textContent = label || '';
      // 两行的例子（\newline）高度预算更小，字号上限也不一样，
      // 见 EXAMPLE_TIP_TWOLINE_MAX_HEIGHT_PX。
      exampleTipTwoLine = /\\newline/.test(latex || '');
      applyExampleTipClasses();
      try {
        exampleTipField.latex(latex || '');
        // MathQuill 解析不了时**不抛异常**，只是留一个空的根块 —— 这里补一条日志，
        // 免得以后改示例 LaTeX 写成它不认的写法（比如 `\{`、槽位形状不对的 `\int`）
        // 时静默地只剩中文标题。
        var root = exampleTipBox.querySelector('.mq-root-block');
        if (latex && root && root.className.indexOf('mq-empty') >= 0) {
          var bridge = window.Android;
          if (bridge && bridge.log) bridge.log('示例算式解析成空: ' + latex);
        }
      } catch (e) {
        var bridge = window.Android;
        if (bridge && bridge.log) bridge.log('示例算式渲染失败: ' + latex + ' / ' + e.message);
        exampleTipField.latex('');
      }
      if (typeof insetPx === 'number' && insetPx >= 0) {
        exampleTipBox.style.right = insetPx + 'px';
      }
      fitExampleTip();
    },

    /** 显示 / 收起示例行（公式是否为空、设置里的「举例展示」）。 */
    setExampleTipVisible: function (visible) {
      if (!exampleTipBox) return;
      exampleTipVisible = !!visible;
      applyExampleTipClasses();
      if (visible) fitExampleTip();
    },

    /**
     * 重新算一次当前公式。
     *
     * 引擎比编辑器晚就绪时用得上：之前那次 autoResult 只能拿到空串，
     * 而编辑器自己不会主动重算（公式没变）。
     */
    refresh: function () {
      lastSent = null;
      compute(formulaField.latex());
    },

    undo: function () {
      if (!undoStack.length) return;
      var previous = undoStack.pop();
      redoStack.push(formulaField.latex());
      setLatexInternal(previous);
      lastSent = null;
      compute(current);
      notifyHistory();
    },

    redo: function () {
      if (!redoStack.length) return;
      var next = redoStack.pop();
      undoStack.push(formulaField.latex());
      setLatexInternal(next);
      lastSent = null;
      compute(current);
      notifyHistory();
    },

    focus: function () {
      formulaField.focus();
    },

    /** 布局变化后让 MathQuill 重排（WebView 尺寸变化时要调）。 */
    reflow: function () {
      formulaField.reflow();
      resultField.reflow();
      numericField.reflow();
      // 重排会重写前缀的 scale()，观察器是微任务，这里顺手扫一遍更直观
      sweepSqrtScales(document.documentElement);
      if (exampleTipBox && exampleTipBox.className.indexOf('on') >= 0) {
        fitExampleTip();
      }
    },

    version: '1',
  };

  // 调试口子：浏览器里排查 MathQuill 内部状态时才挂出来，
  // 正常跑（WebView 里）window.__SUPERCALC_DEBUG__ 是 undefined，不生效。
  if (window.__SUPERCALC_DEBUG__) {
    window.SuperCalcEditor.__debug = {
      field: formulaField,
      controller: formulaField.__controller,
      root: formulaField.__controller.root,
      cursor: formulaField.__controller.cursor,
      MQ: MQ,
    };
  }

  // 通知 Android 侧：编辑器就绪，可以开始下发按键了
  setLatexInternal('');
  sweepSqrtScales(document.documentElement);
  if (window.Android && window.Android.onEditorReady) {
    window.Android.onEditorReady();
  }
})(window, document);
