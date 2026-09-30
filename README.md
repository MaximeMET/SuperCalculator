# supercalc

一个「超级计算器」的重写实现。

目标是**功能与输出 100% 对齐**那个 2017 年发布、2019 年后停止维护的有道超级计算器，
但代码、资源、品牌全部重新实现——原版只作为**行为规格**使用，不作为代码来源。

> 本项目与网易有道无任何关联，也不包含任何有道的代码或素材。

## 为什么做这个

那个计算器很好用：输入什么都行，它会自己判断你想干什么——你打一个方程，它给你「求解方程」；
你打一个高次多项式，它给你「因式分解」和「多项式展开」；你打一个函数，它给你「求导」「积分」「画图」。
2019 年之后没人维护了，targetSdk 停在 22（Android 5.1），
Android 14 起连安装都会被系统拒绝。

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 基准固化：反编译、实机运行、截图、抓取真实输出 | ✅ |
| M1 | 引擎复刻 + 差分测试 | ✅ 基本收口（1140 条语料，按钮结果 99.2%） |
| M2 | 计算器主界面（键盘 / 公式编辑 / 方法按钮） | 🔄 进行中 |
| M3 | 结果页 / 历史 / 设置 / 教程 | ⬜ |
| M4 | 函数图像 | ⬜ |
| M5 | 超级 24 点 | ⬜ |
| M6 | 素材替换 + 开源发布 | ⬜ |

## 模块

```
engine/   纯 JVM 数学引擎（无 Android 依赖，可独立测试）
app/      Android 应用
```

### M2 现状：主界面已经能在手机上跑起来

`app` 模块已经能构建、安装、启动，并且**真的接上了引擎**：
敲 `1 / 3` 停顿半秒，公式区渲染出真分数，右边跟着精确解 `= 1/3` 和数值解
`= 0.3333333333`；运算按钮条也会按 `MethodAdvisor` 的判定浮出来。
键盘四页、左侧书签、工具行（清空 / 换行 / 左右移光标 / 退格）、撤销重做都已接好。

公式编辑器和原版一样是 **WebView 里的 MathQuill**：页面、样式、命令层都在
`app/src/main/assets/matheditor/`，Kotlin 侧只有两个薄封装——`editor/MathEditor.kt`
往下发按键，`editor/EditorBridge.kt` 同步取结果。公式状态（公式树、光标、撤销栈）
全留在 JS 那一侧，和原版的分工一致。

#### 命令层：M2 最费劲的一块

原版用的**不是**官方 MathQuill 0.10.1，而是一个带 `symja()` 的分支：每条 LaTeX 命令
自己算引擎输入（`\frac{a}{b}` → `((a)/(b))`、`\ge` → `>=`、`e` → `E`……），
Android 侧只收一个现成的字符串。这份分支没有公开源码，所以做法是把它当**活体标尺**：
同一张键盘命令表（`keyboard/KeyboardModel.kt` 抄自原版 bundle）跑两边，
逐键比对 `latex()`、渲染出的 HTML、`symja()` 三样东西。

结果：**78 个单键 + 48 条连打序列，全部一致**。原版实测结果固化成
`tools/matheditor/golden-keys-orig.json`（行为规格，不是代码），
回归一条命令跑完：

```powershell
pwsh tools/matheditor/run_probes.ps1 -SkipOrig   # 只测自己，对基准文件
pwsh tools/matheditor/run_probes.ps1             # 有原版 min.js 时两边一起跑
```

踩过的坑记在 `tools/matheditor/README.md`，其中一条值得单独说：隐式乘法。
Symja 不认 `5x`，所以 `y=kx+b` 这类模板在系数非空时要补 `*`，空槽位又不能再留一个
孤零零的 `*`——原版就是这么按槽位分别处理的，照着做才逐字符一致。

键盘的几何不是拍脑袋定的，几条规则都来自原版源码：

- 键盘高度 = 窗口高度的 **50%**（`keyboardHeightScreenPercent`）
- 行高 = 键盘宽度 / 6 + 1dp（`keyboardGridRatio`）
- 四页串成一条**连续滚动的长条**，不是分页 ViewPager；书签直接滚到页首
- 前三页 5 列；「公式」页是单列，左边挂一条文字说明栏

排版也是照着实机逐像素对出来的（对比图在 `work/logs/`）。原版有几处
「看不出来」但很要命的设置：

