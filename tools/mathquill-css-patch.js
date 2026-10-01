/*
 * 把 MathQuill 生成的 CSS 里的数学字体换成 STIX Two Math。
 *
 * 上游 main.less 里的字体栈写的是 Symbola，而 Symbola 现在的许可只给
 * 「个人非商业使用、不许再分发」（见 NOTICE.md），所以我们不能随包发它。
 *
 * 替代品选 STIX Two Math：它是科技出版领域的标准数学字体（STIX 项目），
 * 许可是 SIL OFL-1.1，随软件分发没有障碍。覆盖率实测：
 *   编辑器可能用到的 245 个字形 -> 覆盖 243 个（Symbola 是 243、Termes Math 是 238）
 *   科学符号区段 2784 个码位     -> 覆盖 2622 个（94%）
 *
 * 这个脚本是 build-mathquill.ps1 在 lessc 之后调用的，做两件事：
 *   1. 整块换掉数学字体的 @font-face；
 *   2. 把字体栈里的旧字体名换成新字体名。
 *
 * 注意第 2 步必须按词边界替换：CSS 里有个 `.mq-nonSymbola` 类名
 * （JS 里也在用），直接全局替换会把它一起改坏。
 *
 * 脚本是幂等的：喂给它上游原版 CSS（Symbola）或者之前打过的版本
 * （DejaVu Math TeX Gyre / TeX Gyre Termes Math / STIX Two Math）都能收敛到同一份结果。
 *
 * 用法：node tools/mathquill-css-patch.js <输入.css> [输出.css]
 */

'use strict';

const fs = require('fs');

const FAMILY = 'STIX Two Math';
const FONT_FILE = 'font/STIXTwoMath-Regular.ttf';
const FORMAT = 'truetype';

// 见过的旧字体名：上游的、以及我们自己换过的
const SOURCE_FAMILIES = [
  'Symbola',
  'DejaVu Math TeX Gyre',
  'TeX Gyre Termes Math',
  'STIX Two Math',
];

const input = process.argv[2];
const output = process.argv[3] || input;
if (!input) {
  console.error('用法：node tools/mathquill-css-patch.js <输入.css> [输出.css]');
  process.exit(2);
}

let css = fs.readFileSync(input, 'utf8');

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// 早先的版本可能留下 `""Family""` 这种重复引号，先收干净
css = css.replace(new RegExp(`"{2,}${escapeRe(FAMILY)}"{2,}`, 'g'), `"${FAMILY}"`);

const face = [
  '@font-face {',
  `  font-family: "${FAMILY}";`,
  `  src: url(${FONT_FILE}) format("${FORMAT}");`,
  '}',
].join('\n');

const faces = css.match(/@font-face\s*\{[^}]*\}/g) || [];
const oldFace = faces.find((block) => SOURCE_FAMILIES.some((name) => block.includes(name)));
if (!oldFace) {
  console.error('没找到数学字体的 @font-face —— 上游 CSS 变了，先看看再改。');
  process.exit(1);
}
css = css.replace(oldFace, face);

// 旧字体名在字体栈里出现几次（.mq-nonSymbola 那类标识符不算）。
// 名字两边可能已经有引号，连引号一起吃进来，统一换成带引号的新名字。
const tokenRe = new RegExp(
  `(?<![-\\w])"?(?:${SOURCE_FAMILIES.filter((name) => name !== FAMILY).map(escapeRe).join('|')})"?(?![-\\w])`,
  'g',
);
const stacks = (css.match(tokenRe) || []).length;
if (stacks === 0 && !css.includes(`"${FAMILY}"`)) {
  console.error('字体栈里既没有旧字体名也没有目标字体 —— 上游 CSS 变了，先看看再改。');
  process.exit(1);
}
css = css.replace(tokenRe, `"${FAMILY}"`);

fs.writeFileSync(output, css);
console.log(
  stacks > 0
    ? `  数学字体 -> ${FAMILY}（@font-face 1 处 + 字体栈 ${stacks} 处）`
    : `  数学字体已经是 ${FAMILY}，只刷新了 @font-face`,
);
