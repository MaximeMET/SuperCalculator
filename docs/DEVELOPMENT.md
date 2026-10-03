# 开发笔记

这是 SuperCalculator 的开发笔记：里程碑、和原版逐字符 / 逐像素比对的经过、踩过的坑，
以及引擎补丁的来龙去脉。

- 这个 App 是干什么的、怎么用、怎么构建 → [README](../README.md)
- 这里提到的路径都在仓库里。对比图、插桩探针、反编译产物只留在开发机上，
  **不随仓库分发**；凡涉及原版素材或原版代码的部分，仓库里一个字节都没有，
  详见 [README「与参考实现的关系」](../README.md#与参考实现的关系)。

---

## 里程碑与状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 基准固化：反编译、实机运行、截图、抓取真实输出 | ✅ |
| M1 | 引擎复刻 + 差分测试 | ✅ 基本收口（1140 条语料，按钮结果 99.2%） |
| M2 | 计算器主界面（键盘 / 公式编辑 / 方法按钮） | ✅ |
| M3 | 结果页 / 历史 / 设置 / 教程 / 反馈 / 关于 | ✅ |
| M4 | 函数图像 | ✅ 绘图 / 拖动 / 缩放 / 最多 3 条函数 / 交点与点选气泡 / 图例 / 分享 |
| M6 | 素材替换 + 开源发布 | ✅ 品牌、文案、字体、图标全部自有；v0.1.0 已发布 |

> 原版抽屉里的「超级 24 点」不在复刻范围内，已按设计取消（M5 阶段整段跳过）：
> 抽屉项、图标、字符串和历史记录里那个类型号都删掉了。

## 代码结构

```
engine/   纯 JVM 数学引擎（无 Android 依赖，可独立测试）
app/      Android 应用
```

## M2：主界面

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
- 行高 = **窗口宽度 × 85%（`keyboardPagerWidthPercent`）/ 6 + 1dp**（`keyboardGridRatio`）；
  格子之间横竖各留 1dp（`space_mini`）。这两条一起决定了每条网格线的位置
- 四页串成一条**连续滚动的长条**，不是分页 ViewPager；书签直接滚到页首
- 前三页 5 列；「公式」页是单列，左边挂一条文字说明栏

排版也是照着实机逐像素对出来的（对比图留在开发机上，未随仓库分发）。原版有几处
「看不出来」但很要命的设置：

- 编辑器页面引了一份 `tex-font.css`，把 `Times New Roman` 这个名字指向
  **TeX Gyre Termes**（字体文件在 `assets/matheditor/mathquill/font/`），
  不带上它整页公式都会落到系统衬线字体上，字宽行高全偏
- `base.css` 里写的是 `font:12px/1.5tahoma`——`1.5` 和 `tahoma` 之间差一个空格，
  整条 font 简写其实没生效，字号落在 WebView 默认的 16px 上；再算上没有
  `width=device-width` 带来的自动放大，等效基准字号 ≈ **17.6px**
- 「全部举例」那行原版是九张预渲染位图（`ic_emptytip_0..8`），文案烤在图里。
  位图要全部替换掉，所以这里按图里的**文字内容**重写：左边白字
  「标签：算式 ⇒ 答案」，右边橙字（带下划线）「全部举例」。
  点左边白字把算式填进编辑器；点「全部举例」跳教程页——参考实现里它走的就是
  抽屉的 `nav_tutorial`（`CalculatorFragment$4` → `onNavItemSelected`）。

## M3：结果页、历史、设置、教程、反馈、关于

除「过程展示 / 意见反馈 / 检查更新 / 历史上传」这四个依赖已经下线的服务端功能外，
其余页面都已经照着实机截图逐像素对齐：

| 页面 | 与原版的像素差 | 说明 |
|---|---|---|
| 历史页 | 只剩状态栏时钟 | 列表、分页、⋮ 菜单、点一条回填公式 |
| 设置页 | 只剩状态栏时钟 | 含两个开关和偏好对话框 |
| 关于页 | 时钟 + 版本号（0.1.0 vs 2.0.0）+ 图标字形边缘 | 见下；M6 起 logo / 版权行 / 官网地址按设计不同 |
| 键盘 | 只剩图标边缘抗锯齿 | 阈值 30 时 11.9k 像素，阈值 60 时 4.0k |

几条踩出来的坑：

- **历史页不是查一次数据库**。原版 `HistoryDbHelper.readRecord` 是分批循环：
  每批 15 行、最多 5 轮 retry，遇到 `type=5` 且 retry≤1 时 retry 自增，
  `shouldShowAutoResult` 每批复位。只查一批就会少一大半记录。
- **`fallbackLineSpacing`**。原版 targetSdk 停在 2019，这个开关走兼容分支（false）；
  targetSdk 35 默认 true，中文行盒会高 5px，所有页面整体串位。
  主题里给 `textViewStyle` / `checkedTextViewStyle` / `buttonStyle` 各配了一份关掉它。
- **导航栏**。原版是老 targetSdk，导航栏是系统画的不透明黑条；
  新 targetSdk 强制边到边，那一条要自己补（`@color/nav_bar`）。
- **字体大小**。原版用 `WebSettings.setTextSize(LARGER/NORMAL/SMALLER)`，
  对应 textZoom 125/100/75；原版自己在新 WebView 上已经不生效了，我们按源码意图实现。
- **系统输入法**。公式编辑器是个 WebView，MathQuill 的光标其实是隐藏 textarea，
  一拿到焦点系统就把输入法顶上来盖住自绘键盘（原版不会）。编辑器因此换成
  `view/NoImeWebView`：`onCreateInputConnection` 返回 null，系统拿不到输入连接
  就不会弹输入法，触摸、滚动和 JS 侧的焦点/光标都照常。

界面图标早期是**按原位图轮廓重画成矢量**（描图脚本只在开发机上，未随仓库分发）。M6 已经
整批换掉：键盘那批由 `tools/make_keyboard_icons.py` **从开源字体生成**（工具行与
书签的几何图形是脚本里自绘的），结果页三个按钮和工具条的返回 / 分享由
`tools/make_result_icons.py` 按含义**重新设计**，见下面 M6 一节。

> 关于页那个 logo 早先也是这么描出来的（`trace_logo.py`），但那是网易的品牌素材，
> M6 已经整体换成项目自己的标记。

## M4：函数图像

「绘制图像」按钮现在会真的跳到图像页：输入 `y=x^2` 点一下，出来一张和原版
逐像素对齐的抛物线（含焦点/最小值的白点、准线那条橙色虚线）。

几何是**算出来的**，不是摆上去的——原版把每条刻度做成铺满全屏的 View，
位置由屏幕尺寸推出来，这里照搬同一套算式（`graph/GraphAxes.kt`）：

```
posUnit      = (屏幕宽×2/8 + 窗口高×2/15) / 2      # 两个方向同一个值
idealPosUnit = 把 posUnit 规整到「整百减五十」      # 只作缩放阈值
横轴         8 格，标签从 -4 起每格 +2
纵轴        15 格，标签从  8 起每格 -2
0 刻度       由中间那条刻度反推并夹到可视区内（moveZero）
```

参考实机（1280×2800 @ 480dpi）实测：`w=1280, h=2728, 工具栏=168` →
`posUnit=341.5`，竖轴落在 x=688、横轴落在容器 y=1371（屏幕 y=1614）。
我们的日志打出来是同一组数。

画法上有几处「必须照抄的怪癖」，都写在 `graph/GraphPlotView.kt`：

| 现象 | 原因 |
|---|---|
| 网格虚线是两行 50% 灰，不是一行实色 | 参考实现里 `height / 2` 是整数除法，线正好落在半个像素上 |
| 纵轴刻度的文字/小段挂在竖轴上，不是挂在自己的横线上 | 那些 View 的 X 被 `moveZero()` 统一改成了 0 刻度位置 |
| `0` 只画线不写字（横轴那条），纵轴的 `0` 反而往下让 10px | 参考实现里两条轴的 `"0"` 分支写得不一样 |
| 刻度文字基线要用**没取整**的 `bottom - top` | Skia 会把基线吸附到整像素，差 0.3px 就整行跳 1px |
| 缩放重排后 `0` 会变成 `-0`，横轴被画成虚线、文字还偏上 | `0f * (-2f)` 在浮点里是 `-0.0`，`%.4g` 出来就是 `-0`；原版的刻度是累加出来的 `+0.0`，所以这里重排时必须显式写正零 |

曲线按屏幕坐标每 25px 采一个点（和参考实现一样是屏幕空间采样，
`y` 值超出 ±1e6 就夹住），三条函数依次用橙 `#EFB557` / 蓝 `#62ACFF` / 绿 `#4AD17E`。

「特殊点 + 准线/渐近线」来自对二次曲线的解析：参考实现调的是它自己那份
Symja 私有分支里的 `PlotUtils`，源码没公开，这里按标准解析几何重写了一份
（`engine/GraphPlot.kt`），支持圆 / 椭圆 / 双曲线 / 抛物线，
`y=x^2` 给出的就是原版日志里的 `焦点(0,0.25) / 最小值(0,0) / 准线 y=-0.25`。
回归测试见 `engine/src/test/.../GraphPlotTest.kt`。

交互也按原版做了一遍（`graph/GraphPlotView.kt` + `GraphActivity.kt`）：

- **拖动**：单指滑，刻度整体平移、曲线跟着平移，只补两端新露出来的那一小段
  （对应参考实现的 `translateGraph` + `startSup`），不会每帧重算整条曲线；
- **双指缩放**：刻度按焦点缩放，间距越过 `[idealPosUnit, 2×idealPosUnit)` 就换挡
  （步长减半 / 加倍，标签跟着变），曲线先套矩阵跟手、抬手后再按新映射重算；
- **双击**：原版只把事件吞掉、不做任何事，这里也一样；
- 双指回到单指后的 1 秒内不认拖动（`TouchUp2to1FingerGap`），免得抬手被当成拖。

实机对比（模拟器 1280×2800 @480dpi，同一组捏合/滑动事件）：网格线、0 刻度线、
刻度文字、曲线、准线全部落在同一像素位置，全屏差异 0.03%；
几何的回归测试在 `app/src/test/.../graph/GraphAxesTest.kt`（7 条）。

#### 多函数、交点与点选气泡

公式里有多行（编辑器的 `\newline`）时，图像页**从最后一段往前**取函数
（原版 `initFunctions()` 就是这么循环的），所以 `{y=x+1 / y=2x+3}` 里
橙色那条是 `2x+3`、蓝色那条是 `x+1`；最多 3 条，和原版同一个上限。

每条函数会算出三类白点：与 y 轴的交点 `(0, f(0))`、与 x 轴的交点，
以及函数两两之间的交点。原版是逐条拿 Symja `Solve` 解出来的，
这里改成「屏幕 4px 一步找变号 + 二分细化 60 次」——位置一致，还不用拼表达式字符串。

点中任意一个白点会弹出坐标气泡，文字逐条对应原版 `IntersectionView.onClick()`：

| 点在哪 | 气泡第二行 | 说明 |
|---|---|---|
| y 轴交点上 | `2与y轴交点` | 来自该点自己的附加文字（固定点表里查到的） |
| x 轴交点上 | `2与x轴的交点` | 由「点落在哪几条曲线上」现推，注意它比上一行多一个「的」——原版如此 |
| 两函数交点上 | `1,2的交点` | 同上 |
| 空处（缩放后的残留点） | 提示「因缩放精度误差位置不准了…」 | 并把这个点**作废**：不再画、也点不动（原版 `valid=false`） |

坐标按当前刻度精度格式化（`2 - log10(每格数值)` 位小数）再去掉末尾的 0，
所以刻度变粗时坐标也跟着变短。气泡位置沿用了原版那个反直觉的写法：
先读**当前**宽度再 `setText`，于是第一次点开时的横向偏移是占位文字「交点」的一半宽度。

可点范围也对齐了原版那个不对称的盒子：`mPosOffset` 写死 50px、白圈半径 3dp，
盒子中心在点位上但左上只扩 50px、右下扩 65px；多个点叠在一起时后加的在上。
另外原版的高亮白圈**被刻度层盖住**（白圈正中能看到 1px 轴色 `#6B7176`），
所以那个圈画在画布中间层，不在 View 层。

实机比对：三种点各点一遍，除状态栏时钟外全屏差异 0px。

#### 图例

左下角那块「1: y = 2x + 3 / 2: y = x + 1」也是照原版做的：
原版是一个 WebView 加载 `Mathbot Legend.html`（React + MathQuill 静态域），
这里同样用 WebView（`assets/matheditor/legend.html` + `legend.js`），
DOM 结构、30px 颜色列、量尺寸的算法都照抄，量完把 CSS 像素报给 Kotlin，
按 `3 × dpUnit / density`（= 3）换算后摆位。

点某一条会把那条曲线收起来（再点放出来）：曲线不画，属于它的白点也一起收起。
曲线之间的交点归属**编号大的那条**——原版把它存在 `intersectCalculed[j][i]`（j<i）
这个桶里，隐藏 i 会连它一起藏、隐藏 j 不会，这里如实照抄。

> 已知差异（算是修 bug）：原版图例页会去加载一个内网 weinre 调试脚本
> `http://10.236.8.218:8088/...`，连不上时要等网络超时，实测图例常常几十秒都不出现；
> 我们不加载它，图例即时出现。渲染结果与基线截图逐像素一致（差异 0px）。

#### 分享

「分享」按钮做的事和原版一样：把图区截图 → 四周补 75px 底色、底部接一条应用信息图
（原版 `addAppInfoAndSave`）→ 存成图片 → 弹自己画的渠道选择框让用户挑。

两处按新系统改的必要差异：

- 图片走 `FileProvider`（原版是 `Uri.fromFile`，Android 7 起直接抛
  `FileUriExposedException`），文件放在 `cacheDir/share/`；
- 底部那条宣传图不再用原版带二维码的位图——那是原版素材，
  现在按同样的长宽比（750×1039）画我们自己的：底色 + 曲线 + 项目地址，
  全部用画布画出来（M6 已定稿，见下面 M6 一节）。

渠道选择框（`view/ShareChooserDialog.kt`）的布局、文案、颜色都取自原版
`ImgTxtChooserDialog`；列出来的应用来自 `queryIntentActivities(ACTION_SEND, 图片)`，
一个都没有时显示「您暂未安装任何可以分享的渠道」。

## M6：素材与品牌替换

这一步的目标是：仓库里**只剩本项目自己产出的素材**，同时把原版的品牌信息清干净。

| 原版的东西 | 现在是什么 |
|---|---|
| 关于页那个网易品牌 logo（早先按轮廓描的矢量） | 项目自己的标记：冷色圆角方块 + 白色根号 + 琥珀色等号（`bg_about_logo` / `ic_about_logo_mark`） |
| 原版那个橙色方块启动图标 | 同款标记的启动图标：5 档 PNG（API 21-25）+ 自适应图标（API 26+） |
| 「官方网站：math.youdao.com」 | 「项目主页：github.com/MaximeMET/SuperCalculator」 |
| 「Copyright © 2017, NetEase,Inc.」 | 「Copyright © 2026, MaximeMET · GPL-3.0」 |
| 设置/反馈页的「超级计算器 QQ 群：530100431」 | 「开源版本不收集反馈数据 / 有问题请到 GitHub 提 issue」 |
| 分享底图（带二维码的原版位图） | 自绘：底色 + 曲线 + 项目地址 |
| 抽屉里的「超级 24 点」 | 整项删除，连那条分组分隔线一起 |
| 公式编辑器的数学字体 `Symbola` | `STIX Two Math`（SIL OFL-1.1，科技出版领域的标准字体） |

启动图标是脚本生成的，改配色或比例只要改一处：

```powershell
python tools/make_launcher_icon.py
```

标记的几何一共抄了三份——关于页的 `ic_about_logo_mark.xml`、自适应图标前景的
`ic_launcher_foreground.xml`、生成脚本 `tools/make_launcher_icon.py`——改一处就要同步三处。
比例取 0.8 不是随手定的：
自适应图标外圈 18dp 可能被启动器裁掉，缩完之后标记两端离中心 34.3 格，
正好落在圆形蒙版的 36 格半径以内。

#### 编辑器换字体：Symbola → STIX Two Math

MathQuill 上游把 `Symbola` 放在 `src/font/` 里一起分发，但字体作者现在的 UFAS 许可
只给个人非商业使用、**不允许再分发**（引文见 NOTICE.md），所以这个仓库不能带它。
M6 初版先换成了 DejaVu Math TeX Gyre，之后升级为 **STIX Two Math**——它是 IEEE 牵头
为科技出版做的字体（LaTeX 里用 `stix2` 宏包的那套），以 SIL OFL-1.1 发布：

- 许可干净：OFL-1.1 允许再分发、允许随 GPL 项目一起打包，全文随仓库放在
  `licenses/OFL-1.1.txt`，比 DejaVu 那套 Bitstream/DejaVu 混合许可更省心；
- 覆盖率跟原版打平：把编辑器可能用到的 245 个码位逐个查 cmap，Symbola 覆盖 243 个、
  STIX 覆盖 243 个，缺的两个还完全一样——`U+0086`/`U+0087`，是上游 MathQuill 把
  `\dagger`/`\ddagger` 写成 `&#0134;`/`&#135;` 留下的坏引用，本来就是控制字符，
  任何字体都不会有。也就是说编辑器里 Symbola 能出的字形，STIX 一个不少；
- 把 14 个科学符号 Unicode 区段并起来（2784 个码位）再比：STIX 覆盖 2622 个（94%），
  之前的 Termes Math / DejaVu 只有 56%。STIX 缺的 162 个全部落在编辑器出不到的地方
  （数字形式 52 个、希腊字母变体 51 个、数学字母 28 个、上下标字母 19 个……），
  真碰上也会落回系统字体，不会出现豆腐块；
- DejaVu / Termes 缺的那 5 个符号（`ϒ` `ϝ` `▱` `◇` `⬜`）其实编辑器能打出来：
  `\Upsilon`（`&upsih;`）、`\digamma`、`\parallelogram`、`\diamond`、`\square`，
  STIX 全部接住，不会再掉到系统字体；
- 只有一个文件（1.45 MB），换掉了 Symbola 那一套 10 个文件、约 5.8 MB。比 DejaVu
  的 577 KB 大，换来的是上面的覆盖率和"论文公式"的观感。

覆盖率对比（同一份语料，两种统计口径）：

| 字体 | 编辑器 245 码位 | 科学区段 2784 码位 | 许可 |
|---|---|---|---|
| Symbola（原版） | 243 | 2784（100%） | UFAS，禁止再分发 |
| TeX Gyre Termes Math | 238 | 1564（56%） | GUST FL |
| DejaVu Math TeX Gyre（M6 初版） | 238 | 1566（56%） | Bitstream / DejaVu |
| **STIX Two Math（现在）** | **243** | **2622（94%）** | **SIL OFL-1.1** |

换完实测（模拟器 1280×2800 @480，同一串按键；判定方法：裁 (0,250)-(900,560)，
数 RGB 三通道都 > 150 的像素——白色字形墨迹，橙色光标不算）：

| 指标 | 原版（Symbola） | DejaVu Math | 现在（STIX Two） |
|---|---|---|---|
| 单个 `8` 的字形墨迹 | 37 × 60 px | 43 × 64 px | 34 × 56 px |
| 单个 `8` 的墨迹点数 | 742 | 1239 | 865 |
| 光标高度（与字体无关） | 84 px | 84 px | 84 px |
| `x^2+1/2` 整行墨迹宽 | 350 px | 264 px | 234 px |

光标高度一致，说明字号和行盒没动；STIX 的数字比 DejaVu 轻、窄，尺寸和墨迹量都更接近
原版（865 对 742 点），衬线也回到了论文公式的观感。

因为 MathQuill 的 CSS 是 `tools/build-mathquill.ps1` 从上游生成的，
字体替换落在 `tools/mathquill-css-patch.js` 里（换掉 `@font-face` + 按词边界替换
字体栈里的 `Symbola`，`.mq-nonSymbola` 这个类名不能被误伤），构建时会自动套用；
补丁脚本对 Symbola / DejaVu / Termes Math / STIX 四种输入都幂等，重复跑不会改坏。

#### 键盘图标：从「按轮廓重画」到「按字体生成」

M6 的另一件大事是键盘图标。原来那 84 个 drawable（71 个功能键 + 工具行 5 个 +
左侧书签 4 个，加上按下态的白色字形）是照着原版位图**描轮廓**画出来的——形状虽然是
自绘矢量，但仍然是原素材的衍生物。现在整批换成 `tools/make_keyboard_icons.py` 生成：

- 字形走 **Noto Sans SC Regular**（SIL OFL-1.1）：用 uharfbuzz 排版、fontTools 取轮廓，
  直接输出成 vector drawable 的 path。字体只在生成时用，**不随仓库分发**，
  脚本会按需从 noto-cjk 上游下载并核对 SHA-256；
- 工具行（垃圾桶 / 回车 / 箭头 / 退格）和书签的圆底是脚本里自绘的几何图形；
- 每个图标都按**原版墨迹的包围盒**定位：画布尺寸和浅灰占位方块沿用键盘规格，
  字形按高度适配（窄字形如 `1` 的画布会相应加宽），所以键位、行高、留白都没变；
- 公式类图标（`y=kx+b`、`x²/a²+y²/b²=1` …）用一个小型排版器拼：上标/下标走
  缩小偏移的独立文字段，分式是「分子 / 横线 / 分母」三件套。

重建命令：

```powershell
python tools/make_keyboard_icons.py --fetch
```

生成是确定性的：同一份字体跑两次，84 个文件逐字节一致。四个页面的键盘
和原版并排比过（对比图未随仓库分发），字号、位置、留白都对得上，
差别只在字形本身——现在用的是 Noto，不再是原版那套字。

#### 结果页与工具条图标：按含义重画，不临摹

结果页底部那三个按钮（继续编辑 / 清空 / 用结果继续运算）加上工具条上的
返回、分享，一共 5 个图标，早先也是照原版位图描的轮廓。现在由
`tools/make_result_icons.py` 生成，形状只按**这几个图标要表达什么意思**重新设计，
不追求和原版逐像素一致：

| 图标 | 画的是什么 |
| --- | --- |
| `ic_result_resume` | 一张稿纸 + 一支压在右下角的铅笔 |
| `ic_result_clear` | 一块斜放的橡皮，擦头是品牌橙，下面一道擦痕 |
| `ic_result_new` | 一台计算器：橙色显示屏 + 六个按键 |
| `ic_back` | 通用返回箭头（横杆 + 箭头两笔） |
| `ic_share` | 开口方框 + 右上方箭头 |

风格和其它自绘素材是同一套，脚本开头写死了这几个常量，改一处全套跟着变：

- 线稿 `#53595E`——和键盘上的工具图标、字形图标同色；
- 线宽 4/152 ≈ 2dp，圆头线帽 + 圆角接合，和键盘那批细线图标读起来是一套；
- 强调色 `#FFB560`（`values/colors.xml` 的 `brand_accent`），结果页三个图标
  用的是它的极浅色圆底衬；
- 结果页图标沿用布局里的 76dp，`ic_share` 沿用原来的 37×35 画布，
  所以换图形不会挪动工具条和按钮的排版。

只用到纯色，没有 API 24 才认的矢量渐变，minSdk 21 上照样渲染。重建命令：

```powershell
python tools/make_result_icons.py
```

脚本支持 `--check`：文件不是最新时返回非 0，方便接进 CI 或发布前自检。

### 这一步之后还剩什么

- **应用名仍然是「超级计算器」**：抽屉标题、关于页和桌面图标都读 `app_name`，
  和原版同名是为了界面一致。想区分就改 `strings.xml` 里这一条。

## 引擎

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

### 引擎内核：我们改了上游的什么

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
pwsh tools/build-symja.ps1          # -WorkDir 默认 build/symja，是临时目录
```

## 差分测试

「和原版一模一样」不靠感觉，靠差分测试。

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

> `$$` 是结果分隔符：左边是精确解，右边是数值解。

### 语料库与一致率（1140 条）

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

探针本体（`ProbeReceiver.java`）和它的重建脚本（javac → d8 → apktool 编基础包 →
塞 classes3.dex → zipalign → 签名）只在开发机上，不随仓库分发；它跑在插桩过的参考包里，
和本项目的构建无关。

## 开发环境与构建

需要 JDK 17 和 Android SDK（`compileSdk 35`、`build-tools` 35）。SDK 路径放
`local.properties` 里的 `sdk.dir=`，这个文件不进仓库。

构建走仓库自带的 Gradle wrapper（8.11.1，首次运行会去 services.gradle.org 下载，
脚本里带 SHA-256 校验）：

```bash
./gradlew :engine:test                # 引擎（纯 JVM，不需要 Android SDK）
./gradlew :app:testDebugUnitTest      # app 单元测试
./gradlew :app:assembleDebug          # 打包 debug APK
./gradlew :app:installDebug           # 装到已连接的设备/模拟器
```

测试里有两个会往 `engine/build/diagnostic/` 写对照表的诊断用例，
用来人工比对参考 App 的实测输出。

Windows 上把 `./gradlew` 换成 `gradlew.bat` 即可。

### 发布签名

发布包靠仓库根目录的 `keystore.properties`（已在 `.gitignore` 里）签名：

```properties
storeFile=/绝对路径/supercalc-release.jks
storePassword=…
keyAlias=supercalc
keyPassword=…
```

文件在、且 `storeFile` 指向的 keystore 也在，`:app:assembleRelease` 就出签名包；
缺任意一个就退回 unsigned 包——所以外部贡献者 clone 下来不配密钥也能构建。

> ⚠️ **发布密钥丢了，就没法给已经发布的 App 升级**（Android 只认同一个签名，
> 换签名必须卸载重装）。keystore 和口令一定要单独备份。

素材都能从脚本重建：键盘图标 `tools/make_keyboard_icons.py`、
结果页与工具条图标 `tools/make_result_icons.py`、启动图标
`tools/make_launcher_icon.py`、编辑器字体 `tools/fetch-editor-fonts.ps1`、
MathQuill `tools/build-mathquill.ps1`、MathJax `tools/fetch-mathjax.ps1`。
这些只在改素材时用，正常构建不需要跑。

## 依赖与组件

项目整体是 GPL-3.0（因为引擎链接了 GPL-3.0 的 Symja），完整说明和许可证原文见
[NOTICE.md](../NOTICE.md)。这里是开发视角的清单：哪些是原样的、哪些被改过、改过的怎么重建。

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
完整的 MPL-2.0 与 MIT 原文见 [NOTICE.md](../NOTICE.md)。

编辑器还要两套字体，来源都能查：

- `STIXTwoMath-Regular.ttf`：数学字体，排在字体栈最前面。上游原本是 MathQuill 带的
  `Symbola`，但那个字体的许可不允许再分发（详见 [NOTICE.md](../NOTICE.md)），
  换成了 STIX Two Math 2.12——SIL OFL-1.1，科技出版领域的事实标准字体，
  全文见 [licenses/OFL-1.1.txt](../licenses/OFL-1.1.txt)。
  脚本里记了它的 SHA-256（`562551B1…36DE`），换文件必须同步改哈希；
  这个 ttf 取自 Google Fonts 的官方镜像（与 stixfonts 上游同源）。
- `texgyretermes-*.otf`：编辑器的 `fonts.css` 把 `"Times New Roman"` 指向它，
  取自 CTAN 上游 2.004 版，GUST Font License，允许原样再分发。重建命令：

  ```powershell
  pwsh tools/fetch-editor-fonts.ps1
  ```

  脚本会逐个核对 SHA-256，对不上就报错（`tools/build-mathquill.ps1` 结尾也会自动调它，
  因为上游 `src/font/` 里只有 Symbola，那批文件我们已经不带了）。

`app/src/main/assets/mathjax/` 是从官方 npm 包 `mathjax@3.2.2` 的 `es5/` 目录里
挑出来的一小套离线运行时（`tex-svg.js`、`output/svg/fonts/tex.js`、
几个 TeX 扩展、`ui/menu.js`、`a11y/assistive-mml.js`），
不是原版 App 里那份 MathJax 2.7 / STIX-Web 的拷贝。
重建命令：

```powershell
pwsh tools/fetch-mathjax.ps1
```

## 与参考实现的关系

原版 `com.youdao.calculator` v2.0.0 只作为**行为规格**使用：观察交互与布局、
提取功能清单和键盘键位表、用 logcat 采期望值。仓库里不包含、也不分发原版的
任何代码、图片、字体或品牌素材——完整说明见
[README](../README.md#与参考实现的关系) 与 [NOTICE.md](../NOTICE.md)。
