#!/usr/bin/env node
/*
 * 用 uglify-js 压缩 MathQuill。
 *
 * 为什么不用命令行 + PowerShell 管道：uglify 会把源码里的 `\u2265` 还原成真字符
 * 再输出，而 PowerShell 的 `Out-File -Encoding ascii` 会把非 ASCII 一律写成 '?'，
 * 于是 LatexCmds['÷'] 变成 LatexCmds['?']——键盘上的 ≥ ≤ × ÷ π 全部失效。
 * 这里直接调 uglify 的 API，并打开 ascii_only，输出保证纯 ASCII。
 *
 * 用法：node mathquill-minify.js <源码> <输出> <uglify-js 模块目录>
 */

const fs = require("fs");
const path = require("path");

const [input, output, uglifyDir] = process.argv.slice(2);
if (!input || !output || !uglifyDir) {
  console.error("用法: node mathquill-minify.js <源码> <输出> <uglify-js 模块目录>");
  process.exit(2);
}

const UglifyJS = require(path.resolve(uglifyDir));
const code = fs.readFileSync(input, "utf8");

const result = UglifyJS.minify(code, {
  fromString: true,
  compress: { hoist_vars: true },
  mangle: true,
  output: {
    ascii_only: true,
    comments: /maintainers@mathquill\.com/,
  },
});

if (result.error) {
  console.error(result.error);
  process.exit(1);
}

fs.writeFileSync(output, result.code, "ascii");
console.log(`压缩完成 -> ${output} (${result.code.length} 字节)`);
