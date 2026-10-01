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
  };

  Symbol.prototype.symja = function () {
    var ctrl = String(this.ctrlSeq || '').replace(/\s+$/, '');
    if (SYMJA_TEXT[ctrl] !== undefined) return SYMJA_TEXT[ctrl];
    var t = this.textTemplate;
    var text = (t && t[0]) || ctrl;
    return SYMJA_TEXT[text] !== undefined ? SYMJA_TEXT[text] : text;
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

  // 上下标：_() / ^()，原版两个槽位都写出来（空着也写）
  MathQuill.SupSub.prototype.symja = function () {
    var out = '';
    if (this.sub) out += '_(' + this.sub.symja() + ')';
    if (this.sup) out += '^(' + this.sup.symja() + ')';
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
   * 放不下就整体缩字号。
   *
   * 参考实现这一行是位图，按可用宽度整体缩放 —— 最长那条「求解方程组」在原版里
   * 明显比别的条目小一档。我们照同样的做法：先按 13px 量，超宽（或者高到要压到
   * 键盘上）就按 0.5px 往下缩。
   */
  function fitExampleTip() {
    if (!exampleTipBox || exampleTipBox.className.indexOf('on') < 0) return;
    var avail = exampleTipBox.clientWidth;
    if (avail <= 0) return;
    var size = EXAMPLE_TIP_MAX_PX;
    exampleTipBox.style.fontSize = size + 'px';
    exampleTipField.reflow();
    var tooWide = function () { return exampleTipInner.offsetWidth > avail; };
    var tooTall = function () { return exampleTipInner.offsetHeight > EXAMPLE_TIP_MAX_HEIGHT_PX; };
    while (size > EXAMPLE_TIP_MIN_PX && (tooWide() || tooTall())) {
      size -= EXAMPLE_TIP_STEP_PX;
      exampleTipBox.style.fontSize = size + 'px';
      exampleTipField.reflow();
    }
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
      var bridge = window.Android;
      if (bridge && bridge.copyNumericResult) {
        bridge.copyNumericResult(symjaOf(numericField), numericField.latex());
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
    resultField.latex(exact);
    numericField.latex(numeric);
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
     */
    writeCommand: function (code, cursorBack, typed) {
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
      for (var i = 0; i < (times || 1); i += 1) formulaField.keystroke(name);
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
      exampleTipBox.className = visible ? 'exampleTip on' : 'exampleTip';
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
  if (window.Android && window.Android.onEditorReady) {
    window.Android.onEditorReady();
  }
})(window, document);
