# 编辑器回归对照（MathQuill 命令层）

这套脚本用来回答一个问题：**我们的公式编辑器和原版「超级计算器」行为是否一致。**

答案来自原版 APK，而不是猜：

- 原版 APK 里的 `mathquill.min.js` 是一个**带 `symja()` 的分支**（0.10.0 + 官方定制），
  每个命令自己会算引擎输入。我们把同一个二进制加载到浏览器里当**活体标尺**，
  跑同一张键盘命令表，逐条比对 `latex()` / 渲染出来的 HTML / `symja()`。
- 键盘命令表（哪个键写什么、插完光标左移几次）抄自原版
  `assets/edit/Mathbot Editor_files/bundle.min.js`，见 `keylist.py`。
- 对照结果已经固化成 `golden-keys-orig.json`（原版的实测数据，49 KB）。
  它是**行为规格**，不是代码，可以随仓库分发；原版二进制不行。

## 目录里有什么

| 文件 | 作用 |
|---|---|
| `keylist.py` | 键盘命令表 + 连打序列（两边探针共用） |
| `bare_probe.py` | 生成「裸 MathQuill」探针页，喂给原版或我们的构建 |
| `editor_probe.py` | 生成「真 editor.html」探针页，测的就是要打进 APK 的那套文件 |
| `diff_keys.py` | 并排比对两份 JSON，输出报告 |
| `serve.js` | 静态服务器（两个根目录：assets 和临时工作目录） |
| `run_probes.ps1` | 一键跑完全流程 |
| `golden-keys-orig.json` | 原版实测基准（**改命令层之前请先看它**） |

## 怎么跑

### 1. 只看基准（不需要原版 APK）

```powershell
pwsh tools/matheditor/run_probes.ps1 -SkipOrig
```

这会拿我们自己的构建跑一遍命令表，再和 `golden-keys-orig.json` 比。
输出的报告里，逐键 latex / 渲染结构 / symja 应该**全绿**。

### 2. 完整对照（需要原版 APK，仅开发机）

把原版 `mathquill.min.js` 放到 `build/matheditor-probe/orig-mq/`，
然后：

```powershell
pwsh tools/matheditor/run_probes.ps1
```

### 前置条件

- Node（`npx` 可用，脚本用 `@playwright/cli` + 本机已装的 Edge）
- 浏览器：`--browser msedge`

## 改命令层时的注意

`app/src/main/assets/matheditor/editor.js` 里每个命令的
`ctrlSeq` / 槽位顺序 / `htmlTemplate` / `latex()` / `symja()` 都是对着基准写的，
不是随手编的，改完必须让这套对照全绿。几个已经踩过的坑：

1. **槽位标记 `&N` 必须是所在元素的全部内容，而且要紧跟在 `>` 后面。**
   写成 `<span class="mq-sqrt"><span>r²</span>&2</span>` 的话，
   `mathquill-block-id` 会落到闭合标签上——渲染看起来是对的，
   但这个槽位其实没挂上（空槽不会有 `mq-empty` 类，光标也进不去）。
   正确写法是把标记放最前面：`<span class="mq-sqrt">&2<span>r²</span></span>`。
2. **槽位数组的顺序 = 光标从右往左碰到的顺序**（第 N 个槽位左移 N 次）。
   原版好几个命令的视觉顺序和 `latex()` 顺序并不一致，只能照抄实测值。
3. **`MathQuill.L` 是 `-1`，不是 `0`**（`R` 是 `1`）。
4. **空槽位在 `latex()` 里通常是 `{ }`（带空格），但原版有些命令是 `{}`。**
   后者说明那个命令自己写死了 `latex()`，别用默认实现。
5. **`MathCommand` 默认的 `latex()` 只吐出 `ctrlSeq + {槽位}`**，
   公式模板那批命令的 `y=`、`+`、`=` 都在 `htmlTemplate` 里，得自己拼。
6. **隐式乘法补 `*`**：Symja 不认 `5x`，所以系数槽非空时要补 `*`，
   空的时候又不能留一个孤零零的 `*`——原版就是这么处理的（`y=5x` → `y==5*x`，
   但 `y=x` → `y==x`）。
