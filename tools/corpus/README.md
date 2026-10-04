# 差分测试语料

这个目录放的是「跑分」用的题目清单和对比脚本：把同样的表达式分别喂给
**原版参考 App**（插桩探针）和**本仓库的引擎**，逐条对比输出。
改引擎之前跑一遍，能看出「修好一个、弄坏三个」。

## 目录里有什么

| 文件 | 内容 | 能不能公开 |
|---|---|---|
| `in.txt` | 题目清单，1140 条 | ✅ 我们自己生成的，欢迎加题 |
| `legacy-216.txt` | 最早那批 216 条（生成器的种子） | ✅ |
| `gen_corpus.py` | 按类别补题、去重、写出 `in.txt` | ✅ |
| `compare.py` | 一致率汇总 + 差异摘录 | ✅ |
| `diff_full.py` | 全量差异，按按钮分组 | ✅ |
| `run-diff.ps1` | 一键：拷清单 → 引擎批跑 → 对比 | ✅ |

**期望值不在这里**。`ref_auto.tsv` / `ref_methods.tsv` / `ref_draw.tsv`
是从原版实测出来的数据（原版的衍生物），放在私有子模块 `work/corpus/`。
没有子模块权限也能正常构建，只是跑不了对比。

## 怎么跑

```powershell
# 仓库根目录，Windows PowerShell
pwsh tools/corpus/run-diff.ps1
```

它做三件事：

1. 把 `in.txt` 拷到 `engine/build/corpus/in.txt`（`CorpusRunnerTest` 固定读这里）；
2. 跑 `gradlew :engine:test --tests "*CorpusRunnerTest*"`，把本引擎的输出写成
   `engine/build/diagnostic/engine_{auto,methods,draw}.tsv`（格式和探针产物一致）；
3. 调 `compare.py` 和私有基准对比，打印一致率与差异。

只想对比不上探针的话，第 2、3 步就够了。

## 加题

直接往 `in.txt` 末尾加一行（或改进 `gen_corpus.py` 再重新生成），然后
`run-diff.ps1`。**注意**：新题在私有基准里还没有期望值，对比时会跳过
（`compare.py` 只比对双方都有的表达式）；要让它进基准，得用
`work/probe/` 里的插桩探针在原版上跑一遍——那是维护者的事，PR 只加题目就行。

## 历史

- 216 条：最早的手工清单，用来对齐四则运算和基础函数。
- 1140 条：按类别补厚（幂/根/三角/对数/多项式/方程/不等式/边界错误/特殊写法），
  当前基准：自动预览 100%、按钮集合 100%、按钮结果 99.2%、绘图公式 100%。
