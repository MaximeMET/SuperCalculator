/*
 * 把 MathQuill 生成的 CSS 里的数学字体从 Symbola 换成 DejaVu Math TeX Gyre。
 *
 * 上游 main.less 里的字体栈写的是 Symbola，而 Symbola 现在的许可只给
 * 「个人非商业使用、不许再分发」（见 NOTICE.md），所以我们不能随包发它。
 * 替代品是 DejaVu Math TeX Gyre：DejaVu/Bitstream 许可允许再分发，
 * 字形覆盖也够（编辑器可能出现的 243 个字形里它覆盖 238 个）。
 *
 * 这个脚本是 build-mathquill.ps1 在 lessc 之后调用的，做两件事：
 *   1. 整块换掉 Symbola 的 @font-face；
 *   2. 把字体栈里的 Symbola 换成新字体名。
 *
 * 注意第 2 步必须按词边界替换：CSS 里有个 `.mq-nonSymbola` 类名
 * （JS 里也在用），直接全局替换会把它一起改坏。
 *
 * 用法：node tools/mathquill-css-patch.js <输入.css> [输出.css]
 */

'use strict';

const fs = require('fs');

const FAMILY = 'DejaVu Math TeX Gyre';
const FONT_FILE = 'font/DejaVuMathTeXGyre.ttf';

const input = process.argv[2];
const output = process.argv[3] || input;
if (!input) {
  console.error('用法：node tools/mathquill-css-patch.js <输入.css> [输出.css]');
  process.exit(2);
}

let css = fs.readFileSync(input, 'utf8');

const face = [
  '@font-face {',
  `  font-family: "${FAMILY}";`,
  `  src: url(${FONT_FILE}) format("truetype");`,
  '}',
].join('\n');

const oldFace = css.match(/@font-face\s*\{[^}]*Symbola[^}]*\}/);
if (!oldFace) {
  console.error('没找到 Symbola 的 @font-face —— 上游 CSS 变了，先看看再改。');
  process.exit(1);
}
css = css.replace(oldFace[0], face);

const stacks = (css.match(/(?<![-\w])Symbola(?![-\w])/g) || []).length;
if (stacks === 0) {
  console.error('字体栈里没找到 Symbola —— 上游 CSS 变了，先看看再改。');
  process.exit(1);
}
css = css.replace(/(?<![-\w])Symbola(?![-\w])/g, `"${FAMILY}"`);

fs.writeFileSync(output, css);
console.log(`  数学字体：Symbola -> ${FAMILY}（@font-face 1 处 + 字体栈 ${stacks} 处）`);