- 编辑器页面引了一份 `tex-font.css`，把 `Times New Roman` 这个名字指向
  **TeX Gyre Termes**（字体文件在 `assets/matheditor/mathquill/font/`），
  不带上它整页公式都会落到系统衬线字体上，字宽行高全偏
- `base.css` 里写的是 `font:12px/1.5tahoma`——`1.5` 和 `tahoma` 之间差一个空格，
  整条 font 简写其实没生效，字号落在 WebView 默认的 16px 上；再算上没有
  `width=device-width` 带来的自动放大，等效基准字号 ≈ **17.6px**
- 「全部举例」那行原版是九张预渲染位图（`ic_emptytip_0..8`），文案烤在图里。
  位图要全部替换掉，所以这里按图里的**文字内容**重写：左边白字
  「标签：算式 ⇒ 答案」，右边橙字「全部举例」，点一下把算式填进编辑器

`engine` 是整个项目的核心。它复刻了原版的计算链路：

1. **表达式解析**：Symja 宽松语法，解析失败静默返回 null（输入一半不会崩）
2. **六个基础算子**：积分、求导、展开、因式分解、求解、数值化
3. **方法推荐**：解析表达式树，决定该显示哪几个运算按钮
4. **结果后处理**：精度截断、科学计数法改写、尾零处理、角度度分秒

### 方法推荐逻辑

```
解析表达式
├─ 含 Limit        → [数值结果]
├─ 含 NIntegrate   → [定积分]
├─ 纯数字 / LCM / GCD → [继续计算]
├─ 不等式
│   ├─ 单行 → [解不等式]
│   └─ 多行 → [解不等式, 解不等式组]
├─ 等式 (==)
│   ├─ 单个 ==
│   │   ├─ 多行 → [求解方程组]
│   │   ├─ 含未知数 → [求解方程]
│   │   └─ y = f(x) → [绘制图像]
│   └─ 多个 == → 无
└─ 含未知数
    → [积分, 求导]
    → 只含 x/y → 追加 [绘制图像]
    → 高次多项式 → 追加 [多项式展开, 多项式分解]
```

「继续计算(耗时较长)」什么时候出现，原版藏得比较深：按钮列表由
`SymjaManager.getMethods(needCalc)` 生成，而 `needCalc` 来自
`mAutoResFinished`——它**只在自动结果 5 秒超时**时才会被置回 false。
也就是说，只有一次算不完的公式才会多出这个按钮（同时弹一句
「当前运算较复杂，耗时较长」）；`1/3` 这类秒出的公式根本不会有它。
基准 logcat 里能看到 `getMethods return size 0`。

## 怎么保证「一模一样」

不靠感觉，靠差分测试。

参考 App 跑在 Android 15 模拟器上（`adb install --bypass-low-target-sdk-block`），
它的计算日志可以从 logcat 里直接抓：

```
adb logcat -s calc:* | grep 'getAutoResult returned res'
```

例如实测：

```
1/3  →  = \frac{1}{3}$$= 0.3333333333
1/7  →  = \frac{1}{7}$$= 0.1428571429
```

这两个值已经作为硬断言写进 `EngineRegressionTest`，改动引擎后必须一字不差。
后续会持续扩充语料库。

> `$$` 是结果分隔符：左边是精确解，右边是数值解。

## 引擎内核：我们改了上游的什么

参考 App 里的 Symja **不是原版**，是一个被大改过的分支：

- 多出 `core/computeprocess`、`core/eval/util/segmentfunction` 等整包（解方程过程、分段函数）
- 砍掉了 `DSolve`、`Matcher`、`LaplaceTransform` 等一批上游类
- 连排版行为都动过（加法项序、函数括号形状、对数写法）

那份分支源码没有公开，所以这里不走「对齐源码」的路线，而是用差分测试逼近行为：
在上游 tag `version_2016-04-15` 上打最小补丁。选它的依据是**顶级类名集合比对**——
和基准的重合度最高（差异 140，次优 `2017-04-06` 是 465）。

目前的四处补丁：

| 补丁 | 内容 |
|---|---|
| `reflection/Log` | 对数渲染成 `\ln{x}` / `\log_{b}{x}`（上游根本没有这个转换器） |
| `TeXFormFactory` | `Log` 不进 `operTab`；补 `Sec`/`Csc`；补 `E → e`；通用函数用 `\left( \right)` 而不是裸括号 |
| `TeXFunction` | `\cos(x)` → `\cos{x}`，多参数用 `\,` 分隔 |
| `EvalAttributes` + `EvalEngine` | 每次顶层求值后，递归把结果里的 `Plus` 按**降幂**重排（原版 `x^2-1` 显示成 `x^{2}-1`，而不是 Symja 默认的 `-1+x^{2}`） |

