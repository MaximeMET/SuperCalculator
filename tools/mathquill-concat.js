#!/usr/bin/env node
/*
 * 按官方 Makefile 的顺序把 MathQuill 的源码拼成一个文件。
 *
 * 为什么要自己拼：官方 Makefile 的规则是 `cat $^`，而 Make 的 `$^` 会自动去掉
 * 重复的先决条件（`services/*.util.js` 同时被 `services/*.js` 匹配到），
 * 所以真正参与构建的每个文件**只出现一次**，顺序按首次出现的先后。
 * 直接用 shell 通配符拼会把 util 文件塞进去两遍，把前面挂上去的方法冲掉。
 *
 * 顺带复刻官方的 script/escape-non-ascii：把所有非 ASCII 字符转成 \uXXXX，
 * 免得在不同编码的 WebView 里出问题。
 */

const fs = require("fs");
const path = require("path");

const root = process.argv[2];
const outFile = process.argv[3];
const patchFile = process.argv[4]; // 我们的追加文件，插在 outro.js 之前（仍在 IIFE 内）
if (!root || !outFile) {
  console.error("用法: node mathquill-concat.js <mathquill 源码目录> <输出文件>");
  process.exit(2);
}

const listDir = (dir, suffix) =>
  fs
    .readdirSync(path.join(root, dir))
    .filter((name) => name.endsWith(suffix))
    .sort(); // C 语言环境的字典序，和官方构建机一致

// 顺序照抄 Makefile 的 BASE_SOURCES + SOURCES_FULL，并按 $^ 的规则去重
const sources = [
  "src/intro.js",
  "node_modules/pjs/src/p.js",
  "src/tree.js",
  "src/cursor.js",
  "src/controller.js",
  "src/publicapi.js",
  ...listDir("src/services", ".util.js").map((n) => `src/services/${n}`),
  ...listDir("src/services", ".js").map((n) => `src/services/${n}`),
  "src/commands/math.js",
  "src/commands/text.js",
  ...listDir("src/commands/math", ".js").map((n) => `src/commands/math/${n}`),
  "src/outro.js",
];

// 追加文件要排在 outro.js 前头：outro.js 的结尾就是 IIFE 的收尾，
// 放它后面的话 P / LatexCmds 这些局部变量就看不见了。
if (patchFile) {
  const idx = sources.indexOf("src/outro.js");
  sources.splice(idx, 0, patchFile);
}

const seen = new Set();
const parts = [];
for (const rel of sources) {
  if (seen.has(rel)) continue;
  seen.add(rel);
  const full = path.isAbsolute(rel) ? rel : path.join(root, rel);
  parts.push(fs.readFileSync(full, "utf8"));
}

const joined = parts.join("");
const escaped = joined.replace(/[^\x00-\x7F]/g, (c) => {
  const code = c.codePointAt(0);
  return code > 0xffff
    ? "\\u{" + code.toString(16) + "}" // 理论上不会出现，MathQuill 全是 BMP 字符
    : "\\u" + ("000" + code.toString(16)).slice(-4);
});

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, escaped);
console.log(`${sources.length} 个源文件 -> ${outFile} (${escaped.length} 字节)`);
