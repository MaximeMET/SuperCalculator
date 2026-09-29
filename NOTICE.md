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

## 发布前的素材待办（M6）

现在的界面图形（图标、键盘符号等）都是本项目自己画的可矢量图，**没有**用原版的 382 张位图。
唯一还需要再确认的是公式编辑器里的 `Symbola` 字体：它取自 MathQuill 上游仓库，
不是从原版 APK 里抠的，但字体本身的再分发条款要在发布前落实（换成自己的等价字体或子集）。