### 不等式求解是自己写的

参考实现用的是分支自带的 `SolveInequality` / `SolveSystemInequality`，上游 Symja 里
既没有这个包、`Reduce` 也是未实现状态。所以 `InequalitySolver` 按可观测行为重写了一份：

1. 把不等式化成 `f(x) REL 0`
2. 取全部临界点——分子的零点（等号可能成立的地方）和分母的零点（断点）
3. 相邻临界点之间各取一个采样点判号
4. 把连续成立的区间并起来，每段翻译成一组合取条件

`|u| REL c` 先平方化成 `u² REL c²` 再走同一套流程——上游的 `Solve` 不会解 `Abs`。
另外判号时必须把 `Infinity` 排掉：`1/x /. x->0` 得到的是 `Infinity`，而它
`toDoubleOrNull()` 是能成功的，不拦就会把断点算成解的一部分。

输出形状是「列表套列表」：外层是若干情形（或），内层是同时成立的条件（且）。
Symja 的 TeX 转换器正好把 `{{x>3}}` 排成
`\left(\begin{array}{c} x > 3 \end{array}\right)`，与基准逐字符一致，所以不用自己拼 LaTeX。

### 求解方程的数值后缀

参考实现会给「不够直白」的根再补一段数值形式：

```
x^2==2   ->  x= \sqrt{2}= 1.4142135624
ln(x)==1 ->  x= e= 2.7182818285
x^2==-1  ->  x= -1\,i = 0.0 + -1.0\,i
```

拼接用的是 **Rule** 而不是 Equal：`Equal[\sqrt{2}, 1.4142135623730951]` 会被 Symja
直接判成 `True`，那就只剩一个 `true` 可显示了。`Rule` 在 TeX 里是 `\to`，
后面本来就会被替换成 `=`，正好接得上。

判据是三条同时成立：根里不含未知数、不是整数也不是有理数、`N[根]` 是有限的数。
所以 `x= 2`、`x= \frac{1}{5}`、`x= \sqrt{a}`、`x= \frac{-b}{a}` 都不会被补。

补丁都写在 `tools/build-symja.ps1` 和 `tools/symja-patches/` 里，可重新生成内核：

```powershell
pwsh tools/build-symja.ps1 -WorkDir work/symja
```

### 差分测试现状（1140 条语料）

| 项目 | 一致 |
|---|---|
| 自动预览 | 1140 / 1140 |
| 方法按钮集合 | 1131 / 1131 |
| 方法计算结果 | 1229 / 1239（99.2%） |
| 绘图公式 | 69 / 69 |

「绘制图像」不进文本结果那一栏：那个按钮根本没有文本结果。原版绘图页拿走的是
`DrawMethod.getSymjaFormula()`（`上一行 + "\n" + 当前行`，这里的 `\n` 是**字面反斜杠加 n**，
当多函数分隔符用），再由 `ScaleGraphView` 按 `[\*]*\\n[\*]*` 切开、逐段画。
探针现在把这条通道单独记到 `ref_draw.tsv`，引擎侧对应 `Method.drawFormula()` /
`Method.splitDrawFormula()`。

剩下的 10 条按方法分布：积分 3、多项式分解 3、求导 3、求解方程 1。

- **积分 3 条**：`1/x` 这类积分分支返回 `Log[Abs[x]]`，上游返回 `Log[x]`。
- **多项式分解 3 条、求导 2 条**：`(1+x)^n` 当底数落在 `Power` 里时，
  分支有一部分节点不参与降幂重排（`{\left( 1+x\right) }^{2}` 而不是 `{\left( x+1\right) }^{2}`）。
  已经排除「递归 / 不递归」这种简单解释：`(x^2-1)/(x-1)` 的求导结果里，
  嵌在 `Power` 里的 `-x+1` 又确实被降幂重排过。要弄清得拿到分支的求导实现，
  在那之前不动现有逻辑。
- **求导 1 条**：`cot(x)` 的导数分支化成 `\frac{-1}{{\sin{x}}^{2}}`，上游化成 `-{\csc{x}}^{2}`。
- **求解方程 1 条**：`sin(x)==0` 在分支里会返回「无数解」标记（`{…,p}`），
  上游只返回 `x= 0`。

