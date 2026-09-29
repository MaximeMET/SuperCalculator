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
| M1 | 引擎复刻 + 差分测试 | 🔄 进行中 |
| M2 | 计算器主界面（键盘 / 公式编辑 / 方法按钮） | ⬜ |
| M3 | 结果页 / 历史 / 设置 / 教程 | ⬜ |
| M4 | 函数图像 | ⬜ |
| M5 | 超级 24 点 | ⬜ |
| M6 | 素材替换 + 开源发布 | ⬜ |

## 模块

```
engine/   纯 JVM 数学引擎（无 Android 依赖，可独立测试）
app/      Android 应用（待建）
```

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

补丁都写在 `tools/build-symja.ps1` 和 `tools/symja-patches/` 里，可重新生成内核：

```powershell
pwsh tools/build-symja.ps1 -WorkDir work/symja
```

### 差分测试现状（1140 条语料）

| 项目 | 一致 |
|---|---|
| 自动预览 | 1140 / 1140 |
| 方法按钮集合 | 1131 / 1131 |
| 方法计算结果 | 1207 / 1308（92.3%） |

剩下的 101 条按方法分布：绘制图像 69、解不等式 14、求解方程 9、积分 3、
多项式分解 3、求导 3。归因是三块：

**1. 探针测错了通道（69 条，全是「绘制图像」）**——原版绘图页并不使用
`evaluateAndConvertLaTex` 的返回值，它拿的是 `DrawMethod.getSymjaFormula()`
（`上一行 + "\n" + 当前行`，这里的 `\n` 是**字面反斜杠加 n**，当作多函数分隔符用），
再由 `ScaleGraphView` 按 `[\*]*\\n[\*]*` 切开取最后一段。探针测了被丢弃的那条通道，
所以每一条都稳定多出个 `n`。改探针即可，不是引擎缺陷。

**2. 分支独有的求解能力（14 条，全是「解不等式」）**——原版用的是分支里自己写的
`SolveInEquality` / `SolveSystemInequality`（上游没有这个包），
输出还会排成 `\begin{array}` 矩阵。这部分要照行为重写一个不等式求解器。

**3. 真正待查的 18 条**——求解方程 9 条（分支会给非整数根和复根追加 `= 数值形式`）、
`1/x` 这类积分差一层绝对值（分支的积分器返回 `Log[Abs[x]]`，上游返回 `Log[x]`）、
`(1+x)^n` 当底数落在 `Power` 里时的项序（分支有一部分不参与降幂重排）。

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
# 等 files/probe_done.txt 出现 OK，再拉 probe_auto.tsv / probe_methods.tsv
```

探针本体见 `work/probe/src/.../ProbeReceiver.java`，只在插桩版里跑，不随项目分发。

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

`engine/libs/symja-2016-04-15.jar` 是 Symja 的**修改版**（GPL-3.0）。
对应的源码获取方式就是上面那条 `build-symja.ps1` 命令：
它拉取上游 `version_2016-04-15` 的完整源码，再套用 `tools/symja-patches/` 与本脚本里的补丁。

## 与参考实现的关系

原版 `com.youdao.calculator` v2.0.0 只用于：

- 观察交互行为、界面布局、视觉规格
- 提取功能清单、数据模型（历史库 schema）、键盘键位表
- 通过 logcat 抓取计算输出，作为差分测试的期望值

本项目不包含、也不再分发原版的任何代码、图片、字体或品牌素材。
