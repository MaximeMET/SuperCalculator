# 许可与第三方组件

## 本项目

supercalc 以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

选 GPL-3.0 不是偏好问题：引擎链接了 Symja（GPL-3.0），整个项目就只能跟着走 GPL-3.0。

## 第三方组件

| 组件 | 版本 | 许可证 | 许可证全文 | 用途 |
|---|---|---|---|---|
| Symja | 2016-04-15（修改版） | GPL-3.0 | [LICENSE](LICENSE) | 符号计算内核 |
| JAS `edu.jas` | 随 Symja 分发 | LGPL-2.1 | 见上游 | 多项式、Gröbner 基 |
| Apache Commons Math 4 `org.apache.commons.math4` | 随 Symja 分发 | Apache-2.0 | [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | 数值方法 |
| Apfloat `org.apfloat` | 随 Symja 分发 | MIT | 见上游 | 任意精度浮点 |
| Redberry `cc.redberry` | 随 Symja 分发 | GPL-3.0 | 见上游 | 张量计算 |
| lab4inf `de.lab4inf` | 随 Symja 分发 | 见上游 | 见上游 | 数值工具 |
| MathQuill | 0.10.1（修改版） | MPL-2.0 | [licenses/MPL-2.0.txt](licenses/MPL-2.0.txt) | 公式编辑器 |
| jQuery | 2.1.4 | MIT | [licenses/jquery-MIT.txt](licenses/jquery-MIT.txt) | MathQuill 的依赖 |
| TeX Gyre Termes | 2.004（CTAN 上游） | GUST Font License | [licenses/GUST-Font-License.txt](licenses/GUST-Font-License.txt) | 编辑器里 `"Times New Roman"` 指向它 |
| STIX Two Math | 2.12（stixfonts / Google Fonts） | SIL OFL-1.1 | [licenses/OFL-1.1.txt](licenses/OFL-1.1.txt) | 编辑器的主字体 |
| AndroidX / Material Components | 见 `gradle/libs.versions.toml` | Apache-2.0 | 见上游 | Android 界面 |

> 上表按 `engine/libs/symja-2016-04-15.jar` 里**实际打进去的包**列的，
> 每个包的权威许可声明以各上游项目为准（jar 里是编译产物，不带 LICENSE 文件）。

### 修改过的组件：源码在哪

两个组件是以**修改版**形式随本项目分发的，两条许可证都要求说清楚改动和源码获取方式。

**Symja（GPL-3.0）** —— `engine/libs/symja-2016-04-15.jar`

改动内容：对数渲染、`TeXFormFactory` 运算符表、`TeXFunction` 括号形状、求值后降幂重排，
以及给求解结果补数值后缀（详见 README「引擎内核」一节）。

对应源码：

```powershell
pwsh tools/build-symja.ps1 -WorkDir work/symja
```

脚本会拉取上游 `version_2016-04-15` 的完整源码，套用 `tools/symja-patches/` 和脚本内的补丁，
重新编译出这个 jar——也就是说，拿到本仓库就能重建出与 jar 完全对应的源码。

**MathQuill（MPL-2.0）** —— `app/src/main/assets/matheditor/mathquill/mathquill.min.js`

改动内容：对外暴露内部对象（`MathQuill.P` / `LatexCmds` / `MathQuill.Node` 等）供命令层使用，
并按本项目的构建参数重新压缩。文件头的 MPL 声明保留未动。

对应源码：

```powershell
pwsh tools/build-mathquill.ps1
```

同样是从上游 `v0.10.1` 源码重建，补丁在 `tools/mathquill-patches/`。

> MPL-2.0 是**文件级** copyleft：只有被改过的那个文件（`mathquill.min.js`）继续受 MPL 约束，
> 项目的其余部分仍是 GPL-3.0。两者兼容。

## 与参考实现的关系

原版 `com.youdao.calculator` 只作为**行为规格**使用：观察交互、比对截图、通过差分测试采集输出。
本仓库不分发也不包含原版的任何代码、图片、字体、音频或有道品牌素材；
`tools/matheditor/golden-keys-orig.json` 一类文件是**实测数据**（命令对应的字符串结果），
不是原版代码。

## 素材来源（M6）

界面图形全部是**本项目自己产出**的：键盘符号、工具条图标、书签、抽屉图标是按轮廓重画的矢量，
关于页 logo 和启动图标是项目自己的标记（`tools/make_launcher_icon.py` 生成，
`bg_about_logo` / `ic_about_logo_mark` / `ic_launcher_foreground` 三处矢量），
分享底图是画布现画的。仓库里没有原版的 382 张位图，也没有原版那份品牌 logo。

> 需要说明的是：键盘那批图标虽然已经是自绘矢量，形状仍是照原版位图的轮廓描的
> （描图脚本在 `work/tools/`，不随仓库分发）。它们画的是 ∫ √ log 这类通用数学符号，
> 但如果要把「脱胎于原素材」这条路走到头，得重新设计一套——那会牺牲键盘的逐像素一致，
> 目前没做这一步。

字体一共三处，都已经收口：

**TeX Gyre Termes** —— 取自 CTAN 上游 2.004 版，GUST Font License（LPPL 家族），
原样再分发是允许的，许可证全文已在 `licenses/`。`tools/fetch-editor-fonts.ps1`
会从 CTAN 重新拉一遍并逐个核对 SHA-256，脚本里记的哈希与仓库里的文件一致。

**STIX Two Math 2.12** —— 编辑器的主字体（`STIXTwoMath-Regular.ttf`，1.45 MB）。
STIX（Scientific and Technical Information Exchange）是 IEEE 牵头、为科技出版
做的一套字体（LaTeX 的 `stix2` 宏包同源）。这份 ttf 取自 Google Fonts 仓库里的
OFL 发布版 `ofl/stixtwomath/`，与 stixfonts 上游同源，SIL OFL-1.1 允许自由再分发，
全文见 `licenses/OFL-1.1.txt`。

SHA-256 `562551B15B836E6E01D1B7350909BAF3C8C8D83260C1190FBF4544333E6936DE`，
记在 `tools/fetch-editor-fonts.ps1` 里；脚本会从 Google Fonts 的 raw 地址下载并核对
（这条地址已经实测跑通、哈希一致），对不上会直接报错，不会悄悄换成别的文件。

**Symbola —— 已删除，不再随仓库分发。** MathQuill 上游把它放在 `src/font/` 里
一起发（与 `v0.10.1` 逐字节一致），但字体的著作权人 George Douros 现在给出的
UFAS 许可写的是：

> user: … may use ufas for **strictly personal and non-commercial purposes**, without charge;
> … may not host, loan to service bureaus or in any way **redistribute** ufas, with or without charge;
> Public use of ufas requires the purchase of a Public License.

换句话说，再分发 Symbola 不符合这份许可，MathQuill 上游当年把它放进仓库
也不能替我们拿到授权，所以 M6 把它整批删掉，换成了上面的 STIX Two Math
（中间短暂用过 DejaVu Math TeX Gyre，后来因为覆盖率差一截被替换）。
`tools/build-mathquill.ps1` 现在不再复制上游的 `src/font/`，
`tools/mathquill-css-patch.js` 负责把生成出来的 CSS 里的 `@font-face` 和字体栈
换成新字体（按词边界替换，不动 `.mq-nonSymbola` 这个类名）。