> 语料库从 216 条扩到 1140 条之后，插桩探针跑完仍然只要几秒——
> 加语料的成本几乎为零，比按坐标点按钮快几个数量级。

### 期望值是怎么采的

按坐标点按钮要 10 秒一条，改成给参考包插一个广播接收器，直接反射调用它自己的
`SymjaManager`，1140 条不到 5 秒。流程（需要 root 的模拟器）：

```bash
adb push corpus.txt /data/local/tmp/
adb shell cp /data/local/tmp/corpus.txt \
  /data/data/com.youdao.calculator/files/probe_in.txt
adb shell am broadcast -n com.youdao.calculator/com.youdao.calculator.probe.ProbeReceiver
# 等 files/probe_done.txt 出现 OK，
# 再拉 probe_auto.tsv / probe_methods.tsv / probe_draw.tsv
```

探针本体见 `work/probe/src/.../ProbeReceiver.java`，重建脚本是 `work/probe/build-probe.ps1`
（javac → d8 → apktool 编基础包 → 塞 classes3.dex → zipalign → 签名）。只在插桩版里跑，
不随项目分发。

## 构建

需要 JDK 17。

```bash
./gradlew :engine:test        # 跑引擎测试
```

测试里有两个会往 `engine/build/diagnostic/` 写对照表的诊断用例，
用来人工比对参考 App 的实测输出。

## 许可

GPL-3.0。这不是随便选的：引擎依赖的 Symja 是 GPL-3.0，链接它就决定了整个项目必须是 GPL-3.0。

详见 [LICENSE](LICENSE)。

### 第三方组件

| 组件 | 许可证 | 用途 |
|---|---|---|
| [Symja](https://github.com/axkr/symja_android_library) | GPL-3.0 | 符号计算内核（含 Rubi 积分规则、JAS 代数系统、Apfloat 高精度浮点） |
| Hipparchus | Apache-2.0 | 数值方法 |
| [MathQuill](https://github.com/mathquill/mathquill) | MPL-2.0 | 公式编辑器（`app/src/main/assets/matheditor/mathquill/`） |
| [jQuery](https://jquery.com/) 2.1.4 | MIT | MathQuill 的运行时依赖 |
| [MathJax](https://www.mathjax.org/) 3.2.2 | Apache-2.0 | 结果页公式排版（`app/src/main/assets/mathjax/`） |
| AndroidX / Material | Apache-2.0 | Android 界面基础库 |

`engine/libs/symja-2016-04-15.jar` 是 Symja 的**修改版**（GPL-3.0）。
对应的源码获取方式就是上面那条 `build-symja.ps1` 命令：
它拉取上游 `version_2016-04-15` 的完整源码，再套用 `tools/symja-patches/` 与本脚本里的补丁。

`app/src/main/assets/matheditor/mathquill/mathquill.min.js` 同样是 MathQuill 0.10.1 的**修改版**
（MPL-2.0）。MPL 是文件级 copyleft，要求修改过的文件继续以 MPL 提供、并说明源码在哪：
这里是在上游 v0.10.1 上打了两个补丁（暴露内部对象给命令层、按我们的构建参数重新压缩），
重建命令是

```powershell
pwsh tools/build-mathquill.ps1
```

它会拉取上游源码、套用 `tools/mathquill-patches/`，再输出到 `app/src/main/assets/matheditor/mathquill/`。
完整的 MPL-2.0 与 MIT 原文见 [NOTICE.md](NOTICE.md)。

> MathQuill 自带的 `Symbola` 字体也是从上游仓库取的（哈希与 `mathquill-0.10.1/src/font/`
> 逐个一致），不是从原版 App 里抠出来的素材。

`app/src/main/assets/mathjax/` 是从官方 npm 包 `mathjax@3.2.2` 的 `es5/` 目录里
挑出来的一小套离线运行时（`tex-svg.js`、`output/svg/fonts/tex.js`、
几个 TeX 扩展、`ui/menu.js`、`a11y/assistive-mml.js`），
不是原版 App 里那份 MathJax 2.7 / STIX-Web 的拷贝。
重建命令：

```powershell
pwsh tools/fetch-mathjax.ps1
```

## 与参考实现的关系

原版 `com.youdao.calculator` v2.0.0 只用于：

- 观察交互行为、界面布局、视觉规格
- 提取功能清单、数据模型（历史库 schema）、键盘键位表
- 通过 logcat 抓取计算输出，作为差分测试的期望值

本项目不包含、也不再分发原版的任何代码、图片、字体或品牌素材。
